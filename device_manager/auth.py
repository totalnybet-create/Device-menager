from __future__ import annotations

import hashlib
import secrets
from dataclasses import dataclass
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from .models import AgentCredential, AuditEvent, Device, UserPrincipal

VALID_ROLES = frozenset({"admin", "operator", "read-only"})
ROLE_PERMISSIONS = {
    "read-only": frozenset({"devices:read"}),
    "operator": frozenset({"devices:read", "devices:create", "devices:update"}),
    "admin": frozenset({"devices:read", "devices:create", "devices:update", "devices:delete"}),
}


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def generate_token() -> str:
    return secrets.token_urlsafe(32)


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class IssuedToken:
    token: str
    token_hash: str


class AuthRepository:
    def __init__(self, session: Session):
        self.session = session

    def create_user(self, *, name: str, role: str, token_hash_value: str) -> UserPrincipal:
        principal = UserPrincipal(name=name, role=role, token_hash=token_hash_value, active=True)
        self.session.add(principal)
        self.session.commit()
        self.session.refresh(principal)
        return principal

    def user_by_hash(self, token_hash_value: str) -> UserPrincipal | None:
        statement = select(UserPrincipal).where(
            UserPrincipal.token_hash == token_hash_value,
            UserPrincipal.active.is_(True),
        )
        return self.session.scalar(statement)

    def agent_credential_by_hash(self, token_hash_value: str) -> AgentCredential | None:
        statement = select(AgentCredential).where(
            AgentCredential.token_hash == token_hash_value,
            AgentCredential.active.is_(True),
        )
        return self.session.scalar(statement)

    def credential_for_device(self, device_id: int) -> AgentCredential | None:
        statement = select(AgentCredential).where(AgentCredential.device_id == device_id)
        return self.session.scalar(statement)

    def issue_agent_credential(self, device_id: int, token_hash_value: str) -> AgentCredential:
        credential = self.credential_for_device(device_id)
        if credential is None:
            credential = AgentCredential(
                device_id=device_id,
                token_hash=token_hash_value,
                active=True,
            )
            self.session.add(credential)
        else:
            credential.token_hash = token_hash_value
            credential.active = True
            credential.created_at = utcnow()
            credential.last_used_at = None

        self.session.commit()
        self.session.refresh(credential)
        return credential

    def touch_agent_credential(self, credential: AgentCredential) -> None:
        credential.last_used_at = utcnow()
        self.session.commit()

    def device_for_credential(self, credential: AgentCredential) -> Device | None:
        return self.session.get(Device, credential.device_id)

    def append_audit(
        self,
        *,
        actor_type: str,
        actor_id: str | None,
        action: str,
        outcome: str,
        resource_type: str | None = None,
        resource_id: str | None = None,
        detail: str | None = None,
    ) -> AuditEvent:
        event = AuditEvent(
            actor_type=actor_type,
            actor_id=actor_id,
            action=action,
            resource_type=resource_type,
            resource_id=resource_id,
            outcome=outcome,
            detail=detail,
        )
        self.session.add(event)
        self.session.commit()
        self.session.refresh(event)
        return event


class AuthService:
    def __init__(self, repository: AuthRepository):
        self.repository = repository

    def create_user(self, *, name: str, role: str) -> tuple[UserPrincipal, IssuedToken]:
        name = name.strip()
        if not name:
            raise ValueError("name must not be blank")
        if role not in VALID_ROLES:
            raise ValueError(f"invalid role: {role}")

        token = generate_token()
        issued = IssuedToken(token=token, token_hash=hash_token(token))
        principal = self.repository.create_user(
            name=name,
            role=role,
            token_hash_value=issued.token_hash,
        )
        self.repository.append_audit(
            actor_type="system",
            actor_id=None,
            action="user_credential_created",
            resource_type="user",
            resource_id=str(principal.id),
            outcome="success",
        )
        return principal, issued

    def authenticate_user(self, token: str) -> UserPrincipal | None:
        if not token:
            return None
        return self.repository.user_by_hash(hash_token(token))

    @staticmethod
    def has_permission(principal: UserPrincipal, permission: str) -> bool:
        return permission in ROLE_PERMISSIONS.get(principal.role, frozenset())

    def issue_agent_token(self, device_id: int) -> IssuedToken:
        token = generate_token()
        issued = IssuedToken(token=token, token_hash=hash_token(token))
        self.repository.issue_agent_credential(device_id, issued.token_hash)
        self.repository.append_audit(
            actor_type="system",
            actor_id=None,
            action="agent_credential_issued",
            resource_type="device",
            resource_id=str(device_id),
            outcome="success",
        )
        return issued

    def authenticate_agent(self, token: str) -> tuple[AgentCredential, Device] | None:
        if not token:
            return None
        credential = self.repository.agent_credential_by_hash(hash_token(token))
        if credential is None:
            return None
        device = self.repository.device_for_credential(credential)
        if device is None:
            return None
        self.repository.touch_agent_credential(credential)
        return credential, device

    def audit_user_action(
        self,
        principal: UserPrincipal,
        *,
        action: str,
        outcome: str,
        resource_type: str | None = None,
        resource_id: str | None = None,
        detail: str | None = None,
    ) -> None:
        self.repository.append_audit(
            actor_type="user",
            actor_id=str(principal.id),
            action=action,
            outcome=outcome,
            resource_type=resource_type,
            resource_id=resource_id,
            detail=detail,
        )

    def audit_agent_action(
        self,
        device: Device,
        *,
        action: str,
        outcome: str,
        detail: str | None = None,
    ) -> None:
        self.repository.append_audit(
            actor_type="agent",
            actor_id=device.agent_id,
            action=action,
            outcome=outcome,
            resource_type="device",
            resource_id=str(device.id),
            detail=detail,
        )

from __future__ import annotations

import os
import secrets
from collections.abc import Callable, Generator
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response, status
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import text
from sqlalchemy.orm import Session

from .agent_service import AgentNotRegisteredError, AgentService
from .auth import AuthRepository, AuthService
from .database import Base, create_session_factory, make_engine
from .models import UserPrincipal
from .repository import DeviceRepository
from .schemas import (
    AgentHeartbeat,
    AgentRegistration,
    AgentRegistrationResult,
    DeviceCreate,
    DeviceRead,
    DeviceUpdate,
    HealthRead,
)
from .service import DeviceNotFoundError, DeviceService


def create_app(
    database_url: str | None = None,
    *,
    create_schema: bool = False,
    enrollment_token: str | None = None,
) -> FastAPI:
    engine = make_engine(database_url)
    session_factory = create_session_factory(engine)
    configured_enrollment_token = enrollment_token or os.getenv(
        "DEVICE_MANAGER_AGENT_ENROLLMENT_TOKEN"
    )
    bearer = HTTPBearer(auto_error=False)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if create_schema:
            Base.metadata.create_all(engine)
        app.state.engine = engine
        app.state.session_factory = session_factory
        app.state.enrollment_token = configured_enrollment_token
        yield
        engine.dispose()

    app = FastAPI(
        title="Device Manager API",
        version="2.4.0",
        lifespan=lifespan,
    )

    def get_session(request: Request) -> Generator[Session, None, None]:
        with request.app.state.session_factory() as session:
            yield session

    def get_service(session: Session = Depends(get_session)) -> DeviceService:
        return DeviceService(DeviceRepository(session))

    def get_agent_service(session: Session = Depends(get_session)) -> AgentService:
        return AgentService(DeviceRepository(session))

    def get_auth_service(session: Session = Depends(get_session)) -> AuthService:
        return AuthService(AuthRepository(session))

    def get_current_user(
        credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
        auth: AuthService = Depends(get_auth_service),
    ) -> UserPrincipal:
        if credentials is None or credentials.scheme.lower() != "bearer":
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="authentication required",
                headers={"WWW-Authenticate": "Bearer"},
            )

        principal = auth.authenticate_user(credentials.credentials)
        if principal is None:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="invalid credentials",
                headers={"WWW-Authenticate": "Bearer"},
            )
        return principal

    def require_permission(permission: str) -> Callable:
        def dependency(
            principal: UserPrincipal = Depends(get_current_user),
            auth: AuthService = Depends(get_auth_service),
        ) -> UserPrincipal:
            if not auth.has_permission(principal, permission):
                auth.audit_user_action(
                    principal,
                    action=permission,
                    outcome="denied",
                    detail="insufficient role permission",
                )
                raise HTTPException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    detail="insufficient permission",
                )
            return principal

        return dependency

    @app.exception_handler(DeviceNotFoundError)
    async def device_not_found_handler(
        _request: Request,
        exc: DeviceNotFoundError,
    ) -> JSONResponse:
        return JSONResponse(
            status_code=status.HTTP_404_NOT_FOUND,
            content={"detail": f"device {exc.device_id} not found"},
        )

    @app.exception_handler(AgentNotRegisteredError)
    async def agent_not_registered_handler(
        _request: Request,
        exc: AgentNotRegisteredError,
    ) -> JSONResponse:
        return JSONResponse(
            status_code=status.HTTP_404_NOT_FOUND,
            content={"detail": f"agent {exc.agent_id} not registered"},
        )

    @app.get("/health", response_model=HealthRead)
    def health() -> HealthRead:
        return HealthRead(status="ok")

    @app.get("/ready", response_model=HealthRead)
    def ready(session: Session = Depends(get_session)) -> HealthRead:
        try:
            session.execute(text("SELECT 1"))
        except Exception as exc:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="database unavailable",
            ) from exc
        return HealthRead(status="ready")

    @app.get("/devices", response_model=list[DeviceRead])
    def list_devices(
        service: DeviceService = Depends(get_service),
        _principal: UserPrincipal = Depends(require_permission("devices:read")),
    ):
        return service.list_devices()

    @app.get("/devices/{device_id}", response_model=DeviceRead)
    def get_device(
        device_id: int,
        service: DeviceService = Depends(get_service),
        _principal: UserPrincipal = Depends(require_permission("devices:read")),
    ):
        return service.get_device(device_id)

    @app.post(
        "/devices",
        response_model=DeviceRead,
        status_code=status.HTTP_201_CREATED,
    )
    def create_device(
        payload: DeviceCreate,
        service: DeviceService = Depends(get_service),
        auth: AuthService = Depends(get_auth_service),
        principal: UserPrincipal = Depends(require_permission("devices:create")),
    ):
        device = service.create_device(payload)
        auth.audit_user_action(
            principal,
            action="devices:create",
            outcome="success",
            resource_type="device",
            resource_id=str(device.id),
        )
        return device

    @app.patch("/devices/{device_id}", response_model=DeviceRead)
    def update_device(
        device_id: int,
        payload: DeviceUpdate,
        service: DeviceService = Depends(get_service),
        auth: AuthService = Depends(get_auth_service),
        principal: UserPrincipal = Depends(require_permission("devices:update")),
    ):
        device = service.update_device(device_id, payload)
        auth.audit_user_action(
            principal,
            action="devices:update",
            outcome="success",
            resource_type="device",
            resource_id=str(device.id),
        )
        return device

    @app.delete("/devices/{device_id}", status_code=status.HTTP_204_NO_CONTENT)
    def delete_device(
        device_id: int,
        service: DeviceService = Depends(get_service),
        auth: AuthService = Depends(get_auth_service),
        principal: UserPrincipal = Depends(require_permission("devices:delete")),
    ) -> Response:
        service.delete_device(device_id)
        auth.audit_user_action(
            principal,
            action="devices:delete",
            outcome="success",
            resource_type="device",
            resource_id=str(device_id),
        )
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    @app.post("/agents/register", response_model=AgentRegistrationResult)
    def register_agent(
        payload: AgentRegistration,
        request: Request,
        x_agent_enrollment_token: str | None = Header(
            default=None,
            alias="X-Agent-Enrollment-Token",
        ),
        service: AgentService = Depends(get_agent_service),
        auth: AuthService = Depends(get_auth_service),
    ) -> AgentRegistrationResult:
        expected = request.app.state.enrollment_token
        if not expected:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="agent enrollment is not configured",
            )
        if not x_agent_enrollment_token or not secrets.compare_digest(
            x_agent_enrollment_token,
            expected,
        ):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="invalid enrollment credential",
            )

        device = service.register(payload)
        issued = auth.issue_agent_token(device.id)
        auth.audit_agent_action(
            device,
            action="agent:register",
            outcome="success",
        )
        return AgentRegistrationResult(device=DeviceRead.model_validate(device), agent_token=issued.token)

    @app.post("/agents/heartbeat", response_model=DeviceRead)
    def heartbeat_agent(
        payload: AgentHeartbeat,
        credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
        service: AgentService = Depends(get_agent_service),
        auth: AuthService = Depends(get_auth_service),
    ):
        if credentials is None or credentials.scheme.lower() != "bearer":
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="agent authentication required",
                headers={"WWW-Authenticate": "Bearer"},
            )

        authenticated = auth.authenticate_agent(credentials.credentials)
        if authenticated is None:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="invalid agent credential",
                headers={"WWW-Authenticate": "Bearer"},
            )
        _credential, credential_device = authenticated
        if credential_device.agent_id != str(payload.agent_id):
            auth.audit_agent_action(
                credential_device,
                action="agent:heartbeat",
                outcome="denied",
                detail="agent id mismatch",
            )
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="credential does not belong to agent",
            )

        device = service.heartbeat(payload)
        auth.audit_agent_action(
            device,
            action="agent:heartbeat",
            outcome="success",
        )
        return device

    return app


app = create_app()

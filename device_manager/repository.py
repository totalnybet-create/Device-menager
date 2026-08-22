from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from .models import Device
from .schemas import DeviceCreate, DeviceUpdate


def _as_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


class DeviceRepository:
    def __init__(self, session: Session):
        self.session = session

    def list(self) -> list[Device]:
        statement = select(Device).order_by(Device.id)
        return list(self.session.scalars(statement))

    def get(self, device_id: int) -> Device | None:
        return self.session.get(Device, device_id)

    def get_by_agent_id(self, agent_id: str) -> Device | None:
        statement = select(Device).where(Device.agent_id == agent_id)
        return self.session.scalar(statement)

    def create(self, payload: DeviceCreate) -> Device:
        device = Device(**payload.model_dump())
        self.session.add(device)
        self.session.commit()
        self.session.refresh(device)
        return device

    def update(self, device_id: int, payload: DeviceUpdate) -> Device | None:
        device = self.get(device_id)
        if device is None:
            return None

        changes = payload.model_dump(exclude_unset=True)
        for field, value in changes.items():
            if value is not None:
                setattr(device, field, value)

        self.session.commit()
        self.session.refresh(device)
        return device

    def delete(self, device_id: int) -> bool:
        device = self.get(device_id)
        if device is None:
            return False

        self.session.delete(device)
        self.session.commit()
        return True

    def create_agent_device(
        self,
        *,
        agent_id: str,
        name: str,
        device_type: str,
        status: str,
        hostname: str,
        platform: str,
        agent_version: str,
        last_seen_at: datetime,
    ) -> Device:
        device = Device(
            agent_id=agent_id,
            name=name,
            device_type=device_type,
            status=status,
            hostname=hostname,
            platform=platform,
            agent_version=agent_version,
            last_seen_at=last_seen_at,
        )
        self.session.add(device)
        self.session.commit()
        self.session.refresh(device)
        return device

    def update_agent_registration(
        self,
        device: Device,
        *,
        name: str,
        device_type: str,
        status: str,
        hostname: str,
        platform: str,
        agent_version: str,
        last_seen_at: datetime,
    ) -> Device:
        device.name = name
        device.device_type = device_type
        device.status = status
        device.hostname = hostname
        device.platform = platform
        device.agent_version = agent_version
        self._advance_last_seen(device, last_seen_at)
        self.session.commit()
        self.session.refresh(device)
        return device

    def update_agent_heartbeat(
        self,
        device: Device,
        *,
        status: str,
        last_seen_at: datetime,
    ) -> Device:
        device.status = status
        self._advance_last_seen(device, last_seen_at)
        self.session.commit()
        self.session.refresh(device)
        return device

    @staticmethod
    def _advance_last_seen(device: Device, incoming: datetime) -> None:
        if device.last_seen_at is None:
            device.last_seen_at = incoming
            return

        if _as_utc(incoming) >= _as_utc(device.last_seen_at):
            device.last_seen_at = incoming

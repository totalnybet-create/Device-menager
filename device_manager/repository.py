from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from .models import Device
from .schemas import DeviceCreate, DeviceUpdate


class DeviceRepository:
    def __init__(self, session: Session):
        self.session = session

    def list(self) -> list[Device]:
        statement = select(Device).order_by(Device.id)
        return list(self.session.scalars(statement))

    def get(self, device_id: int) -> Device | None:
        return self.session.get(Device, device_id)

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

from __future__ import annotations

from .models import Device
from .repository import DeviceRepository
from .schemas import DeviceCreate, DeviceUpdate


class DeviceNotFoundError(LookupError):
    def __init__(self, device_id: int):
        self.device_id = device_id
        super().__init__(f"Device {device_id} not found")


class DeviceService:
    """Business boundary for device operations."""

    def __init__(self, repository: DeviceRepository):
        self.repository = repository

    def list_devices(self) -> list[Device]:
        return self.repository.list()

    def get_device(self, device_id: int) -> Device:
        device = self.repository.get(device_id)
        if device is None:
            raise DeviceNotFoundError(device_id)
        return device

    def create_device(self, payload: DeviceCreate) -> Device:
        return self.repository.create(payload)

    def update_device(self, device_id: int, payload: DeviceUpdate) -> Device:
        device = self.repository.update(device_id, payload)
        if device is None:
            raise DeviceNotFoundError(device_id)
        return device

    def delete_device(self, device_id: int) -> None:
        if not self.repository.delete(device_id):
            raise DeviceNotFoundError(device_id)

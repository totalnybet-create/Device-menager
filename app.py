# ==========================================
# DEVICE MANAGER - FULL PAKIET (WERSJA 2.1)
# Trwała baza SQLite + SQLAlchemy
# ==========================================

from __future__ import annotations

from device_manager.database import Base, create_session_factory, make_engine
from device_manager.repository import DeviceRepository
from device_manager.schemas import DeviceCreate, DeviceUpdate


INITIAL_DEVICES = (
    DeviceCreate(name="Serwer Główny", device_type="Server", status="Aktywny"),
    DeviceCreate(name="Router Biurowy", device_type="Network", status="Aktywny"),
    DeviceCreate(name="Drukarka Piętro 2", device_type="Peripheral", status="Offline"),
)


class DeviceManager:
    def __init__(self, database_url: str | None = None):
        self.engine = make_engine(database_url)
        Base.metadata.create_all(self.engine)
        self.session_factory = create_session_factory(self.engine)
        self._seed_initial_devices()

    def _seed_initial_devices(self) -> None:
        with self.session_factory() as session:
            repository = DeviceRepository(session)
            if repository.list():
                return
            for device in INITIAL_DEVICES:
                repository.create(device)

    @property
    def devices(self) -> list[dict]:
        with self.session_factory() as session:
            repository = DeviceRepository(session)
            return [
                {
                    "id": device.id,
                    "name": device.name,
                    "type": device.device_type,
                    "status": device.status,
                }
                for device in repository.list()
            ]

    def list_devices(self) -> None:
        devices = self.devices
        print("\n--- LISTA ZAREJESTROWANYCH URZĄDZEŃ ---")
        if not devices:
            print("Brak urządzeń w bazie.")
            return

        for dev in devices:
            print(
                f"ID: {dev['id']} | Nazwa: {dev['name']} | "
                f"Typ: {dev['type']} | Status: {dev['status']}"
            )
        print("-" * 45)

    def add_device(self, name: str, device_type: str, status: str = "Aktywny") -> int:
        with self.session_factory() as session:
            repository = DeviceRepository(session)
            device = repository.create(
                DeviceCreate(name=name, device_type=device_type, status=status)
            )
            print(f"[SUKCES] Dodano urządzenie: {device.name} (ID: {device.id})")
            return device.id

    def remove_device(self, device_id: int) -> bool:
        with self.session_factory() as session:
            repository = DeviceRepository(session)
            removed = repository.delete(device_id)

        if removed:
            print(f"[SUKCES] Usunięto urządzenie o ID: {device_id}")
        else:
            print(f"[BŁĄD] Nie znaleziono urządzenia o ID: {device_id}")
        return removed

    def change_status(self, device_id: int, new_status: str) -> bool:
        with self.session_factory() as session:
            repository = DeviceRepository(session)
            device = repository.update(device_id, DeviceUpdate(status=new_status))

        if device is None:
            print(f"[BŁĄD] Nie znaleziono urządzenia o ID: {device_id}")
            return False

        print(f"[SUKCES] Zmieniono status urządzenia ID {device_id} na: {device.status}")
        return True

    def close(self) -> None:
        self.engine.dispose()


def main() -> None:
    print("=== DEVICE MANAGER v2.1 — TRWAŁA BAZA URZĄDZEŃ ===")
    manager = DeviceManager()
    try:
        manager.list_devices()
        print("Dane są przechowywane w SQLite. Testy mutacji uruchamia CI/unittest.")
    finally:
        manager.close()


if __name__ == "__main__":
    main()

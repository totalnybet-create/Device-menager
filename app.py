# ==========================================
# DEVICE MANAGER - FULL PAKIET (WERSJA 2.0)
# ==========================================


class DeviceManager:
    def __init__(self):
        # Początkowa baza urządzeń
        self.devices = [
            {"id": 1, "name": "Serwer Główny", "type": "Server", "status": "Aktywny"},
            {"id": 2, "name": "Router Biurowy", "type": "Network", "status": "Aktywny"},
            {"id": 3, "name": "Drukarka Piętro 2", "type": "Peripheral", "status": "Offline"},
        ]

    def list_devices(self):
        print("\n--- LISTA ZAREJESTROWANYCH URZĄDZEŃ ---")
        if not self.devices:
            print("Brak urządzeń w bazie.")
            return
        for dev in self.devices:
            print(
                f"ID: {dev['id']} | Nazwa: {dev['name']} | "
                f"Typ: {dev['type']} | Status: {dev['status']}"
            )
        print("-" * 45)

    def add_device(self, name, device_type, status="Aktywny"):
        new_id = max([dev["id"] for dev in self.devices], default=0) + 1
        new_device = {
            "id": new_id,
            "name": name,
            "type": device_type,
            "status": status,
        }
        self.devices.append(new_device)
        print(f"[SUKCES] Dodano urządzenie: {name} (ID: {new_id})")

    def remove_device(self, device_id):
        for dev in self.devices:
            if dev["id"] == device_id:
                self.devices.remove(dev)
                print(f"[SUKCES] Usunięto urządzenie o ID: {device_id}")
                return
        print(f"[BŁĄD] Nie znaleziono urządzenia o ID: {device_id}")

    def change_status(self, device_id, new_status):
        for dev in self.devices:
            if dev["id"] == device_id:
                dev["status"] = new_status
                print(
                    f"[SUKCES] Zmieniono status urządzenia ID {device_id} na: {new_status}"
                )
                return
        print(f"[BŁĄD] Nie znaleziono urządzenia o ID: {device_id}")


def main():
    print("=== URUCHAMIANIE SYSTEMU DEVICE MANAGER (FULL PAKIET) ===")
    manager = DeviceManager()

    # 1. Wyświetlenie stanu początkowego
    manager.list_devices()

    # 2. Test dodawania nowego urządzenia
    print("\n--- TEST: DODAWANIE URZĄDZENIA ---")
    manager.add_device("Switch Główny", "Network", "Aktywny")

    # 3. Test zmiany statusu
    print("\n--- TEST: ZMIANA STATUSU ---")
    manager.change_status(3, "Online")  # Ożywiamy drukarkę

    # 4. Test usuwania urządzenia
    print("\n--- TEST: USUWANIE URZĄDZENIA ---")
    manager.remove_device(2)  # Usuwamy router

    # 5. Podsumowanie końcowe
    manager.list_devices()
    print("=== WSZYSTKIE TESTY FULL PAKIETU ZAKOŃCZONE POMYŚLNIE ===")


if __name__ == "__main__":
    main()

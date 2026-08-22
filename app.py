# Prosta aplikacja Device Manager
def main():
    print("=== SYSTEM ZARZĄDZANIA URZĄDZENIAMI ===")
    
    # Przykładowe urządzenie startowe
    device = {
        "name": "Serwer Główny",
        "status": "Aktywny",
        "ip": "192.168.1.100"
    }
    
    print(f"Urządzenie: {device['name']}")
    print(f"Status: {device['status']}")
    print(f"Adres IP: {device['ip']}")
    print("========================================")
    print("Aplikacja działa poprawnie!")

if __name__ == "__main__":
    main()

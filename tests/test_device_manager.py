import tempfile
import unittest
from pathlib import Path

from app import DeviceManager


class DeviceManagerTests(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        db_path = Path(self.tempdir.name) / "manager.db"
        self.manager = DeviceManager(f"sqlite:///{db_path}")

    def tearDown(self):
        self.manager.close()
        self.tempdir.cleanup()

    def test_initial_devices(self):
        self.assertEqual(len(self.manager.devices), 3)
        self.assertEqual([device["id"] for device in self.manager.devices], [1, 2, 3])

    def test_add_device_assigns_next_id(self):
        created_id = self.manager.add_device("Switch Główny", "Network")

        created = self.manager.devices[-1]
        self.assertEqual(created_id, 4)
        self.assertEqual(created["id"], 4)
        self.assertEqual(created["name"], "Switch Główny")
        self.assertEqual(created["type"], "Network")
        self.assertEqual(created["status"], "Aktywny")

    def test_change_status(self):
        self.assertTrue(self.manager.change_status(3, "Online"))

        printer = next(device for device in self.manager.devices if device["id"] == 3)
        self.assertEqual(printer["status"], "Online")

    def test_remove_device(self):
        self.assertTrue(self.manager.remove_device(2))

        self.assertNotIn(2, [device["id"] for device in self.manager.devices])
        self.assertEqual(len(self.manager.devices), 2)

    def test_missing_device_does_not_mutate_collection(self):
        before = [device.copy() for device in self.manager.devices]

        self.assertFalse(self.manager.change_status(999, "Offline"))
        self.assertFalse(self.manager.remove_device(999))

        self.assertEqual(self.manager.devices, before)

    def test_data_survives_manager_restart(self):
        self.manager.add_device("AP Magazyn", "Network")
        db_url = str(self.manager.engine.url)
        self.manager.close()

        restarted = DeviceManager(db_url)
        try:
            names = [device["name"] for device in restarted.devices]
            self.assertIn("AP Magazyn", names)
        finally:
            restarted.close()


if __name__ == "__main__":
    unittest.main()

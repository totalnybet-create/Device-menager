import unittest

from app import DeviceManager


class DeviceManagerTests(unittest.TestCase):
    def setUp(self):
        self.manager = DeviceManager()

    def test_initial_devices(self):
        self.assertEqual(len(self.manager.devices), 3)
        self.assertEqual([device["id"] for device in self.manager.devices], [1, 2, 3])

    def test_add_device_assigns_next_id(self):
        self.manager.add_device("Switch Główny", "Network")

        created = self.manager.devices[-1]
        self.assertEqual(created["id"], 4)
        self.assertEqual(created["name"], "Switch Główny")
        self.assertEqual(created["type"], "Network")
        self.assertEqual(created["status"], "Aktywny")

    def test_change_status(self):
        self.manager.change_status(3, "Online")

        printer = next(device for device in self.manager.devices if device["id"] == 3)
        self.assertEqual(printer["status"], "Online")

    def test_remove_device(self):
        self.manager.remove_device(2)

        self.assertNotIn(2, [device["id"] for device in self.manager.devices])
        self.assertEqual(len(self.manager.devices), 2)

    def test_missing_device_does_not_mutate_collection(self):
        before = [device.copy() for device in self.manager.devices]

        self.manager.change_status(999, "Offline")
        self.manager.remove_device(999)

        self.assertEqual(self.manager.devices, before)


if __name__ == "__main__":
    unittest.main()

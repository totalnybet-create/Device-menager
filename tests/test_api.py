import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

from device_manager.api import create_app


class ApiTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        db_path = Path(self.tmp.name) / "api.db"
        self.client_context = TestClient(
            create_app(f"sqlite:///{db_path}", create_schema=True)
        )
        self.client = self.client_context.__enter__()

    def tearDown(self):
        self.client_context.__exit__(None, None, None)
        self.tmp.cleanup()

    def test_health_and_ready(self):
        self.assertEqual(self.client.get("/health").json(), {"status": "ok"})
        self.assertEqual(self.client.get("/ready").json(), {"status": "ready"})

    def test_full_crud(self):
        created = self.client.post(
            "/devices",
            json={"name": "Phone A", "device_type": "Mobile", "status": "Online"},
        )
        self.assertEqual(created.status_code, 201)
        device_id = created.json()["id"]

        listed = self.client.get("/devices")
        self.assertEqual(listed.status_code, 200)
        self.assertEqual(len(listed.json()), 1)

        fetched = self.client.get(f"/devices/{device_id}")
        self.assertEqual(fetched.status_code, 200)
        self.assertEqual(fetched.json()["name"], "Phone A")

        patched = self.client.patch(
            f"/devices/{device_id}",
            json={"status": "Offline"},
        )
        self.assertEqual(patched.status_code, 200)
        self.assertEqual(patched.json()["status"], "Offline")

        deleted = self.client.delete(f"/devices/{device_id}")
        self.assertEqual(deleted.status_code, 204)
        self.assertEqual(self.client.get(f"/devices/{device_id}").status_code, 404)

    def test_validation_and_not_found_contract(self):
        invalid = self.client.post(
            "/devices",
            json={"name": " ", "device_type": "Mobile"},
        )
        self.assertEqual(invalid.status_code, 422)

        empty_patch = self.client.patch("/devices/1", json={})
        self.assertEqual(empty_patch.status_code, 422)

        missing_patch = self.client.patch(
            "/devices/999",
            json={"status": "Online"},
        )
        self.assertEqual(missing_patch.status_code, 404)

        missing_delete = self.client.delete("/devices/999")
        self.assertEqual(missing_delete.status_code, 404)


if __name__ == "__main__":
    unittest.main()

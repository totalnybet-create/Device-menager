import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

from fastapi.testclient import TestClient

from device_manager.api import create_app


def as_utc(value: str) -> datetime:
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


class AgentApiTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        db_path = Path(self.tmp.name) / "agent-api.db"
        self.client_context = TestClient(
            create_app(f"sqlite:///{db_path}", create_schema=True)
        )
        self.client = self.client_context.__enter__()

    def tearDown(self):
        self.client_context.__exit__(None, None, None)
        self.tmp.cleanup()

    def test_registration_is_idempotent_and_heartbeat_updates_status(self):
        agent_id = str(uuid4())
        observed = datetime.now(timezone.utc)
        registration = {
            "agent_id": agent_id,
            "name": "phone-a",
            "device_type": "Mobile",
            "status": "Online",
            "hostname": "phone-a",
            "platform": "Android",
            "agent_version": "0.1.0",
            "observed_at": observed.isoformat(),
        }

        first = self.client.post("/agents/register", json=registration)
        self.assertEqual(first.status_code, 200)
        first_device = first.json()

        registration["status"] = "Offline"
        registration["observed_at"] = (observed + timedelta(seconds=1)).isoformat()
        second = self.client.post("/agents/register", json=registration)
        self.assertEqual(second.status_code, 200)
        second_device = second.json()

        self.assertEqual(first_device["id"], second_device["id"])
        self.assertEqual(second_device["agent_id"], agent_id)
        self.assertEqual(second_device["status"], "Offline")

        heartbeat_time = observed + timedelta(seconds=2)
        heartbeat = self.client.post(
            "/agents/heartbeat",
            json={
                "agent_id": agent_id,
                "status": "Online",
                "observed_at": heartbeat_time.isoformat(),
            },
        )
        self.assertEqual(heartbeat.status_code, 200)
        self.assertEqual(heartbeat.json()["status"], "Online")
        self.assertEqual(as_utc(heartbeat.json()["last_seen_at"]), heartbeat_time)

    def test_older_heartbeat_does_not_move_last_seen_backwards(self):
        agent_id = str(uuid4())
        now = datetime.now(timezone.utc)
        registration = {
            "agent_id": agent_id,
            "name": "tablet",
            "device_type": "Mobile",
            "status": "Online",
            "hostname": "tablet",
            "platform": "Android",
            "agent_version": "0.1.0",
            "observed_at": now.isoformat(),
        }
        self.client.post("/agents/register", json=registration)

        older = now - timedelta(minutes=5)
        response = self.client.post(
            "/agents/heartbeat",
            json={
                "agent_id": agent_id,
                "status": "Online",
                "observed_at": older.isoformat(),
            },
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(as_utc(response.json()["last_seen_at"]), now)

    def test_unknown_agent_heartbeat_returns_404(self):
        response = self.client.post(
            "/agents/heartbeat",
            json={
                "agent_id": str(uuid4()),
                "status": "Online",
                "observed_at": datetime.now(timezone.utc).isoformat(),
            },
        )
        self.assertEqual(response.status_code, 404)
        self.assertIn("not registered", response.json()["detail"])


if __name__ == "__main__":
    unittest.main()

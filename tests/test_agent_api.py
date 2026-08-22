import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

from fastapi.testclient import TestClient

from device_manager.api import create_app


ENROLLMENT = "test-enrollment-secret"


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
            create_app(
                f"sqlite:///{db_path}",
                create_schema=True,
                enrollment_token=ENROLLMENT,
            )
        )
        self.client = self.client_context.__enter__()
        self.enrollment_headers = {"X-Agent-Enrollment-Token": ENROLLMENT}

    def tearDown(self):
        self.client_context.__exit__(None, None, None)
        self.tmp.cleanup()

    def _registration(self, agent_id: str, observed: datetime) -> dict:
        return {
            "agent_id": agent_id,
            "name": "phone-a",
            "device_type": "Mobile",
            "status": "Online",
            "hostname": "phone-a",
            "platform": "Android",
            "agent_version": "0.2.0",
            "observed_at": observed.isoformat(),
        }

    def test_registration_requires_enrollment_secret(self):
        payload = self._registration(str(uuid4()), datetime.now(timezone.utc))
        self.assertEqual(self.client.post("/agents/register", json=payload).status_code, 401)
        self.assertEqual(
            self.client.post(
                "/agents/register",
                headers={"X-Agent-Enrollment-Token": "wrong"},
                json=payload,
            ).status_code,
            401,
        )

    def test_registration_rotates_machine_token_and_heartbeat_authenticates(self):
        agent_id = str(uuid4())
        observed = datetime.now(timezone.utc)
        registration = self._registration(agent_id, observed)

        first = self.client.post(
            "/agents/register",
            headers=self.enrollment_headers,
            json=registration,
        )
        self.assertEqual(first.status_code, 200)
        first_body = first.json()
        first_device = first_body["device"]
        first_token = first_body["agent_token"]

        registration["status"] = "Offline"
        registration["observed_at"] = (observed + timedelta(seconds=1)).isoformat()
        second = self.client.post(
            "/agents/register",
            headers=self.enrollment_headers,
            json=registration,
        )
        self.assertEqual(second.status_code, 200)
        second_body = second.json()
        second_device = second_body["device"]
        second_token = second_body["agent_token"]

        self.assertEqual(first_device["id"], second_device["id"])
        self.assertNotEqual(first_token, second_token)
        self.assertEqual(second_device["agent_id"], agent_id)

        heartbeat_payload = {
            "agent_id": agent_id,
            "status": "Online",
            "observed_at": (observed + timedelta(seconds=2)).isoformat(),
        }
        old_token_response = self.client.post(
            "/agents/heartbeat",
            headers={"Authorization": f"Bearer {first_token}"},
            json=heartbeat_payload,
        )
        self.assertEqual(old_token_response.status_code, 401)

        heartbeat = self.client.post(
            "/agents/heartbeat",
            headers={"Authorization": f"Bearer {second_token}"},
            json=heartbeat_payload,
        )
        self.assertEqual(heartbeat.status_code, 200)
        self.assertEqual(heartbeat.json()["status"], "Online")

    def test_older_heartbeat_does_not_move_last_seen_backwards(self):
        agent_id = str(uuid4())
        now = datetime.now(timezone.utc)
        registered = self.client.post(
            "/agents/register",
            headers=self.enrollment_headers,
            json=self._registration(agent_id, now),
        ).json()
        token = registered["agent_token"]

        older = now - timedelta(minutes=5)
        response = self.client.post(
            "/agents/heartbeat",
            headers={"Authorization": f"Bearer {token}"},
            json={
                "agent_id": agent_id,
                "status": "Online",
                "observed_at": older.isoformat(),
            },
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(as_utc(response.json()["last_seen_at"]), now)

    def test_agent_token_cannot_impersonate_different_agent(self):
        first_id = str(uuid4())
        second_id = str(uuid4())
        now = datetime.now(timezone.utc)
        first = self.client.post(
            "/agents/register",
            headers=self.enrollment_headers,
            json=self._registration(first_id, now),
        ).json()
        self.client.post(
            "/agents/register",
            headers=self.enrollment_headers,
            json=self._registration(second_id, now),
        )

        response = self.client.post(
            "/agents/heartbeat",
            headers={"Authorization": f"Bearer {first['agent_token']}"},
            json={
                "agent_id": second_id,
                "status": "Online",
                "observed_at": now.isoformat(),
            },
        )
        self.assertEqual(response.status_code, 403)


if __name__ == "__main__":
    unittest.main()

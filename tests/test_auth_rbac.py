import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient
from sqlalchemy import select

from device_manager.api import create_app
from device_manager.auth import AuthRepository, AuthService, hash_token
from device_manager.models import AuditEvent, UserPrincipal


class AuthRbacTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        db_path = Path(self.tmp.name) / "auth.db"
        self.client_context = TestClient(
            create_app(
                f"sqlite:///{db_path}",
                create_schema=True,
                enrollment_token="enroll-test",
            )
        )
        self.client = self.client_context.__enter__()
        self.tokens = {}
        with self.client.app.state.session_factory() as session:
            service = AuthService(AuthRepository(session))
            for role in ("admin", "operator", "read-only"):
                _principal, issued = service.create_user(name=role, role=role)
                self.tokens[role] = issued.token

    def tearDown(self):
        self.client_context.__exit__(None, None, None)
        self.tmp.cleanup()

    def headers(self, role: str) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.tokens[role]}"}

    def test_raw_user_token_is_never_stored(self):
        raw_token = self.tokens["admin"]
        with self.client.app.state.session_factory() as session:
            principal = session.scalar(
                select(UserPrincipal).where(UserPrincipal.name == "admin")
            )
            self.assertIsNotNone(principal)
            self.assertNotEqual(principal.token_hash, raw_token)
            self.assertEqual(principal.token_hash, hash_token(raw_token))
            self.assertEqual(len(principal.token_hash), 64)

    def test_read_only_can_read_but_cannot_mutate(self):
        self.assertEqual(
            self.client.get("/devices", headers=self.headers("read-only")).status_code,
            200,
        )
        denied = self.client.post(
            "/devices",
            headers=self.headers("read-only"),
            json={"name": "Denied", "device_type": "Mobile", "status": "Online"},
        )
        self.assertEqual(denied.status_code, 403)

    def test_operator_can_create_update_but_not_delete(self):
        created = self.client.post(
            "/devices",
            headers=self.headers("operator"),
            json={"name": "Operator Phone", "device_type": "Mobile", "status": "Online"},
        )
        self.assertEqual(created.status_code, 201)
        device_id = created.json()["id"]

        updated = self.client.patch(
            f"/devices/{device_id}",
            headers=self.headers("operator"),
            json={"status": "Offline"},
        )
        self.assertEqual(updated.status_code, 200)
        self.assertEqual(updated.json()["status"], "Offline")

        self.assertEqual(
            self.client.delete(
                f"/devices/{device_id}",
                headers=self.headers("operator"),
            ).status_code,
            403,
        )

        self.assertEqual(
            self.client.delete(
                f"/devices/{device_id}",
                headers=self.headers("admin"),
            ).status_code,
            204,
        )

    def test_user_token_is_not_valid_agent_credential(self):
        response = self.client.post(
            "/agents/heartbeat",
            headers=self.headers("admin"),
            json={
                "agent_id": "1f5851f6-cf65-443d-a1a6-c1da96e3fc56",
                "status": "Online",
                "observed_at": "2026-08-22T18:00:00+00:00",
            },
        )
        self.assertEqual(response.status_code, 401)

    def test_permission_denials_and_mutations_are_audited(self):
        self.client.post(
            "/devices",
            headers=self.headers("read-only"),
            json={"name": "Denied", "device_type": "Mobile", "status": "Online"},
        )
        self.client.post(
            "/devices",
            headers=self.headers("operator"),
            json={"name": "Audited", "device_type": "Mobile", "status": "Online"},
        )

        with self.client.app.state.session_factory() as session:
            events = list(session.scalars(select(AuditEvent).order_by(AuditEvent.id)))
        self.assertTrue(any(event.action == "devices:create" and event.outcome == "denied" for event in events))
        self.assertTrue(any(event.action == "devices:create" and event.outcome == "success" for event in events))


if __name__ == "__main__":
    unittest.main()

import tempfile
import unittest
from pathlib import Path

import httpx

from device_manager.agent import AgentClient, load_or_create_agent_id


class AgentClientTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.identity_path = root / "agent-id"
        self.token_path = root / "agent-token"
        self.queue_path = root / "pending-heartbeat.json"

    def tearDown(self):
        self.tmp.cleanup()

    def test_identity_is_stable(self):
        first = load_or_create_agent_id(self.identity_path)
        second = load_or_create_agent_id(self.identity_path)
        self.assertEqual(first, second)

    def test_registration_requires_enrollment_and_persists_machine_token(self):
        calls = []

        def post(url, *, json, headers, timeout):
            calls.append((url, headers))
            return httpx.Response(
                200,
                json={"device": {"id": 1}, "agent_token": "machine-token-value"},
                request=httpx.Request("POST", url),
            )

        client = AgentClient(
            "http://127.0.0.1:8000",
            identity_path=self.identity_path,
            token_path=self.token_path,
            queue_path=self.queue_path,
            enrollment_token="enroll-secret",
            post=post,
        )
        result = client.register()

        self.assertEqual(result["agent_token"], "machine-token-value")
        self.assertEqual(self.token_path.read_text(encoding="utf-8"), "machine-token-value")
        self.assertEqual(calls[0][1]["X-Agent-Enrollment-Token"], "enroll-secret")

    def test_retry_uses_exponential_backoff(self):
        attempts = []
        delays = []

        def post(url, *, json, headers, timeout):
            attempts.append((url, json, headers, timeout))
            if len(attempts) < 3:
                request = httpx.Request("POST", url)
                raise httpx.ConnectError("offline", request=request)
            return httpx.Response(
                200,
                json={"device": {"id": 1}, "agent_token": "machine-token"},
                request=httpx.Request("POST", url),
            )

        client = AgentClient(
            "http://127.0.0.1:8000",
            identity_path=self.identity_path,
            token_path=self.token_path,
            queue_path=self.queue_path,
            enrollment_token="enroll-secret",
            max_retries=3,
            sleep=delays.append,
            post=post,
        )
        client.register()

        self.assertEqual(len(attempts), 3)
        self.assertEqual(delays, [1, 2])

    def test_failed_heartbeat_is_queued(self):
        self.token_path.write_text("machine-token", encoding="utf-8")

        def post(url, *, json, headers, timeout):
            request = httpx.Request("POST", url)
            raise httpx.ConnectError("offline", request=request)

        client = AgentClient(
            "http://127.0.0.1:8000",
            identity_path=self.identity_path,
            token_path=self.token_path,
            queue_path=self.queue_path,
            max_retries=1,
            sleep=lambda _seconds: None,
            post=post,
        )

        with self.assertRaises(httpx.ConnectError):
            client.heartbeat()

        self.assertTrue(self.queue_path.exists())
        self.assertIn(client.agent_id, self.queue_path.read_text(encoding="utf-8"))

    def test_401_heartbeat_reenrolls_when_enrollment_secret_exists(self):
        self.token_path.write_text("old-machine-token", encoding="utf-8")
        calls = []

        def post(url, *, json, headers, timeout):
            calls.append((url, headers.copy()))
            request = httpx.Request("POST", url)
            if len(calls) == 1:
                return httpx.Response(401, json={"detail": "invalid"}, request=request)
            if url.endswith("/agents/register"):
                return httpx.Response(
                    200,
                    json={"device": {"id": 1}, "agent_token": "new-machine-token"},
                    request=request,
                )
            return httpx.Response(200, json={"heartbeat": True}, request=request)

        client = AgentClient(
            "http://127.0.0.1:8000",
            identity_path=self.identity_path,
            token_path=self.token_path,
            queue_path=self.queue_path,
            enrollment_token="enroll-secret",
            max_retries=1,
            sleep=lambda _seconds: None,
            post=post,
        )
        result = client.heartbeat()

        self.assertEqual(result, {"heartbeat": True})
        self.assertEqual(self.token_path.read_text(encoding="utf-8"), "new-machine-token")
        self.assertEqual(
            [call[0] for call in calls],
            [
                "http://127.0.0.1:8000/agents/heartbeat",
                "http://127.0.0.1:8000/agents/register",
                "http://127.0.0.1:8000/agents/heartbeat",
            ],
        )
        self.assertEqual(calls[2][1]["Authorization"], "Bearer new-machine-token")


if __name__ == "__main__":
    unittest.main()

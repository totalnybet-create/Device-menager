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
        self.queue_path = root / "pending-heartbeat.json"

    def tearDown(self):
        self.tmp.cleanup()

    def test_identity_is_stable(self):
        first = load_or_create_agent_id(self.identity_path)
        second = load_or_create_agent_id(self.identity_path)
        self.assertEqual(first, second)

    def test_retry_uses_exponential_backoff(self):
        attempts = []
        delays = []

        def post(url, *, json, timeout):
            attempts.append((url, json, timeout))
            if len(attempts) < 3:
                request = httpx.Request("POST", url)
                raise httpx.ConnectError("offline", request=request)
            return httpx.Response(
                200,
                json={"ok": True},
                request=httpx.Request("POST", url),
            )

        client = AgentClient(
            "http://127.0.0.1:8000",
            identity_path=self.identity_path,
            queue_path=self.queue_path,
            max_retries=3,
            sleep=delays.append,
            post=post,
        )
        result = client.register()

        self.assertEqual(result, {"ok": True})
        self.assertEqual(len(attempts), 3)
        self.assertEqual(delays, [1, 2])

    def test_failed_heartbeat_is_queued(self):
        def post(url, *, json, timeout):
            request = httpx.Request("POST", url)
            raise httpx.ConnectError("offline", request=request)

        client = AgentClient(
            "http://127.0.0.1:8000",
            identity_path=self.identity_path,
            queue_path=self.queue_path,
            max_retries=1,
            sleep=lambda _seconds: None,
            post=post,
        )

        with self.assertRaises(httpx.ConnectError):
            client.heartbeat()

        self.assertTrue(self.queue_path.exists())
        self.assertIn(client.agent_id, self.queue_path.read_text(encoding="utf-8"))

    def test_404_heartbeat_self_registers_then_retries(self):
        calls = []

        def post(url, *, json, timeout):
            calls.append(url)
            request = httpx.Request("POST", url)
            if len(calls) == 1:
                return httpx.Response(404, json={"detail": "missing"}, request=request)
            if url.endswith("/agents/register"):
                return httpx.Response(200, json={"registered": True}, request=request)
            return httpx.Response(200, json={"heartbeat": True}, request=request)

        client = AgentClient(
            "http://127.0.0.1:8000",
            identity_path=self.identity_path,
            queue_path=self.queue_path,
            max_retries=1,
            sleep=lambda _seconds: None,
            post=post,
        )
        result = client.heartbeat()

        self.assertEqual(result, {"heartbeat": True})
        self.assertEqual(
            calls,
            [
                "http://127.0.0.1:8000/agents/heartbeat",
                "http://127.0.0.1:8000/agents/register",
                "http://127.0.0.1:8000/agents/heartbeat",
            ],
        )


if __name__ == "__main__":
    unittest.main()

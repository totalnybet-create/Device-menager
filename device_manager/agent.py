from __future__ import annotations

import argparse
import json
import os
import platform
import socket
import time
from collections.abc import Callable
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

import httpx

AGENT_VERSION = "0.2.0"


def utcnow_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def default_state_dir() -> Path:
    configured = os.getenv("DEVICE_MANAGER_AGENT_STATE_DIR")
    if configured:
        return Path(configured)
    return Path.home() / ".device-manager"


def _atomic_write_secret(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(value, encoding="utf-8")
    try:
        os.chmod(temporary, 0o600)
    except OSError:
        pass
    temporary.replace(path)


def load_or_create_agent_id(path: Path) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        value = path.read_text(encoding="utf-8").strip()
        if value:
            return value

    value = str(uuid4())
    _atomic_write_secret(path, value)
    return value


def detect_device_type() -> str:
    if os.getenv("ANDROID_ROOT") or os.getenv("ANDROID_DATA"):
        return "Mobile"
    return "Computer"


def collect_registration(agent_id: str) -> dict[str, str]:
    hostname = socket.gethostname() or "unknown"
    platform_name = f"{platform.system()} {platform.release()}".strip()
    return {
        "agent_id": agent_id,
        "name": hostname,
        "device_type": detect_device_type(),
        "status": "Online",
        "hostname": hostname,
        "platform": platform_name or "unknown",
        "agent_version": AGENT_VERSION,
        "observed_at": utcnow_iso(),
    }


def collect_heartbeat(agent_id: str) -> dict[str, str]:
    return {
        "agent_id": agent_id,
        "status": "Online",
        "observed_at": utcnow_iso(),
    }


class AgentClient:
    def __init__(
        self,
        base_url: str,
        *,
        identity_path: Path | None = None,
        token_path: Path | None = None,
        queue_path: Path | None = None,
        enrollment_token: str | None = None,
        timeout: float = 5.0,
        max_retries: int = 3,
        sleep: Callable[[float], None] = time.sleep,
        post: Callable[..., httpx.Response] = httpx.post,
    ):
        if max_retries < 1:
            raise ValueError("max_retries must be at least 1")

        state_dir = default_state_dir()
        self.base_url = base_url.rstrip("/")
        self.identity_path = identity_path or state_dir / "agent-id"
        self.token_path = token_path or state_dir / "agent-token"
        self.queue_path = queue_path or state_dir / "pending-heartbeat.json"
        self.enrollment_token = enrollment_token or os.getenv(
            "DEVICE_MANAGER_AGENT_ENROLLMENT_TOKEN"
        )
        self.timeout = timeout
        self.max_retries = max_retries
        self.sleep = sleep
        self.post = post
        self.agent_id = load_or_create_agent_id(self.identity_path)

    def _send(
        self,
        path: str,
        payload: dict[str, str],
        *,
        headers: dict[str, str] | None = None,
    ) -> dict:
        last_error: Exception | None = None

        for attempt in range(self.max_retries):
            try:
                response = self.post(
                    f"{self.base_url}{path}",
                    json=payload,
                    headers=headers or {},
                    timeout=self.timeout,
                )
                response.raise_for_status()
                return response.json()
            except httpx.HTTPStatusError as exc:
                last_error = exc
                if exc.response.status_code < 500:
                    raise
            except httpx.RequestError as exc:
                last_error = exc

            if attempt + 1 < self.max_retries:
                self.sleep(2**attempt)

        assert last_error is not None
        raise last_error

    def _load_agent_token(self) -> str | None:
        if not self.token_path.exists():
            return None
        value = self.token_path.read_text(encoding="utf-8").strip()
        return value or None

    def register(self) -> dict:
        if not self.enrollment_token:
            raise RuntimeError("agent enrollment token is required for registration")

        result = self._send(
            "/agents/register",
            collect_registration(self.agent_id),
            headers={"X-Agent-Enrollment-Token": self.enrollment_token},
        )
        token = result.get("agent_token")
        if not isinstance(token, str) or not token:
            raise RuntimeError("registration response did not contain agent token")
        _atomic_write_secret(self.token_path, token)
        return result

    def _queue_heartbeat(self, payload: dict[str, str]) -> None:
        self.queue_path.parent.mkdir(parents=True, exist_ok=True)
        self.queue_path.write_text(
            json.dumps(payload, ensure_ascii=False),
            encoding="utf-8",
        )

    def _load_pending_heartbeat(self) -> dict[str, str] | None:
        if not self.queue_path.exists():
            return None
        try:
            return json.loads(self.queue_path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            return None

    def _heartbeat_headers(self) -> dict[str, str]:
        token = self._load_agent_token()
        if token is None:
            self.register()
            token = self._load_agent_token()
        if token is None:
            raise RuntimeError("agent token is unavailable after registration")
        return {"Authorization": f"Bearer {token}"}

    def _send_heartbeat(self, payload: dict[str, str]) -> dict:
        try:
            return self._send(
                "/agents/heartbeat",
                payload,
                headers=self._heartbeat_headers(),
            )
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code != 401 or not self.enrollment_token:
                raise
            self.register()
            return self._send(
                "/agents/heartbeat",
                payload,
                headers=self._heartbeat_headers(),
            )

    def flush_pending(self) -> dict | None:
        payload = self._load_pending_heartbeat()
        if payload is None:
            return None

        result = self._send_heartbeat(payload)
        self.queue_path.unlink(missing_ok=True)
        return result

    def heartbeat(self) -> dict:
        if self._load_pending_heartbeat() is not None:
            self.flush_pending()

        payload = collect_heartbeat(self.agent_id)
        try:
            return self._send_heartbeat(payload)
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code >= 500:
                self._queue_heartbeat(payload)
            raise
        except httpx.RequestError:
            self._queue_heartbeat(payload)
            raise


def main() -> None:
    parser = argparse.ArgumentParser(description="Device Manager telemetry agent")
    parser.add_argument("command", choices=["register", "heartbeat"])
    parser.add_argument(
        "--api",
        default=os.getenv("DEVICE_MANAGER_API_URL", "http://127.0.0.1:8000"),
    )
    args = parser.parse_args()

    client = AgentClient(args.api)
    result = client.register() if args.command == "register" else client.heartbeat()
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()

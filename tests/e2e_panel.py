from __future__ import annotations

import os
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

from playwright.sync_api import expect, sync_playwright

from device_manager.auth import AuthRepository, AuthService
from device_manager.database import Base, create_session_factory, make_engine


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def wait_for_health(url: str, process: subprocess.Popen[str], timeout: float = 20.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            output = process.stdout.read() if process.stdout else ""
            raise RuntimeError(f"server exited early with code {process.returncode}\n{output}")
        try:
            with urllib.request.urlopen(f"{url}/health", timeout=1.0) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, TimeoutError):
            time.sleep(0.2)
    raise TimeoutError("server did not become healthy")


def seed_users(database_url: str) -> dict[str, str]:
    engine = make_engine(database_url)
    Base.metadata.create_all(engine)
    session_factory = create_session_factory(engine)
    tokens: dict[str, str] = {}
    try:
        with session_factory() as session:
            service = AuthService(AuthRepository(session))
            for role in ("operator", "read-only", "admin"):
                _principal, issued = service.create_user(name=f"e2e-{role}", role=role)
                tokens[role] = issued.token
    finally:
        engine.dispose()
    return tokens


def login(page, token: str, role: str) -> None:
    page.locator("#tokenInput").fill(token)
    page.locator('#loginForm button[type="submit"]').click()
    expect(page.locator("#dashboard")).to_be_visible()
    expect(page.locator("#identityRole")).to_have_text(role)


def assert_no_horizontal_overflow(page) -> None:
    overflow = page.evaluate(
        "() => document.documentElement.scrollWidth - window.innerWidth"
    )
    if overflow > 1:
        raise AssertionError(f"horizontal overflow detected: {overflow}px")


def main() -> None:
    artifacts = Path(os.getenv("E2E_ARTIFACT_DIR", "artifacts"))
    artifacts.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory() as tmp:
        database_url = f"sqlite:///{Path(tmp) / 'e2e.db'}"
        tokens = seed_users(database_url)
        port = free_port()
        base_url = f"http://127.0.0.1:{port}"

        env = os.environ.copy()
        env["DATABASE_URL"] = database_url
        env["DEVICE_MANAGER_AGENT_ENROLLMENT_TOKEN"] = "e2e-enrollment"
        process = subprocess.Popen(
            [
                sys.executable,
                "-m",
                "uvicorn",
                "device_manager.api:app",
                "--host",
                "127.0.0.1",
                "--port",
                str(port),
            ],
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )

        try:
            wait_for_health(base_url, process)

            with sync_playwright() as playwright:
                browser = playwright.chromium.launch()
                page = browser.new_page(viewport={"width": 390, "height": 844})
                page.goto(f"{base_url}/panel", wait_until="networkidle")

                expect(page.locator("#loginCard")).to_be_visible()
                expect(page.locator("#dashboard")).to_be_hidden()

                page.locator("#tokenInput").fill("invalid-token")
                page.locator('#loginForm button[type="submit"]').click()
                expect(page.locator("#toast")).to_contain_text("Logowanie nieudane")
                expect(page.locator("#dashboard")).to_be_hidden()

                login(page, tokens["operator"], "operator")
                expect(page.locator("#addBtn")).to_be_visible()
                expect(page.locator("#statusLine")).to_contain_text("0 z 0")

                page.locator("#addBtn").click()
                expect(page.locator("#deviceDialog")).to_be_visible()
                expect(page.locator("#deviceName")).to_be_focused()
                page.locator("#deviceName").fill("Telefon testowy")
                page.locator("#deviceType").fill("Mobile")
                page.locator("#deviceStatus").fill("Online")

                page.evaluate(
                    """() => {
                        const form = document.getElementById('deviceForm');
                        form.requestSubmit();
                        form.requestSubmit();
                    }"""
                )
                expect(page.locator(".device-card")).to_have_count(1)
                expect(page.locator("#totalCount")).to_have_text("1")
                assert_no_horizontal_overflow(page)
                page.screenshot(path=str(artifacts / "panel-mobile.png"), full_page=True)

                page.locator("#searchInput").fill("brak-wyniku")
                expect(page.locator("#emptyState")).to_be_visible()
                page.locator("#searchInput").fill("")
                expect(page.locator(".device-card")).to_have_count(1)

                page.locator("#statusFilter").select_option("offline")
                expect(page.locator("#emptyState")).to_be_visible()
                page.locator("#statusFilter").select_option("")

                page.locator(".device-card .ghost").click()
                expect(page.locator("#deleteBtn")).to_be_hidden()
                page.locator("#deviceStatus").fill("Offline")
                page.locator('#deviceForm button[type="submit"]').click()
                expect(page.locator(".badge")).to_have_text("Offline")

                page.locator("#logoutBtn").click()
                expect(page.locator("#loginCard")).to_be_visible()
                expect(page.locator("#tokenInput")).to_be_focused()

                login(page, tokens["read-only"], "read-only")
                expect(page.locator("#addBtn")).to_be_hidden()
                expect(page.locator(".device-card")).to_have_count(1)
                expect(page.locator(".device-card .card-actions button")).to_have_count(0)
                expect(page.locator(".device-card .meta")).to_contain_text("Utworzono")

                page.locator("#logoutBtn").click()
                login(page, tokens["admin"], "admin")
                page.set_viewport_size({"width": 1440, "height": 900})
                assert_no_horizontal_overflow(page)
                page.screenshot(path=str(artifacts / "panel-desktop.png"), full_page=True)

                page.locator(".device-card .ghost").click()
                expect(page.locator("#deleteBtn")).to_be_visible()
                page.once("dialog", lambda prompt: prompt.accept())
                page.locator("#deleteBtn").click()
                expect(page.locator(".device-card")).to_have_count(0)
                expect(page.locator("#emptyState")).to_be_visible()

                browser.close()
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            if process.returncode not in (0, -15):
                output = process.stdout.read() if process.stdout else ""
                print(output, file=sys.stderr)


if __name__ == "__main__":
    main()

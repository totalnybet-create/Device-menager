import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

from device_manager.api import create_app
from device_manager.auth import AuthRepository, AuthService


class PanelTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        db_path = Path(self.tmp.name) / "panel.db"
        self.client_context = TestClient(
            create_app(f"sqlite:///{db_path}", create_schema=True, enrollment_token="test")
        )
        self.client = self.client_context.__enter__()
        with self.client.app.state.session_factory() as session:
            _principal, issued = AuthService(AuthRepository(session)).create_user(
                name="panel-operator",
                role="operator",
            )
        self.headers = {"Authorization": f"Bearer {issued.token}"}

    def tearDown(self):
        self.client_context.__exit__(None, None, None)
        self.tmp.cleanup()

    def test_panel_shell_has_csp_and_no_external_dependencies(self):
        response = self.client.get("/panel")
        self.assertEqual(response.status_code, 200)
        self.assertIn("default-src 'self'", response.headers["content-security-policy"])
        self.assertEqual(response.headers["x-frame-options"], "DENY")
        self.assertEqual(response.headers["x-content-type-options"], "nosniff")
        self.assertEqual(response.headers["referrer-policy"], "no-referrer")
        self.assertEqual(response.headers["cache-control"], "no-store")
        self.assertIn("camera=()", response.headers["permissions-policy"])
        self.assertEqual(response.headers["cross-origin-opener-policy"], "same-origin")
        self.assertEqual(response.headers["cross-origin-resource-policy"], "same-origin")
        html = response.text
        self.assertIn('id="loginForm"', html)
        self.assertIn('id="deviceGrid"', html)
        self.assertIn('aria-live="polite"', html)
        self.assertNotIn("https://", html)
        self.assertNotIn("http://", html)

    def test_panel_assets_are_served(self):
        css = self.client.get("/panel-assets/panel.css")
        js = self.client.get("/panel-assets/panel.js")
        self.assertEqual(css.status_code, 200)
        self.assertEqual(js.status_code, 200)
        self.assertIn("text/css", css.headers["content-type"])
        self.assertIn("javascript", js.headers["content-type"])
        self.assertIn("prefers-reduced-motion", css.text)
        self.assertIn("roleCan", js.text)
        self.assertIn("REQUEST_TIMEOUT_MS", js.text)
        self.assertNotIn("innerHTML", js.text)

    def test_me_requires_auth_and_returns_role_without_cache(self):
        self.assertEqual(self.client.get("/me").status_code, 401)
        response = self.client.get("/me", headers=self.headers)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers["cache-control"], "no-store")
        self.assertEqual(
            response.json(),
            {"id": response.json()["id"], "name": "panel-operator", "role": "operator"},
        )


if __name__ == "__main__":
    unittest.main()

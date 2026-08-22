from __future__ import annotations

import os
import secrets
from collections.abc import Callable, Generator
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response, status
from fastapi.responses import FileResponse, JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from fastapi.staticfiles import StaticFiles
from sqlalchemy import text
from sqlalchemy.orm import Session

from .agent_service import AgentNotRegisteredError, AgentService
from .auth import AuthRepository, AuthService
from .database import Base, create_session_factory, make_engine
from .models import UserPrincipal
from .repository import DeviceRepository
from .schemas import (
    AgentHeartbeat,
    AgentRegistration,
    AgentRegistrationResult,
    DeviceCreate,
    DeviceRead,
    DeviceUpdate,
    HealthRead,
    UserPrincipalRead,
)
from .security import AttemptLimiter
from .service import DeviceNotFoundError, DeviceService


PANEL_DIR = Path(__file__).with_name("panel")
PANEL_CSP = (
    "default-src 'self'; "
    "script-src 'self'; "
    "style-src 'self'; "
    "connect-src 'self'; "
    "img-src 'self' data:; "
    "object-src 'none'; "
    "base-uri 'none'; "
    "frame-ancestors 'none'; "
    "form-action 'self'"
)
SENSITIVE_NO_STORE_PATHS = frozenset(
    {
        "/panel",
        "/me",
        "/agents/register",
        "/agents/heartbeat",
    }
)


def create_app(
    database_url: str | None = None,
    *,
    create_schema: bool = False,
    enrollment_token: str | None = None,
) -> FastAPI:
    engine = make_engine(database_url)
    session_factory = create_session_factory(engine)
    configured_enrollment_token = enrollment_token or os.getenv(
        "DEVICE_MANAGER_AGENT_ENROLLMENT_TOKEN"
    )
    bearer = HTTPBearer(auto_error=False)
    auth_limiter = AttemptLimiter(max_failures=5, window_seconds=60.0)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if create_schema:
            Base.metadata.create_all(engine)
        app.state.engine = engine
        app.state.session_factory = session_factory
        app.state.enrollment_token = configured_enrollment_token
        app.state.auth_limiter = auth_limiter
        yield
        engine.dispose()

    app = FastAPI(
        title="Device Manager API",
        version="2.5.0",
        lifespan=lifespan,
    )
    app.mount("/panel-assets", StaticFiles(directory=str(PANEL_DIR)), name="panel-assets")

    @app.middleware("http")
    async def add_security_headers(request: Request, call_next):
        response = await call_next(request)
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("Referrer-Policy", "no-referrer")
        response.headers.setdefault("X-Frame-Options", "DENY")
        response.headers.setdefault(
            "Permissions-Policy",
            "camera=(), microphone=(), geolocation=()",
        )
        response.headers.setdefault("Cross-Origin-Opener-Policy", "same-origin")
        response.headers.setdefault("Cross-Origin-Resource-Policy", "same-origin")

        path = request.url.path
        if path in SENSITIVE_NO_STORE_PATHS or path == "/devices" or path.startswith("/devices/"):
            response.headers.setdefault("Cache-Control", "no-store")

        if request.url.scheme == "https":
            response.headers.setdefault(
                "Strict-Transport-Security",
                "max-age=31536000; includeSubDomains",
            )
        return response

    def get_session(request: Request) -> Generator[Session, None, None]:
        with request.app.state.session_factory() as session:
            yield session

    def get_service(session: Session = Depends(get_session)) -> DeviceService:
        return DeviceService(DeviceRepository(session))

    def get_agent_service(session: Session = Depends(get_session)) -> AgentService:
        return AgentService(DeviceRepository(session))

    def get_auth_service(session: Session = Depends(get_session)) -> AuthService:
        return AuthService(AuthRepository(session))

    def limiter_key(request: Request, scope: str) -> str:
        host = request.client.host if request.client else "unknown"
        return f"{scope}:{host}"

    def reject_if_limited(request: Request, scope: str) -> str:
        key = limiter_key(request, scope)
        if request.app.state.auth_limiter.blocked(key):
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail="too many failed authentication attempts",
                headers={"Retry-After": "60"},
            )
        return key

    def get_current_user(
        request: Request,
        credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
        auth: AuthService = Depends(get_auth_service),
    ) -> UserPrincipal:
        key = reject_if_limited(request, "user-auth")
        if credentials is None or credentials.scheme.lower() != "bearer":
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="authentication required",
                headers={"WWW-Authenticate": "Bearer"},
            )

        principal = auth.authenticate_user(credentials.credentials)
        if principal is None:
            request.app.state.auth_limiter.register_failure(key)
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="invalid credentials",
                headers={"WWW-Authenticate": "Bearer"},
            )
        request.app.state.auth_limiter.clear(key)
        return principal

    def require_permission(permission: str) -> Callable:
        def dependency(
            principal: UserPrincipal = Depends(get_current_user),
            auth: AuthService = Depends(get_auth_service),
        ) -> UserPrincipal:
            if not auth.has_permission(principal, permission):
                auth.audit_user_action(
                    principal,
                    action=permission,
                    outcome="denied",
                    detail="insufficient role permission",
                )
                raise HTTPException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    detail="insufficient permission",
                )
            return principal

        return dependency

    @app.exception_handler(DeviceNotFoundError)
    async def device_not_found_handler(
        _request: Request,
        exc: DeviceNotFoundError,
    ) -> JSONResponse:
        return JSONResponse(
            status_code=status.HTTP_404_NOT_FOUND,
            content={"detail": f"device {exc.device_id} not found"},
        )

    @app.exception_handler(AgentNotRegisteredError)
    async def agent_not_registered_handler(
        _request: Request,
        exc: AgentNotRegisteredError,
    ) -> JSONResponse:
        return JSONResponse(
            status_code=status.HTTP_404_NOT_FOUND,
            content={"detail": f"agent {exc.agent_id} not registered"},
        )

    @app.get("/panel", include_in_schema=False)
    def panel() -> FileResponse:
        return FileResponse(
            PANEL_DIR / "index.html",
            media_type="text/html",
            headers={"Content-Security-Policy": PANEL_CSP},
        )

    @app.get("/health", response_model=HealthRead)
    def health() -> HealthRead:
        return HealthRead(status="ok")

    @app.get("/ready", response_model=HealthRead)
    def ready(session: Session = Depends(get_session)) -> HealthRead:
        try:
            session.execute(text("SELECT 1"))
        except Exception as exc:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="database unavailable",
            ) from exc
        return HealthRead(status="ready")

    @app.get("/me", response_model=UserPrincipalRead)
    def me(principal: UserPrincipal = Depends(get_current_user)) -> UserPrincipal:
        return principal

    @app.get("/devices", response_model=list[DeviceRead])
    def list_devices(
        service: DeviceService = Depends(get_service),
        _principal: UserPrincipal = Depends(require_permission("devices:read")),
    ):
        return service.list_devices()

    @app.get("/devices/{device_id}", response_model=DeviceRead)
    def get_device(
        device_id: int,
        service: DeviceService = Depends(get_service),
        _principal: UserPrincipal = Depends(require_permission("devices:read")),
    ):
        return service.get_device(device_id)

    @app.post(
        "/devices",
        response_model=DeviceRead,
        status_code=status.HTTP_201_CREATED,
    )
    def create_device(
        payload: DeviceCreate,
        service: DeviceService = Depends(get_service),
        auth: AuthService = Depends(get_auth_service),
        principal: UserPrincipal = Depends(require_permission("devices:create")),
    ):
        device = service.create_device(payload)
        auth.audit_user_action(
            principal,
            action="devices:create",
            outcome="success",
            resource_type="device",
            resource_id=str(device.id),
        )
        return device

    @app.patch("/devices/{device_id}", response_model=DeviceRead)
    def update_device(
        device_id: int,
        payload: DeviceUpdate,
        service: DeviceService = Depends(get_service),
        auth: AuthService = Depends(get_auth_service),
        principal: UserPrincipal = Depends(require_permission("devices:update")),
    ):
        device = service.update_device(device_id, payload)
        auth.audit_user_action(
            principal,
            action="devices:update",
            outcome="success",
            resource_type="device",
            resource_id=str(device.id),
        )
        return device

    @app.delete("/devices/{device_id}", status_code=status.HTTP_204_NO_CONTENT)
    def delete_device(
        device_id: int,
        service: DeviceService = Depends(get_service),
        auth: AuthService = Depends(get_auth_service),
        principal: UserPrincipal = Depends(require_permission("devices:delete")),
    ) -> Response:
        service.delete_device(device_id)
        auth.audit_user_action(
            principal,
            action="devices:delete",
            outcome="success",
            resource_type="device",
            resource_id=str(device_id),
        )
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    @app.post("/agents/register", response_model=AgentRegistrationResult)
    def register_agent(
        payload: AgentRegistration,
        request: Request,
        x_agent_enrollment_token: str | None = Header(
            default=None,
            alias="X-Agent-Enrollment-Token",
        ),
        service: AgentService = Depends(get_agent_service),
        auth: AuthService = Depends(get_auth_service),
    ) -> AgentRegistrationResult:
        expected = request.app.state.enrollment_token
        if not expected:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="agent enrollment is not configured",
            )
        key = reject_if_limited(request, "agent-enrollment")
        if not x_agent_enrollment_token or not secrets.compare_digest(
            x_agent_enrollment_token,
            expected,
        ):
            request.app.state.auth_limiter.register_failure(key)
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="invalid enrollment credential",
            )
        request.app.state.auth_limiter.clear(key)

        device = service.register(payload)
        issued = auth.issue_agent_token(device.id)
        auth.audit_agent_action(
            device,
            action="agent:register",
            outcome="success",
        )
        return AgentRegistrationResult(device=DeviceRead.model_validate(device), agent_token=issued.token)

    @app.post("/agents/heartbeat", response_model=DeviceRead)
    def heartbeat_agent(
        payload: AgentHeartbeat,
        request: Request,
        credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
        service: AgentService = Depends(get_agent_service),
        auth: AuthService = Depends(get_auth_service),
    ):
        key = reject_if_limited(request, "agent-auth")
        if credentials is None or credentials.scheme.lower() != "bearer":
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="agent authentication required",
                headers={"WWW-Authenticate": "Bearer"},
            )

        authenticated = auth.authenticate_agent(credentials.credentials)
        if authenticated is None:
            request.app.state.auth_limiter.register_failure(key)
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="invalid agent credential",
                headers={"WWW-Authenticate": "Bearer"},
            )
        request.app.state.auth_limiter.clear(key)
        _credential, credential_device = authenticated
        if credential_device.agent_id != str(payload.agent_id):
            auth.audit_agent_action(
                credential_device,
                action="agent:heartbeat",
                outcome="denied",
                detail="agent id mismatch",
            )
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="credential does not belong to agent",
            )

        device = service.heartbeat(payload)
        auth.audit_agent_action(
            device,
            action="agent:heartbeat",
            outcome="success",
        )
        return device

    return app


app = create_app()

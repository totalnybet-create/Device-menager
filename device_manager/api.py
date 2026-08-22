from __future__ import annotations

from collections.abc import Generator
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Request, Response, status
from fastapi.responses import JSONResponse
from sqlalchemy import text
from sqlalchemy.orm import Session

from .agent_service import AgentNotRegisteredError, AgentService
from .database import Base, create_session_factory, make_engine
from .repository import DeviceRepository
from .schemas import (
    AgentHeartbeat,
    AgentRegistration,
    DeviceCreate,
    DeviceRead,
    DeviceUpdate,
    HealthRead,
)
from .service import DeviceNotFoundError, DeviceService


def create_app(database_url: str | None = None, *, create_schema: bool = False) -> FastAPI:
    engine = make_engine(database_url)
    session_factory = create_session_factory(engine)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if create_schema:
            Base.metadata.create_all(engine)
        app.state.engine = engine
        app.state.session_factory = session_factory
        yield
        engine.dispose()

    app = FastAPI(
        title="Device Manager API",
        version="2.3.0",
        lifespan=lifespan,
    )

    def get_session(request: Request) -> Generator[Session, None, None]:
        with request.app.state.session_factory() as session:
            yield session

    def get_service(session: Session = Depends(get_session)) -> DeviceService:
        return DeviceService(DeviceRepository(session))

    def get_agent_service(session: Session = Depends(get_session)) -> AgentService:
        return AgentService(DeviceRepository(session))

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

    @app.get("/devices", response_model=list[DeviceRead])
    def list_devices(service: DeviceService = Depends(get_service)):
        return service.list_devices()

    @app.get("/devices/{device_id}", response_model=DeviceRead)
    def get_device(device_id: int, service: DeviceService = Depends(get_service)):
        return service.get_device(device_id)

    @app.post(
        "/devices",
        response_model=DeviceRead,
        status_code=status.HTTP_201_CREATED,
    )
    def create_device(
        payload: DeviceCreate,
        service: DeviceService = Depends(get_service),
    ):
        return service.create_device(payload)

    @app.patch("/devices/{device_id}", response_model=DeviceRead)
    def update_device(
        device_id: int,
        payload: DeviceUpdate,
        service: DeviceService = Depends(get_service),
    ):
        return service.update_device(device_id, payload)

    @app.delete("/devices/{device_id}", status_code=status.HTTP_204_NO_CONTENT)
    def delete_device(
        device_id: int,
        service: DeviceService = Depends(get_service),
    ) -> Response:
        service.delete_device(device_id)
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    @app.post("/agents/register", response_model=DeviceRead)
    def register_agent(
        payload: AgentRegistration,
        service: AgentService = Depends(get_agent_service),
    ):
        return service.register(payload)

    @app.post("/agents/heartbeat", response_model=DeviceRead)
    def heartbeat_agent(
        payload: AgentHeartbeat,
        service: AgentService = Depends(get_agent_service),
    ):
        return service.heartbeat(payload)

    return app


app = create_app()

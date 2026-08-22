from __future__ import annotations

from .models import Device
from .repository import DeviceRepository
from .schemas import AgentHeartbeat, AgentRegistration


class AgentNotRegisteredError(LookupError):
    def __init__(self, agent_id: str):
        self.agent_id = agent_id
        super().__init__(f"Agent {agent_id} not registered")


class AgentService:
    def __init__(self, repository: DeviceRepository):
        self.repository = repository

    def register(self, payload: AgentRegistration) -> Device:
        agent_id = str(payload.agent_id)
        device = self.repository.get_by_agent_id(agent_id)
        values = {
            "name": payload.name,
            "device_type": payload.device_type,
            "status": payload.status,
            "hostname": payload.hostname,
            "platform": payload.platform,
            "agent_version": payload.agent_version,
            "last_seen_at": payload.observed_at,
        }

        if device is None:
            return self.repository.create_agent_device(agent_id=agent_id, **values)

        return self.repository.update_agent_registration(device, **values)

    def heartbeat(self, payload: AgentHeartbeat) -> Device:
        agent_id = str(payload.agent_id)
        device = self.repository.get_by_agent_id(agent_id)
        if device is None:
            raise AgentNotRegisteredError(agent_id)

        return self.repository.update_agent_heartbeat(
            device,
            status=payload.status,
            last_seen_at=payload.observed_at,
        )

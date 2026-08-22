from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class DeviceBase(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1, max_length=120)
    device_type: str = Field(min_length=1, max_length=64)
    status: str = Field(default="Aktywny", min_length=1, max_length=32)

    @field_validator("name", "device_type", "status")
    @classmethod
    def _strip_and_reject_blank(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("value must not be blank")
        return value


class DeviceCreate(DeviceBase):
    pass


class DeviceUpdate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str | None = Field(default=None, min_length=1, max_length=120)
    device_type: str | None = Field(default=None, min_length=1, max_length=64)
    status: str | None = Field(default=None, min_length=1, max_length=32)

    @field_validator("name", "device_type", "status")
    @classmethod
    def _strip_optional_values(cls, value: str | None) -> str | None:
        if value is None:
            return None
        value = value.strip()
        if not value:
            raise ValueError("value must not be blank")
        return value

    @model_validator(mode="after")
    def _require_change(self):
        if not self.model_fields_set:
            raise ValueError("at least one field must be provided")
        if all(getattr(self, field) is None for field in self.model_fields_set):
            raise ValueError("updated fields must not be null")
        return self


class DeviceRead(DeviceBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    agent_id: str | None = None
    hostname: str | None = None
    platform: str | None = None
    agent_version: str | None = None
    last_seen_at: datetime | None = None
    created_at: datetime
    updated_at: datetime


class HealthRead(BaseModel):
    status: str


class AgentRegistration(BaseModel):
    model_config = ConfigDict(extra="forbid")

    agent_id: UUID
    name: str = Field(min_length=1, max_length=120)
    device_type: str = Field(default="Computer", min_length=1, max_length=64)
    status: str = Field(default="Online", min_length=1, max_length=32)
    hostname: str = Field(min_length=1, max_length=255)
    platform: str = Field(min_length=1, max_length=64)
    agent_version: str = Field(min_length=1, max_length=32)
    observed_at: datetime

    @field_validator(
        "name",
        "device_type",
        "status",
        "hostname",
        "platform",
        "agent_version",
    )
    @classmethod
    def _strip_agent_values(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("value must not be blank")
        return value


class AgentRegistrationResult(BaseModel):
    device: DeviceRead
    agent_token: str = Field(min_length=32)


class AgentHeartbeat(BaseModel):
    model_config = ConfigDict(extra="forbid")

    agent_id: UUID
    status: str = Field(default="Online", min_length=1, max_length=32)
    observed_at: datetime

    @field_validator("status")
    @classmethod
    def _strip_heartbeat_status(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("value must not be blank")
        return value

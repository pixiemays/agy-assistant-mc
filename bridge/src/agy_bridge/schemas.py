from __future__ import annotations

from typing import Any, Literal
from pydantic import BaseModel, Field


class TargetData(BaseModel):
    kind: Literal["block", "entity"] = "block"
    id: str
    mod_id: str | None = None
    display_name: str | None = None
    pos: list[int] | None = None
    block_state: dict[str, str] = Field(default_factory=dict)
    block_entity_nbt: str | None = None
    entity_health: float | None = None
    entity_max_health: float | None = None
    extra_tags: list[str] = Field(default_factory=list)


class ItemData(BaseModel):
    id: str
    mod_id: str | None = None
    display_name: str | None = None
    count: int = 1
    source: str = "inventory"
    components: dict[str, Any] = Field(default_factory=dict)
    tooltip: list[str] = Field(default_factory=list)
    tags: list[str] = Field(default_factory=list)
    recipes: list[str] = Field(default_factory=list)


class ChatRequest(BaseModel):
    message: str = Field(..., min_length=1)
    target_data: TargetData | None = None
    item_data: ItemData | None = None
    screenshot_base64: str | None = None
    screenshot_mime: str = "image/jpeg"
    game_dir: str | None = None
    allow_game_dir_read: bool = False
    system_prompt_extra: str | None = None
    model: str | None = None
    api_key: str | None = None
    provider: str = "bridge"
    clarify_vague: bool = True


class HealthResponse(BaseModel):
    status: str = "ok"
    version: str
    agent_ready: bool
    pid: int


class ResetResponse(BaseModel):
    status: str = "reset"

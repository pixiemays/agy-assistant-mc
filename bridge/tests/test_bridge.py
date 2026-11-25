from __future__ import annotations

import base64
import json
from collections.abc import AsyncIterator
from typing import Any

from fastapi.testclient import TestClient
from google.antigravity import Image

from agy_bridge.main import create_app
from agy_bridge.prompt_builder import build_prompt_parts
from agy_bridge.schemas import ChatRequest, ItemData, TargetData


class FakeAgentService:
    def __init__(self) -> None:
        self.reset_called = False
        self.last_request: ChatRequest | None = None

    @property
    def is_ready(self) -> bool:
        return True

    async def stream_chat(self, req: ChatRequest) -> AsyncIterator[dict[str, Any]]:
        self.last_request = req
        yield {"type": "token", "content": "Hello "}
        yield {"type": "token", "content": "Minecraft!"}
        yield {"type": "done"}

    async def reset(self) -> None:
        self.reset_called = True

    async def close(self) -> None:
        pass


def test_health_and_reset() -> None:
    fake = FakeAgentService()
    app = create_app(agent_service=fake)  # type: ignore[arg-type]
    with TestClient(app) as client:
        resp = client.get("/health")
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "ok"
        assert body["agent_ready"] is True

        reset_resp = client.post("/reset")
        assert reset_resp.status_code == 200
        assert fake.reset_called is True


def test_chat_sse_stream() -> None:
    fake = FakeAgentService()
    app = create_app(agent_service=fake)  # type: ignore[arg-type]
    with TestClient(app) as client:
        resp = client.post(
            "/chat",
            json={
                "message": "What is this beehive?",
                "target_data": {
                    "kind": "block",
                    "id": "productivebees:advanced_oak_beehive",
                    "mod_id": "productivebees",
                    "block_state": {"facing": "north"},
                },
            },
        )
        assert resp.status_code == 200
        events = [
            json.loads(line.removeprefix("data: "))
            for line in resp.text.splitlines()
            if line.startswith("data: ")
        ]
        assert events == [
            {"type": "token", "content": "Hello "},
            {"type": "token", "content": "Minecraft!"},
            {"type": "done"},
        ]
        assert fake.last_request is not None
        assert fake.last_request.target_data is not None
        assert fake.last_request.target_data.id == "productivebees:advanced_oak_beehive"


def test_prompt_builder_multimodal() -> None:
    tiny_jpg = base64.b64encode(b"\xff\xd8\xff\xd9").decode("ascii")
    req = ChatRequest(
        message="Explain this recipe",
        target_data=TargetData(kind="entity", id="minecraft:bee", entity_health=10.0, entity_max_health=10.0),
        item_data=ItemData(id="minecraft:honeycomb", count=4, source="jei", tooltip=["Honeycomb"]),
        screenshot_base64=tiny_jpg,
    )
    parts = build_prompt_parts(req)
    assert isinstance(parts, list)
    assert len(parts) == 2
    assert "minecraft:bee" in parts[0]
    assert "minecraft:honeycomb" in parts[0]
    assert isinstance(parts[1], Image)
    assert parts[1].mime_type == "image/jpeg"

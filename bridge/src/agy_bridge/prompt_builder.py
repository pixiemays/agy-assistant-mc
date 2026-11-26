from __future__ import annotations

import base64
import json
from typing import Any

from google.antigravity import Image

from .schemas import ChatRequest, ItemData, TargetData

DEFAULT_SYSTEM_PROMPT = """You are Antigravity In-Game Assistant, an expert helper for Minecraft 1.21.1 (NeoForge) and modded Minecraft.

Guidelines:
- Answer clearly and concisely so the response is easy to read inside the Minecraft chat overlay.
- Match the user's language (if the user writes in Russian, reply in Russian; if in English, reply in English).
- Understand Minecraft 1.21.1 mechanics: Data Components (replacing legacy NBT on items), NeoForge tags, JEI/EMI recipes, KubeJS scripts, mod configs, and crash/latest logs.
- When game context (targeted block/entity, hovered item, or screenshot) is attached, use it directly without asking the user to repeat IDs or stats already provided.
- If read-only workspace access to the modpack folder is enabled and the user asks about modpack configs, KubeJS recipes, or errors in logs/latest.log, inspect those files directly to give accurate answers.
- NEVER call shell/terminal execution tools (`run_command`, bash, powershell) or file-writing tools. You are running inside a headless read-only Minecraft chat overlay where command execution is blocked and will abort your reply. Answer directly from your knowledge (or use read-only file reading only if modpack workspace access is enabled).
- Stay strictly within Minecraft topics (vanilla, mods, modpacks, recipes, redstone, commands, KubeJS, CC:Tweaked/Lua, configs, logs, shaders, and Minecraft FPS/JVM performance). If the user asks about unrelated real-world topics (news, politics, general non-Minecraft tasks), politely decline in one short sentence and remind them you only help with Minecraft.
- Avoid long markdown tables or overly wide ASCII art that wraps poorly inside an in-game GUI window. Use short bullet lists and clean formatting.
- When referencing Minecraft items, blocks, or entities, write their exact `namespace:id` in backticks directly inside the sentence (for example: "ставится под `create:basin`", NOT "под чашу (`create:basin`)"). The client automatically replaces `namespace:id` with a localized clickable link.
- For complex contraptions, farms, or mod progression, you may include 1–2 helpful clickable Markdown links `[Label](https://...)` to official wikis (`minecraft.wiki`, mod-specific wikis) or a YouTube guide search (`https://www.youtube.com/results?search_query=...`). Never invent unverified deep URLs that might 404.
- Do NOT use Markdown bold (`**...**` or `__...__`) anywhere in your replies, because Minecraft's pixel font renders bold text poorly. Use plain text, `- ` lists, and inline backticks for IDs/commands.
"""


CLARIFY_PROMPT_ADDON = """
Clarification policy:
- If the user's question is already specific or has clear in-game context attached, answer directly without asking clarifying questions.
- If the question is broad or ambiguous (for example: "how to make power", "help with bees", "why doesn't this work" with no context), ask 1 short clarifying question first so you can give a much more accurate answer, and end your message with a single line in this exact format:
[options: Short option 1 | Short option 2 | Short option 3]
- Provide 2 to 4 short options (max ~32 characters each) in the user's language.
"""


def build_system_prompt(extra: str | None = None, clarify_vague: bool = True) -> str:
    prompt = DEFAULT_SYSTEM_PROMPT
    if clarify_vague:
        prompt = f"{prompt}\n{CLARIFY_PROMPT_ADDON}"
    if extra and extra.strip():
        prompt = f"{prompt}\n\nAdditional user instructions:\n{extra.strip()}"
    return prompt


def _format_target(target: TargetData) -> str:
    lines = [f"### Targeted {target.kind.capitalize()}"]
    lines.append(f"- ID: `{target.id}`")
    if target.display_name:
        lines.append(f"- Name: {target.display_name}")
    if target.mod_id:
        lines.append(f"- Mod: `{target.mod_id}`")
    if target.pos:
        lines.append(f"- Position: {target.pos}")
    if target.block_state:
        state_str = ", ".join(f"{k}={v}" for k, v in target.block_state.items())
        lines.append(f"- BlockState: `{state_str}`")
    if target.block_entity_nbt:
        nbt = target.block_entity_nbt
        if len(nbt) > 1500:
            nbt = nbt[:1500] + "... (truncated)"
        lines.append(f"- BlockEntity NBT: `{nbt}`")
    if target.entity_health is not None:
        max_hp = f"/{target.entity_max_health:.1f}" if target.entity_max_health is not None else ""
        lines.append(f"- Health: {target.entity_health:.1f}{max_hp}")
    if target.extra_tags:
        lines.append(f"- Tags: {', '.join(target.extra_tags[:20])}")
    return "\n".join(lines)


def _format_item(item: ItemData) -> str:
    lines = [f"### Hovered Item ({item.source})"]
    lines.append(f"- ID: `{item.id}` (x{item.count})")
    if item.display_name:
        lines.append(f"- Name: {item.display_name}")
    if item.mod_id:
        lines.append(f"- Mod: `{item.mod_id}`")
    if item.tooltip:
        tooltip_preview = " | ".join(line for line in item.tooltip[:12] if line.strip())
        if tooltip_preview:
            lines.append(f"- Tooltip: {tooltip_preview}")
    if item.components:
        comp_json = json.dumps(item.components, ensure_ascii=False)
        if len(comp_json) > 1500:
            comp_json = comp_json[:1500] + "... (truncated)"
        lines.append(f"- Data Components: `{comp_json}`")
    if item.tags:
        lines.append(f"- Tags: {', '.join(item.tags[:20])}")
    if item.recipes:
        lines.append("- Actual in-game recipes:")
        for r in item.recipes[:5]:
            lines.append(f"  - {r}")
    return "\n".join(lines)


def build_text_prompt(req: ChatRequest, screenshot_path: str | None = None) -> str:
    sections: list[str] = []
    if req.target_data is not None:
        sections.append(_format_target(req.target_data))
    if req.item_data is not None:
        sections.append(_format_item(req.item_data))
    if screenshot_path:
        sections.append(
            f"### Attached Screenshot\n"
            f"- Path: `{screenshot_path}`\n"
            f"- Inspect this image with your file-reading tool if visual context helps answer the question."
        )

    if sections:
        context_block = "## Attached In-Game Context\n" + "\n\n".join(sections)
        return f"{context_block}\n\n## User Question\n{req.message}"
    return req.message


def decode_screenshot_bytes(req: ChatRequest) -> bytes | None:
    if not req.screenshot_base64:
        return None
    raw_b64 = req.screenshot_base64.strip()
    if "," in raw_b64 and raw_b64.startswith("data:"):
        raw_b64 = raw_b64.split(",", 1)[1]
    return base64.b64decode(raw_b64)


def build_prompt_parts(req: ChatRequest) -> str | list[str | Image]:
    text_prompt = build_text_prompt(req)
    img_bytes = decode_screenshot_bytes(req)
    if not img_bytes:
        return text_prompt

    image_part = Image(
        data=img_bytes,
        mime_type=req.screenshot_mime or "image/jpeg",
        description="Current Minecraft 1.21.1 in-game screenshot",
    )
    return [text_prompt, image_part]

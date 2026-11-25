from __future__ import annotations

import asyncio
import json
import logging
import os
import shutil
import subprocess
import sys
import tempfile
import uuid
from collections.abc import AsyncIterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from google.antigravity import Agent, LocalAgentConfig

from .prompt_builder import (
    build_prompt_parts,
    build_system_prompt,
    build_text_prompt,
    decode_screenshot_bytes,
)
from .schemas import ChatRequest

logger = logging.getLogger("agy_bridge.agent")


@dataclass(frozen=True)
class _AgentConfigKey:
    workspace: str | None
    system_prompt_extra: str | None
    model: str | None
    api_key: str | None
    clarify_vague: bool = True


def _find_agy_cli() -> str | None:
    found = shutil.which("agy")
    if found:
        return found
    if sys.platform == "win32":
        local_app = os.environ.get("LOCALAPPDATA")
        if local_app:
            candidate = Path(local_app) / "agy" / "bin" / "agy.exe"
            if candidate.is_file():
                return str(candidate)
    else:
        for p in (Path.home() / ".local" / "bin" / "agy", Path("/usr/local/bin/agy")):
            if p.is_file():
                return str(p)
    return None


def _find_opencode_cli() -> str | None:
    found = shutil.which("opencode")
    if found:
        return found
    if sys.platform == "win32":
        appdata = os.environ.get("APPDATA")
        if appdata:
            for name in ("opencode.cmd", "opencode.exe"):
                candidate = Path(appdata) / "npm" / name
                if candidate.is_file():
                    return str(candidate)
    return None


class AgentService:
    """Manages Antigravity (SDK / agy CLI) and OpenCode CLI sessions for Minecraft."""

    def __init__(self) -> None:
        self._lock = asyncio.Lock()
        self._agent: Agent | None = None
        self._active_key: _AgentConfigKey | None = None
        self._agy_conversation_id: str | None = None
        self._opencode_session_id: str | None = None
        self._agy_cli_path: str | None = _find_agy_cli()
        self._opencode_cli_path: str | None = _find_opencode_cli()
        self._shot_dir = Path(tempfile.gettempdir()) / "agy_mc_screenshots"

    @property
    def is_ready(self) -> bool:
        return (
            self._agent is not None
            or self._agy_cli_path is not None
            or self._opencode_cli_path is not None
        )

    def _resolve_workspace(self, req: ChatRequest) -> str | None:
        if not req.allow_game_dir_read or not req.game_dir:
            return None
        path = Path(req.game_dir)
        if path.exists() and path.is_dir():
            return str(path.resolve())
        logger.warning("Requested game_dir does not exist: %s", req.game_dir)
        return None

    def _resolve_api_key(self, req: ChatRequest) -> str | None:
        if req.api_key and req.api_key.strip():
            return req.api_key.strip()
        env_key = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")
        return env_key.strip() if env_key and env_key.strip() else None

    async def _ensure_sdk_agent(self, key: _AgentConfigKey) -> Agent:
        if self._agent is not None and self._active_key == key:
            return self._agent

        await self._close_sdk_unlocked()

        workspaces = [key.workspace] if key.workspace else None
        config = LocalAgentConfig(
            system_instructions=build_system_prompt(key.system_prompt_extra, key.clarify_vague),
            capabilities=None,  # Read-only mode by default for safety
            workspaces=workspaces,
            model=key.model if key.model else None,
            api_key=key.api_key,
        )
        agent = Agent(config)
        await agent.__aenter__()
        self._agent = agent
        self._active_key = key
        logger.info(
            "Started Antigravity SDK Agent (workspace=%s, model=%s)",
            key.workspace,
            key.model or "default",
        )
        return agent

    async def stream_chat(self, req: ChatRequest) -> AsyncIterator[dict[str, Any]]:
        api_key = self._resolve_api_key(req)
        workspace = self._resolve_workspace(req)

        async with self._lock:
            if req.provider == "opencode":
                async for event in self._stream_via_opencode_cli(req, workspace):
                    yield event
            elif api_key:
                async for event in self._stream_via_sdk(req, api_key, workspace):
                    yield event
            else:
                async for event in self._stream_via_agy_cli(req, workspace):
                    yield event

    async def _stream_via_sdk(
        self,
        req: ChatRequest,
        api_key: str,
        workspace: str | None,
    ) -> AsyncIterator[dict[str, Any]]:
        key = _AgentConfigKey(
            workspace=workspace,
            system_prompt_extra=req.system_prompt_extra,
            model=req.model,
            api_key=api_key,
            clarify_vague=req.clarify_vague,
        )
        prompt = build_prompt_parts(req)
        try:
            agent = await self._ensure_sdk_agent(key)
            response = await agent.chat(prompt)
            async for token in response:
                if token:
                    yield {"type": "token", "content": str(token)}
            yield {"type": "done"}
        except Exception as exc:  # noqa: BLE001
            logger.exception("Error during SDK agent chat")
            yield {"type": "error", "message": str(exc)}

    async def _stream_via_agy_cli(
        self,
        req: ChatRequest,
        workspace: str | None,
    ) -> AsyncIterator[dict[str, Any]]:
        agy_exe = self._agy_cli_path or _find_agy_cli()
        if not agy_exe:
            yield {
                "type": "error",
                "message": "No GEMINI_API_KEY set and agy CLI not found. Set API key in Config or install agy CLI.",
            }
            return
        self._agy_cli_path = agy_exe

        shot_path: Path | None = None
        img_bytes = decode_screenshot_bytes(req)
        if img_bytes:
            self._shot_dir.mkdir(parents=True, exist_ok=True)
            ext = "png" if (req.screenshot_mime or "").endswith("png") else "jpg"
            shot_path = self._shot_dir / f"mc_{uuid.uuid4().hex[:10]}.{ext}"
            shot_path.write_bytes(img_bytes)

        user_text = build_text_prompt(req, str(shot_path) if shot_path else None)
        if self._agy_conversation_id is None:
            sys_prompt = build_system_prompt(req.system_prompt_extra, req.clarify_vague)
            full_prompt = f"{sys_prompt}\n\n---\n\n{user_text}"
        else:
            full_prompt = user_text

        cmd = [
            agy_exe,
            "--output-format",
            "stream-json",
            "--disable-slash-commands",
            "--print-timeout",
            "90s",
        ]
        if self._agy_conversation_id:
            cmd.extend(["--conversation", self._agy_conversation_id])
        if req.model and req.model.strip():
            cmd.extend(["--model", req.model.strip()])
        if workspace:
            cmd.extend(["--add-dir", workspace])
        if shot_path:
            cmd.extend(["--add-dir", str(self._shot_dir)])
        cmd.extend(["--print", full_prompt])

        creation_flags = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
        logger.info(
            "Running agy CLI turn (conversation=%s, workspace=%s, screenshot=%s)",
            self._agy_conversation_id or "new",
            workspace,
            bool(shot_path),
        )

        proc: asyncio.subprocess.Process | None = None
        try:
            proc = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                creationflags=creation_flags,
            )
            assert proc.stdout is not None

            emitted_any = False
            while True:
                raw_line = await proc.stdout.readline()
                if not raw_line:
                    break
                line = raw_line.decode("utf-8", errors="replace").strip()
                if not line or not line.startswith("{"):
                    continue
                try:
                    evt = json.loads(line)
                except json.JSONDecodeError:
                    continue

                evt_type = evt.get("event")
                if evt_type == "step_update":
                    upd = evt.get("step_update") or {}
                    conv_id = upd.get("conversation_id")
                    if conv_id:
                        self._agy_conversation_id = conv_id
                    if upd.get("step_type") == "agent_response":
                        delta = upd.get("text_delta")
                        if delta:
                            emitted_any = True
                            yield {"type": "token", "content": str(delta)}
                elif evt_type == "result":
                    res = evt.get("result") or {}
                    conv_id = res.get("conversation_id")
                    if conv_id:
                        self._agy_conversation_id = conv_id
                    status = res.get("status")
                    if status and status != "SUCCESS":
                        err_msg = res.get("error") or res.get("response") or f"agy status={status}"
                        yield {"type": "error", "message": str(err_msg)}
                        return
                    if not emitted_any and res.get("response"):
                        emitted_any = True
                        yield {"type": "token", "content": str(res["response"])}

            rc = await proc.wait()
            if not emitted_any:
                stderr_text = ""
                if proc.stderr:
                    stderr_text = (await proc.stderr.read()).decode("utf-8", errors="replace").strip()
                logger.warning("agy CLI returned no tokens (rc=%d, stderr=%s)", rc, stderr_text[:300])
                yield {
                    "type": "error",
                    "message": stderr_text[:300] or f"agy CLI returned no text (exit code {rc})",
                }
                return

            yield {"type": "done"}
        except Exception as exc:  # noqa: BLE001
            logger.exception("Error running agy CLI")
            yield {"type": "error", "message": str(exc)}
        finally:
            if proc and proc.returncode is None:
                proc.kill()
            if shot_path and shot_path.exists():
                try:
                    shot_path.unlink()
                except OSError:
                    pass

    async def _stream_via_opencode_cli(
        self,
        req: ChatRequest,
        workspace: str | None,
    ) -> AsyncIterator[dict[str, Any]]:
        oc_exe = self._opencode_cli_path or _find_opencode_cli()
        if not oc_exe:
            yield {
                "type": "error",
                "message": "opencode CLI not found in PATH or %APPDATA%\\npm.",
            }
            return
        self._opencode_cli_path = oc_exe

        shot_path: Path | None = None
        img_bytes = decode_screenshot_bytes(req)
        if img_bytes:
            self._shot_dir.mkdir(parents=True, exist_ok=True)
            ext = "png" if (req.screenshot_mime or "").endswith("png") else "jpg"
            shot_path = self._shot_dir / f"mc_{uuid.uuid4().hex[:10]}.{ext}"
            shot_path.write_bytes(img_bytes)

        user_text = build_text_prompt(req, str(shot_path) if shot_path else None)
        if self._opencode_session_id is None:
            sys_prompt = build_system_prompt(req.system_prompt_extra, req.clarify_vague)
            full_prompt = f"{sys_prompt}\n\n---\n\n{user_text}"
        else:
            full_prompt = user_text

        cmd = [oc_exe, "run", "--format", "json"]
        if self._opencode_session_id:
            cmd.extend(["--session", self._opencode_session_id])
        if req.model and req.model.strip():
            cmd.extend(["--model", req.model.strip()])
        if shot_path:
            cmd.extend(["--file", str(shot_path)])
        cmd.append(full_prompt)

        creation_flags = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
        logger.info(
            "Running opencode CLI turn (session=%s, workspace=%s)",
            self._opencode_session_id or "new",
            workspace,
        )

        proc: asyncio.subprocess.Process | None = None
        try:
            proc = await asyncio.create_subprocess_exec(
                *cmd,
                cwd=workspace if workspace else None,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                creationflags=creation_flags,
            )
            assert proc.stdout is not None

            emitted_any = False
            while True:
                raw_line = await proc.stdout.readline()
                if not raw_line:
                    break
                line = raw_line.decode("utf-8", errors="replace").strip()
                if not line:
                    continue
                if not line.startswith("{"):
                    emitted_any = True
                    yield {"type": "token", "content": line + "\n"}
                    continue
                try:
                    evt = json.loads(line)
                except json.JSONDecodeError:
                    continue

                sess_id = evt.get("sessionID") or evt.get("session_id")
                if sess_id:
                    self._opencode_session_id = str(sess_id)

                evt_type = evt.get("type")
                if evt_type == "text":
                    part = evt.get("part") or {}
                    text_chunk = part.get("text") or evt.get("text") or evt.get("delta")
                    if text_chunk:
                        emitted_any = True
                        yield {"type": "token", "content": str(text_chunk)}
                elif evt_type == "error":
                    err_obj = evt.get("error") or {}
                    err_msg = err_obj.get("message") if isinstance(err_obj, dict) else str(err_obj)
                    yield {"type": "error", "message": err_msg or "OpenCode error"}
                    return

            rc = await proc.wait()
            if rc != 0 and not emitted_any:
                stderr_text = ""
                if proc.stderr:
                    stderr_text = (await proc.stderr.read()).decode("utf-8", errors="replace").strip()
                yield {
                    "type": "error",
                    "message": stderr_text[:300] or f"opencode exited with code {rc}",
                }
                return

            yield {"type": "done"}
        except Exception as exc:  # noqa: BLE001
            logger.exception("Error running opencode CLI")
            yield {"type": "error", "message": str(exc)}
        finally:
            if proc and proc.returncode is None:
                proc.kill()
            if shot_path and shot_path.exists():
                try:
                    shot_path.unlink()
                except OSError:
                    pass

    async def reset(self) -> None:
        async with self._lock:
            await self._close_sdk_unlocked()
            self._agy_conversation_id = None
            self._opencode_session_id = None
            logger.info("Agent conversation reset.")

    async def close(self) -> None:
        async with self._lock:
            await self._close_sdk_unlocked()
            self._agy_conversation_id = None
            self._opencode_session_id = None

    async def _close_sdk_unlocked(self) -> None:
        if self._agent is not None:
            try:
                await self._agent.__aexit__(None, None, None)
            except Exception:  # noqa: BLE001
                logger.exception("Error closing Antigravity Agent")
            finally:
                self._agent = None
                self._active_key = None

from __future__ import annotations

import argparse
import asyncio
import ctypes
import json
import logging
import os
import signal
import sys
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI
from fastapi.responses import StreamingResponse

from . import __version__
from .agent_service import AgentService
from .schemas import ChatRequest, HealthResponse, ResetResponse

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("agy_bridge")


def _is_pid_alive(pid: int) -> bool:
    if pid <= 0:
        return True
    if sys.platform == "win32":
        PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
        STILL_ACTIVE = 259
        kernel32 = ctypes.windll.kernel32
        handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
        if not handle:
            return False
        try:
            exit_code = ctypes.c_ulong()
            if kernel32.GetExitCodeProcess(handle, ctypes.byref(exit_code)):
                return exit_code.value == STILL_ACTIVE
            return False
        finally:
            kernel32.CloseHandle(handle)
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def create_app(
    agent_service: AgentService | None = None,
    parent_pid: int | None = None,
) -> FastAPI:
    service = agent_service or AgentService()

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        watchdog_task: asyncio.Task[None] | None = None
        if parent_pid:
            async def _watch_parent() -> None:
                while True:
                    await asyncio.sleep(3.0)
                    if not _is_pid_alive(parent_pid):
                        logger.info("Parent PID %d exited; shutting down bridge.", parent_pid)
                        os.kill(os.getpid(), signal.SIGTERM)
                        return

            watchdog_task = asyncio.create_task(_watch_parent())
        try:
            yield
        finally:
            if watchdog_task:
                watchdog_task.cancel()
            await service.close()

    app = FastAPI(title="Antigravity Minecraft Bridge", version=__version__, lifespan=lifespan)
    app.state.agent_service = service

    @app.get("/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        return HealthResponse(
            status="ok",
            version=__version__,
            agent_ready=service.is_ready,
            pid=os.getpid(),
        )

    @app.post("/chat")
    async def chat(req: ChatRequest) -> StreamingResponse:
        async def event_stream():
            async for event in service.stream_chat(req):
                payload = json.dumps(event, ensure_ascii=False)
                yield f"data: {payload}\n\n"

        return StreamingResponse(
            event_stream(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "Connection": "keep-alive",
                "X-Accel-Buffering": "no",
            },
        )

    @app.post("/reset", response_model=ResetResponse)
    async def reset() -> ResetResponse:
        await service.reset()
        return ResetResponse(status="reset")

    return app


app = create_app()


def run_cli() -> None:
    if sys.stdout is None:
        sys.stdout = open(os.devnull, "w", encoding="utf-8")
    if sys.stderr is None:
        sys.stderr = open(os.devnull, "w", encoding="utf-8")

    parser = argparse.ArgumentParser(description="Antigravity Minecraft local HTTP/SSE bridge")
    parser.add_argument("--host", default=os.environ.get("AGY_BRIDGE_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("AGY_BRIDGE_PORT", "18765")))
    parser.add_argument(
        "--parent-pid",
        type=int,
        default=int(os.environ.get("AGY_BRIDGE_PARENT_PID", "0")) or None,
        help="Optional Minecraft JVM PID; bridge exits automatically if parent dies.",
    )
    args = parser.parse_args()

    logger.info("Starting agy-bridge on %s:%d (parent_pid=%s)", args.host, args.port, args.parent_pid)
    bridge_app = create_app(parent_pid=args.parent_pid)
    uvicorn.run(bridge_app, host=args.host, port=args.port, log_level="info", log_config=None)


if __name__ == "__main__":
    run_cli()

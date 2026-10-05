"""uvicorn이 실제 loopback HTTP로 AI 앱을 제공하는지 검증한다."""

from __future__ import annotations

import socket
import threading
import time

import httpx
import uvicorn


def test_uvicorn_serves_health_over_real_http() -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
        server = uvicorn.Server(uvicorn.Config(
            "main:app", host="127.0.0.1", port=0,
            loop="asyncio", http="h11", lifespan="off",
            log_level="warning", access_log=False,
            timeout_graceful_shutdown=1,
        ))
        startup_errors: list[Exception] = []

        def run_server() -> None:
            try:
                server.run(sockets=[listener])
            except Exception as exc:
                startup_errors.append(exc)

        thread = threading.Thread(target=run_server, daemon=True)
        thread.start()
        try:
            deadline = time.monotonic() + 5
            while not server.started and thread.is_alive() and time.monotonic() < deadline:
                time.sleep(0.01)
            assert not startup_errors, f"uvicorn startup failed: {startup_errors}"
            assert server.started, "uvicorn did not start within 5 seconds"
            with httpx.Client(timeout=2, trust_env=False) as client:
                response = client.get(f"http://127.0.0.1:{port}/health")
            assert response.status_code == 200
            assert response.json() == {
                "status": "ok", "service": "gitory-ai", "version": "0.0.1"
            }
        finally:
            server.should_exit = True
            thread.join(timeout=5)
            if thread.is_alive():
                server.force_exit = True
                thread.join(timeout=1)
            assert not thread.is_alive(), "uvicorn did not stop within 6 seconds"

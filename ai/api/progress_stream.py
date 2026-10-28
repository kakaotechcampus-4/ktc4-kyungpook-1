"""진행 줄을 흘려보내다가 마지막 줄에 보통 API 와 같은 응답을 싣는 스트리밍 응답."""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import AsyncIterator, Awaitable, Callable
from typing import Any

from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse, StreamingResponse

from schemas.common import Envelope, ErrorDetail
from services.progress import listening
from services.usage import usage_meta

logger = logging.getLogger(__name__)

NDJSON = "application/x-ndjson"

STREAM_DESCRIPTION = (
    "본문은 한 줄에 JSON 하나(NDJSON)입니다. 처리하는 동안 "
    '`{"type":"progress","step":"PR_REVIEW|COMMITS|COMPRESS|REASON","done":0,"total":44}` 줄을 보내고, '
    '마지막 줄은 `{"type":"result","status":200,"body":{...}}` 입니다. '
    "`status`·`body` 는 같은 요청을 스트리밍 없는 API 로 보냈을 때의 HTTP 상태와 응답 본문과 같습니다. "
    "응답 헤더를 보낸 뒤에는 상태를 바꿀 수 없어, 오류도 HTTP 200 의 마지막 줄로 알립니다."
)


def stream_with_progress(path: str, run: Callable[[], Awaitable[Any]]) -> StreamingResponse:
    """run 이 알리는 진행을 줄마다 보내고, 끝나면 run 의 응답을 마지막 줄로 보낸다."""
    return StreamingResponse(_lines(path, run), media_type=NDJSON)


async def _lines(path: str, run: Callable[[], Awaitable[Any]]) -> AsyncIterator[str]:
    queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()

    def on_progress(step: str, done: int, total: int | None) -> None:
        queue.put_nowait({"type": "progress", "step": step, "done": done, "total": total})

    with listening(on_progress):
        task = asyncio.ensure_future(run())
    try:
        while True:
            getter = asyncio.ensure_future(queue.get())
            finished, _ = await asyncio.wait({task, getter}, return_when=asyncio.FIRST_COMPLETED)
            if getter in finished:
                yield _line(getter.result())
                continue
            getter.cancel()
            while not queue.empty():
                yield _line(queue.get_nowait())
            yield _line(_result(path, task))
            return
    finally:
        # 호출자가 끊어 스트림이 닫히면 남은 GitHub·LLM 호출을 내보내지 않는다
        if not task.done():
            task.cancel()


def _result(path: str, task: asyncio.Future[Any]) -> dict[str, Any]:
    try:
        response = task.result()
    except Exception as exc:
        # 헤더를 이미 보내 전역 처리기가 받지 못하므로, 전역 처리기와 같은 줄과 응답을 남긴다
        logger.error("AI 처리 못 한 예외: path=%s exc=%s", path, type(exc).__name__)
        return {"type": "result", "status": 500, "body": jsonable_encoder(_internal_error())}
    if isinstance(response, JSONResponse):
        return {"type": "result", "status": response.status_code, "body": json.loads(response.body)}
    return {"type": "result", "status": 200, "body": jsonable_encoder(response)}


def _internal_error() -> Envelope:
    return Envelope(
        success=False,
        data=None,
        meta=usage_meta(None, "FAILED"),
        error=ErrorDetail(code="INTERNAL_ERROR", message="AI 내부 오류로 처리하지 못했습니다.", retryable=False),
    )


def _line(item: dict[str, Any]) -> str:
    return json.dumps(item, ensure_ascii=False) + "\n"

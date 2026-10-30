"""LLM 호출을 보내는 스레드 실행기.

경험 그룹화와 카드 초안(diff 근거·STAR)이 파이썬 기본 실행기를 같이 쓰면 초안 호출이 자리를
차지해 그룹화가 마감을 넘길 수 있어, 둘을 따로 둔다.
공고 매칭의 조건 뽑기와 색인·검색도 이벤트 루프를 막지 않도록 같은 방식으로 보낸다.
"""

from __future__ import annotations

import asyncio
import contextvars
import threading
import time
from concurrent.futures import Executor, ThreadPoolExecutor
from typing import Any, Callable, Optional, TypeVar

T = TypeVar("T")

#: 백엔드 워커가 Job을 동시에 최대 3개 돌리고, 그룹화는 요청 하나에서 LLM을 하나씩 부른다.
GROUPING_EXECUTOR = ThreadPoolExecutor(max_workers=3, thread_name_prefix="llm-grouping")
#: diff 근거가 요청 하나에서 최대 3개를 동시에 부르므로 초안 2개가 겹쳐도 기다리지 않는다.
CARD_DRAFT_EXECUTOR = ThreadPoolExecutor(max_workers=6, thread_name_prefix="llm-card-draft")
#: 공고 조건 뽑기는 호출 한 번이 몇 분까지 걸릴 수 있어 그룹화·카드 초안 자리를 쓰지 않게 따로 둔다.
POSTING_EXTRACTION_EXECUTOR = ThreadPoolExecutor(max_workers=2, thread_name_prefix="llm-posting")
#: 되묻기는 사용자가 화면에서 기다리는 요청이라 카드 초안 줄에 밀리지 않게 따로 두고,
#: 동시 사용자 10명 안팎이 몰려도 마감 안에 시작하도록 6개로 둔다.
INTERVIEW_EXECUTOR = ThreadPoolExecutor(max_workers=6, thread_name_prefix="llm-interview")
#: 자소서는 문항마다 동시에 부르므로 되묻기 자리를 차지하지 않게 따로 둔다.
COVER_LETTER_EXECUTOR = ThreadPoolExecutor(max_workers=6, thread_name_prefix="llm-cover-letter")
#: 마감을 넘겨 버려진 쓰기 호출이 스트림을 다 받을 때까지 자리를 잡아도 감독·다음 행동 판단이 밀리지 않게 나눈다.
COVER_LETTER_JUDGE_EXECUTOR = ThreadPoolExecutor(max_workers=4, thread_name_prefix="llm-cover-letter-judge")
#: 색인과 검색이 같은 로컬 Chroma 파일을 동시에 열지 않게 한 줄로 세운다.
MATCHING_EXECUTOR = ThreadPoolExecutor(max_workers=1, thread_name_prefix="matching-index")


class LLMCallSkipped(Exception):
    """실행기 줄에서 기다리는 사이 마감이 지나거나 요청이 취소돼 LLM을 부르지 않았다."""


async def run_llm_call(
    executor: Executor,
    call: Callable[..., T],
    *args: Any,
    deadline: Optional[float] = None,
    min_seconds: float = 0,
) -> T:
    """``call(*args)``를 실행기 스레드에서 부른다.

    스레드를 얻은 시점에 요청이 취소됐거나 ``deadline``(이벤트 루프 시각)까지 남은 시간이 없거나
    ``min_seconds``보다 짧으면 부르지 않고 ``LLMCallSkipped``를 올린다. ``deadline``을 주면 그때 남은 초를
    마지막 인자로 더해 넘긴다.
    """
    loop = asyncio.get_running_loop()
    # 스레드에서는 이벤트 루프 시계를 읽지 않도록 같은 순간의 monotonic 시각으로 옮겨 둔다.
    expires_at = None if deadline is None else time.monotonic() + (deadline - loop.time())
    abandoned = threading.Event()
    context = contextvars.copy_context()

    def start() -> T:
        if abandoned.is_set():
            raise LLMCallSkipped
        if expires_at is None:
            return call(*args)
        remaining = expires_at - time.monotonic()
        # 남은 시간이 너무 짧으면 응답 전에 끊기기 쉬워 LLM 비용만 쓰게 된다.
        if remaining <= 0 or remaining < min_seconds:
            raise LLMCallSkipped
        # 줄에서 기다린 만큼 줄여 넘겨야 늦게 시작한 호출이 마감 뒤까지 스레드를 잡지 않는다.
        return call(*args, remaining)

    try:
        return await loop.run_in_executor(executor, context.run, start)
    except asyncio.CancelledError:
        # 취소가 실행기 작업을 지우기 전에 스레드가 먼저 집어 갔어도, 아직 부르기 전이면 LLM은 부르지 않게 한다.
        abandoned.set()
        raise

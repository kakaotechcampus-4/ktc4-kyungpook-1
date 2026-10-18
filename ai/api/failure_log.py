"""처리한 실패의 원인을 로그 한 줄로 남긴다."""

from __future__ import annotations

import logging
import time


def _root_cause_name(exc: BaseException) -> str:
    """타임아웃·한도·5xx가 모두 같은 감싼 예외로 보이므로 가장 안쪽 원인의 종류를 찾는다.

    마감으로 끊긴 호출은 TimeoutError 아래에 CancelledError 가 붙어 있어 일반 예외까지만 따라간다.
    """
    seen = {id(exc)}
    current = exc
    while isinstance(current.__cause__, Exception) and id(current.__cause__) not in seen:
        current = current.__cause__
        seen.add(id(current))
    return type(current).__name__ if current is not exc else "-"


def log_failure(
    logger: logging.Logger,
    feature: str,
    *,
    path: str,
    code: str,
    retryable: bool,
    exc: BaseException,
    started: float,
    **ids: int | str,
) -> None:
    """응답에는 고정 문구만 나가므로 운영에서 원인을 찾을 수 있게 예외 종류만 남긴다.

    예외 문구와 traceback에는 요청·모델 출력 조각이 실릴 수 있어 싣지 않는다.
    """
    cause = type(exc.__cause__).__name__ if exc.__cause__ is not None else "-"
    root = _root_cause_name(exc)
    status = getattr(exc, "status_code", None)
    extras = "".join(f" {name}={value!r}" for name, value in ids.items())
    logger.warning(
        "%s 실패: path=%s code=%s retryable=%s exc=%s cause=%s root=%s github_status=%s%s ms=%d",
        feature,
        path,
        code,
        str(retryable).lower(),
        type(exc).__name__,
        cause,
        root,
        status if status is not None else "-",
        extras,
        int((time.perf_counter() - started) * 1000),
    )

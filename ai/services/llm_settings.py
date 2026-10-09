"""환경변수로 작업별 LLM 호출기를 만든다.

연결 정보(GITORY_LLM_BASE_URL·API_KEY·TIMEOUT_SECONDS)는 모든 작업이 공유하고,
모델과 추론 강도만 작업별(GITORY_<TASK>_MODEL·REASONING_EFFORT)로 바꿀 수 있다.
그룹화(#109)의 환경변수 이름과 같은 규칙이다.
"""

from __future__ import annotations

import os
import threading
from typing import Any, Optional

from services.llm_structured_client import (
    LLMSettings,
    StructuredCallResult,
    StructuredLLMClient,
)

DEFAULT_MODEL = "gpt-5.6-luna"
DEFAULT_REASONING_EFFORT = "medium"
DEFAULT_TIMEOUT_SECONDS = 120.0
_EFFORTS = ("none", "low", "medium", "high")


class LLMConfigError(RuntimeError):
    """LLM 환경 설정이 없거나 잘못됐다. 재시도로 해결되지 않는다."""


def load_llm_settings(task: str) -> LLMSettings:
    """``task``(예: DIFF, STAR)에 해당하는 모델 설정을 환경변수에서 읽는다."""
    base_url = os.getenv("GITORY_LLM_BASE_URL", "").strip()
    api_key = os.getenv("GITORY_LLM_API_KEY", "").strip()
    missing = [
        name
        for name, value in (("GITORY_LLM_BASE_URL", base_url), ("GITORY_LLM_API_KEY", api_key))
        if not value
    ]
    if missing:
        raise LLMConfigError("LLM 환경변수가 없습니다: " + ", ".join(missing))

    effort_name = f"GITORY_{task}_REASONING_EFFORT"
    effort = os.getenv(effort_name, DEFAULT_REASONING_EFFORT).strip() or DEFAULT_REASONING_EFFORT
    if effort not in _EFFORTS:
        raise LLMConfigError(f"{effort_name}는 {'/'.join(_EFFORTS)} 중 하나여야 합니다")

    timeout = os.getenv("GITORY_LLM_TIMEOUT_SECONDS", str(DEFAULT_TIMEOUT_SECONDS)).strip()
    try:
        timeout_seconds = float(timeout)
    except ValueError as exc:
        raise LLMConfigError("GITORY_LLM_TIMEOUT_SECONDS가 숫자가 아닙니다") from exc
    if timeout_seconds <= 0:
        raise LLMConfigError("GITORY_LLM_TIMEOUT_SECONDS는 0보다 커야 합니다")

    return LLMSettings(
        base_url=base_url,
        api_key=api_key,
        model=os.getenv(f"GITORY_{task}_MODEL", DEFAULT_MODEL).strip() or DEFAULT_MODEL,
        reasoning_effort=effort,  # type: ignore[arg-type]
        timeout_seconds=timeout_seconds,
    )


class EnvironmentLLMClient:
    """첫 호출 때 환경변수로 호출기를 만든다. 설정이 없어도 앱 시작은 막지 않는다."""

    def __init__(self, task: str) -> None:
        self._task = task
        self._client: Optional[StructuredLLMClient] = None
        self._lock = threading.Lock()

    def call(
        self,
        system_prompt: str,
        payload: dict[str, Any],
        output_model: type,
        max_completion_tokens: int,
    ) -> StructuredCallResult:
        with self._lock:
            if self._client is None:
                self._client = StructuredLLMClient(load_llm_settings(self._task))
            client = self._client
        return client.call(system_prompt, payload, output_model, max_completion_tokens)

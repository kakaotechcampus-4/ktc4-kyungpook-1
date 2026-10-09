"""모델 비교용 LLM 작업의 공통 계약과 자동 검사 도구.

각 작업은 프롬프트·출력 스키마·입력 변환·자동 검사(하드 조건)를 한 모듈에 둔다.
자동 검사는 코드로 확정할 수 있는 위반만 잡고, 누락·품질은 이후 판정 모델이 채점한다.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any, Callable

from pydantic import BaseModel

from services.llm_structured_client import ReasoningEffort
from services.evidence_checks import METRIC_PATTERN as _METRIC_PATTERN
from services.secret_redaction import find_secrets, redact_secrets  # noqa: F401  (작업 모듈 재사용)


@dataclass(frozen=True)
class EvalTask:
    """비교 실행기가 다루는 LLM 작업 하나."""

    name: str
    system_prompt: str
    output_model: type[BaseModel]
    default_reasoning_effort: ReasoningEffort
    max_completion_tokens: int
    build_payload: Callable[[dict[str, Any]], dict[str, Any]]
    check: Callable[[dict[str, Any], BaseModel], list[str]]


def invented_metrics(output_text: str, source_text: str) -> list[str]:
    """입력 어디에도 없는 수치 표현을 반환한다. 공백 차이는 무시한다."""
    normalized_source = re.sub(r"\s+", "", source_text)
    return sorted({
        match.group(0)
        for match in _METRIC_PATTERN.finditer(output_text)
        if re.sub(r"\s+", "", match.group(0)) not in normalized_source
    })


def dump(value: Any) -> str:
    """검사용으로 입력·출력을 하나의 문자열로 만든다."""
    if isinstance(value, BaseModel):
        value = value.model_dump()
    return json.dumps(value, ensure_ascii=False)


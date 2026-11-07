"""자소서 시험의 가짜 LLM 들이 같이 쓰는 답."""

from __future__ import annotations

from typing import Any

from services.cover_letter_writer import ReadinessOutput, SupportedOutput


def all_evidence(payload: dict[str, Any]) -> ReadinessOutput:
    """쓰기 전 준비도에 '증거 넷을 다 보여 줌' 으로 답해, 준비도와 상관없는 시험이 쓰기로 바로 가게 한다.

    가장 긴 재료 줄을 그대로 인용해 코드의 인용 확인을 통과한다.
    """
    quote = max(payload["material"], key=len)
    return ReadinessOutput(supported=[SupportedOutput(evidence=number, quote=quote) for number in range(1, 5)])

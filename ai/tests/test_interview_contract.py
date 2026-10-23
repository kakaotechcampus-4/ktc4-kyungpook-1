"""Spring ↔ AI 되묻기 공통 계약 검증."""

import json
from pathlib import Path

import pytest

from schemas.interview import InterviewTurnRequest


CONTRACT_DIR = Path(__file__).resolve().parents[2] / "contracts" / "interview"


@pytest.mark.parametrize("name", ["direct-card.json", "pr-candidate-card.json"])
def test_interview_contract_request_matches_ai_model(name: str) -> None:
    """Spring 이 보내기로 한 대표 요청을 AI 의 Pydantic 계약이 그대로 받아들인다."""
    contract = json.loads((CONTRACT_DIR / name).read_text(encoding="utf-8"))

    expected = contract["springToAi"]["expectedRequest"]
    request = InterviewTurnRequest.model_validate(expected)

    # 되묻기 루프 칸(allowed_slots 등)은 기본값이 있어 Spring 이 보내기 전에도 같은 요청을 받는다
    assert request.model_dump(mode="json", exclude_unset=True) == expected

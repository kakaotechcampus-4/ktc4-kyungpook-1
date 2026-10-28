"""요청 하나의 LLM 호출 기록이 같은 기준으로 모이고 응답 meta 에 실리는지 검증한다(#468)."""

from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import httpx
import pytest
from fastapi.testclient import TestClient
from openai import APIConnectionError
from pydantic import BaseModel

import api.interviews
from api.analysis import get_analysis_pipeline
from main import app
from schemas.analysis import StarAnalysisResponse, StarFieldResult
from schemas.interview import InterviewTurnResult
from services.llm_executors import run_llm_call
from services.llm_structured_client import LLMSettings, LLMStructuredCallError, StructuredLLMClient
from services.star_generator import StarGenerationError
from services.usage import metering

client = TestClient(app)


class _Output(BaseModel):
    value: str


def _llm(*, fails: bool = False) -> StructuredLLMClient:
    """안쪽 OpenAI 만 가짜로 바꾼 공통 LLM 호출기로, 성공하면 입력 3·출력 2 토큰을 쓴다."""
    llm = StructuredLLMClient(LLMSettings(base_url="http://127.0.0.1:1", api_key="test", model="m"))
    completion = SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(parsed=_Output(value="ok")))],
        usage=SimpleNamespace(prompt_tokens=3, completion_tokens=2),
    )

    def parse(**_):
        if fails:
            raise APIConnectionError(request=httpx.Request("POST", "http://127.0.0.1:1/chat/completions"))
        return completion

    llm._client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(parse=parse)))
    return llm


async def _call(llm: StructuredLLMClient, executor: ThreadPoolExecutor) -> None:
    await run_llm_call(executor, llm.call, "system", {}, _Output, 100)


class _StarPipeline:
    """STAR 초안을 만들며 실행기 스레드에서 공통 LLM 호출기를 ``calls`` 번 부르는 대역."""

    def __init__(self, *, calls: int = 0, fails: bool = False, timeout_field: bool = False) -> None:
        self.calls = calls
        self.fails = fails
        self.timeout_field = timeout_field

    async def analyze_star(self, request) -> StarAnalysisResponse:
        with ThreadPoolExecutor(max_workers=2) as executor:
            for _ in range(self.calls):
                await _call(_llm(), executor)
        if self.fails:
            raise StarGenerationError("실패", retryable=True)
        filled = StarFieldResult(text="필터를 더했다.", status="FILLED", confidence="HIGH")
        r_field = (
            StarFieldResult(status="EMPTY", insufficient_reason_code="TIMEOUT") if self.timeout_field else filled
        )
        return StarAnalysisResponse(
            title=request.title,
            star={"S": filled, "T": filled, "A": filled, "R": r_field},
            missing_fields=["R"] if self.timeout_field else [],
        )


@pytest.fixture
def star_pipeline():
    def apply(pipeline: _StarPipeline) -> None:
        app.dependency_overrides[get_analysis_pipeline] = lambda: pipeline

    yield apply
    app.dependency_overrides.clear()


def _post_star():
    return client.post(
        "/internal/analysis/star",
        json={
            "candidate_id": "c1",
            "target_login": "octo",
            "title": "JWT 로그인 구현",
            "source_type": "PR",
            "evidence": [{
                "sha": "a" * 40, "message": "feat: jwt", "churn": "+1/-0",
                "summary": "요약", "summary_source": "DIFF",
            }],
        },
    )


def test_adds_every_llm_call_even_when_called_at_once() -> None:
    llm = _llm()

    async def three_at_once(executor: ThreadPoolExecutor) -> None:
        await asyncio.gather(*(_call(llm, executor) for _ in range(3)))

    with metering() as usage, ThreadPoolExecutor(max_workers=3) as executor:
        asyncio.run(three_at_once(executor))

    assert (usage.model, usage.llm_calls, usage.input_tokens, usage.output_tokens) == ("m", 3, 9, 6)


def test_failed_llm_call_is_counted_without_tokens() -> None:
    with metering() as usage:
        _llm().call("system", {}, _Output, 100)
        with pytest.raises(LLMStructuredCallError):
            _llm(fails=True).call("system", {}, _Output, 100)

    assert (usage.llm_calls, usage.input_tokens, usage.output_tokens) == (2, 3, 2)


def test_error_response_carries_llm_calls_made_before_failure(star_pipeline) -> None:
    star_pipeline(_StarPipeline(calls=1, fails=True))

    response = _post_star()

    assert response.status_code == 503
    meta = response.json()["meta"]
    assert (meta["model"], meta["llm_calls"], meta["input_tokens"], meta["ended_by"]) == ("m", 1, 3, "FAILED")


def test_response_without_llm_reports_no_calls_and_no_model(star_pipeline) -> None:
    star_pipeline(_StarPipeline(calls=0))

    meta = _post_star().json()["meta"]

    assert (meta["model"], meta["llm_calls"], meta["input_tokens"], meta["output_tokens"]) == (None, 0, 0, 0)
    assert (meta["tool_calls_made"], meta["repair_rounds"], meta["github_calls"]) == (0, 0, 0)


@pytest.mark.parametrize(("timeout_field", "ended_by"), [(True, "PARTIAL"), (False, "COMPLETED")])
def test_star_with_a_timed_out_field_ends_partial(star_pipeline, timeout_field: bool, ended_by: str) -> None:
    star_pipeline(_StarPipeline(calls=1, timeout_field=timeout_field))

    assert _post_star().json()["meta"]["ended_by"] == ended_by


@pytest.mark.parametrize(("fell_back", "ended_by"), [(True, "FALLBACK"), (False, "COMPLETED")])
def test_interview_question_from_template_ends_fallback(monkeypatch, fell_back: bool, ended_by: str) -> None:
    class _Agent:
        async def next_turn(self, request):
            return InterviewTurnResult(card_id=request.card_id, next_action="COMPLETE"), fell_back

    monkeypatch.setattr(api.interviews, "_agent", _Agent())

    response = client.post("/internal/interview-turns", json={
        "max_turns": 2, "card_id": 104, "source_type": "DIRECT_CARD", "candidate": None,
        "missing_slots": [{"star_slot": "R", "statement_seq": 1, "body": None, "confidence": "LOW", "linked_commits": []}],
        "existing_turn_count": 0,
    })

    assert response.json()["meta"]["ended_by"] == ended_by


def test_each_request_reports_only_its_own_llm_calls(star_pipeline) -> None:
    star_pipeline(_StarPipeline(calls=2))

    first = _post_star().json()["meta"]
    second = _post_star().json()["meta"]

    assert (first["llm_calls"], first["input_tokens"], first["output_tokens"]) == (2, 6, 4)
    assert (second["llm_calls"], second["input_tokens"], second["output_tokens"]) == (2, 6, 4)

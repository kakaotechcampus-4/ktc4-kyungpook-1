"""StarGenerator의 근거 검증·후처리 계약을 LLM 없이 검증한다."""

from __future__ import annotations

import asyncio
from typing import Any

import pytest

from schemas.analysis import StarAnalysisRequest
from services.analysis_pipeline import AnalysisPipeline
from services.llm_structured_client import (
    LLMInvalidOutputError,
    LLMStructuredCallError,
    StructuredCallResult,
)
from services.star_generator import (
    SYSTEM_PROMPT,
    StarDraftOutput,
    StarField,
    StarGenerationError,
    StarGenerator,
)

SHA_A = "a" * 40
SHA_B = "b" * 40
FAKE_TOKEN = "ghp_" + "x" * 30


class _FakeClient:
    """입력 payload를 기록하고 미리 정한 응답(또는 오류)을 순서대로 돌려준다."""

    def __init__(self, *responses: StarDraftOutput | Exception) -> None:
        self._responses = list(responses)
        self.calls: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens):
        self.calls.append({
            "system_prompt": system_prompt,
            "payload": payload,
            "output_model": output_model,
            "max_completion_tokens": max_completion_tokens,
        })
        response = self._responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return StructuredCallResult(output=response, prompt_tokens=10, completion_tokens=5, latency_ms=1)


def _field(status: str = "FILLED", text: str | None = "문장", shas: list[str] | None = None,
           reason: str | None = None) -> StarField:
    return StarField(status=status, text=text, evidence_shas=shas or [], insufficient_reason=reason)


def _empty(reason: str = "근거 없음") -> StarField:
    return _field("EMPTY", None, [], reason)


def _output(**fields: StarField) -> StarDraftOutput:
    return StarDraftOutput(**{slot: fields.get(slot, _empty()) for slot in ("S", "T", "A", "R")})


def _evidence(sha: str, **extra: Any) -> dict[str, Any]:
    return {
        "sha": sha,
        "message": f"feat: {sha[:7]} 변경\n\n본문",
        "author_login": "octo",
        "churn": "+10/-2",
        "summary": f"{sha[:7]} 커밋 요약",
        "url": f"https://github.com/o/r/commit/{sha}",
        **extra,
    }


def _request(*evidence: dict[str, Any], answers: list[str] | None = None, **extra: Any) -> StarAnalysisRequest:
    return StarAnalysisRequest.model_validate({
        "candidate_id": "c1",
        "target_login": "octo",
        "title": "JWT 로그인 구현",
        "source_type": "PR",
        "source_ref": "PR #12",
        "evidence": list(evidence),
        "confirmed_answers": answers or [],
        **extra,
    })


def _run(generator: StarGenerator, request: StarAnalysisRequest):
    return asyncio.run(generator.generate(request))


def test_builds_star_with_verified_evidence_and_missing_fields() -> None:
    client = _FakeClient(_output(
        S=_field("FILLED", "토큰 만료 시 401 대신 500이 반환됐다.", [SHA_B]),
        A=_field("FILLED", "JWT 필터에서 만료 예외를 401 JSON으로 바꿨다.", [SHA_A, SHA_B]),
        R=_empty("결과를 확인할 근거가 없다."),
    ))
    request = _request(_evidence(SHA_A), _evidence(SHA_B))

    result = _run(StarGenerator(client), request)

    assert result.title == "JWT 로그인 구현"
    assert result.star["A"].status == "FILLED"
    assert result.star["A"].confidence == "HIGH"
    assert [ref.sha for ref in result.star["A"].evidence] == [SHA_A, SHA_B]
    assert result.star["A"].evidence[0].message == f"feat: {SHA_A[:7]} 변경"
    assert result.star["A"].evidence[0].url == f"https://github.com/o/r/commit/{SHA_A}"
    assert result.star["R"].status == "EMPTY"
    assert result.star["R"].text is None
    assert result.star["R"].insufficient_reason == "결과를 확인할 근거가 없다."
    assert result.missing_fields == ["T", "R"]
    assert result.shared_with is None
    assert result.removed_claims == []
    call = client.calls[0]
    assert call["system_prompt"] == SYSTEM_PROMPT
    assert call["output_model"] is StarDraftOutput
    assert [item["sha"] for item in call["payload"]["evidence"]] == [SHA_A, SHA_B]


def test_drops_unknown_shas_and_removes_claims_without_any_evidence() -> None:
    client = _FakeClient(_output(
        S=_field("FILLED", "입력에 없는 커밋만 인용했다.", ["c" * 40]),
        A=_field("FILLED", "실제 커밋과 없는 커밋을 함께 인용했다.", [SHA_A, "d" * 40]),
    ))

    result = _run(StarGenerator(client), _request(_evidence(SHA_A)))

    # 근거가 하나도 남지 않은 문장은 화면에 뜨면 안 되므로 비운다.
    assert result.star["S"].status == "EMPTY"
    assert result.star["S"].text is None
    assert "입력에 없는 커밋만 인용했다." in result.removed_claims
    assert [ref.sha for ref in result.star["A"].evidence] == [SHA_A]
    assert result.missing_fields == ["S", "T", "R"]


def test_allows_text_backed_only_by_confirmed_answers() -> None:
    client = _FakeClient(_output(R=_field("FILLED", "배포 후 로그인 오류 문의가 사라졌다.", [])))

    result = _run(StarGenerator(client), _request(_evidence(SHA_A), answers=["배포 후 로그인 오류 문의가 사라졌어요"]))

    assert result.star["R"].status == "FILLED"
    assert result.star["R"].evidence == []
    assert result.removed_claims == []


def test_removes_claim_with_invented_metric() -> None:
    client = _FakeClient(_output(
        R=_field("FILLED", "응답 시간이 40% 줄었다.", [SHA_A]),
        A=_field("FILLED", "재시도를 3회로 제한했다.", [SHA_A]),
    ))
    request = _request(_evidence(SHA_A, summary="재시도 상한 상수 MAX_RETRY = 3 추가"))

    result = _run(StarGenerator(client), request)

    assert result.star["R"].status == "EMPTY"
    assert "응답 시간이 40% 줄었다." in result.removed_claims
    assert result.star["A"].status == "FILLED"


def test_maps_confidence_from_status_and_summary_source() -> None:
    client = _FakeClient(_output(
        S=_field("NEEDS_REVIEW", "상황 해석이 불확실하다.", [SHA_A]),
        A=_field("FILLED", "커밋 메시지로만 확인된 행동.", [SHA_B]),
    ))
    request = _request(_evidence(SHA_A), _evidence(SHA_B, summary_source="COMMIT_MESSAGE"))

    result = _run(StarGenerator(client), request)

    assert result.star["S"].confidence == "LOW"
    assert result.star["S"].review_reason
    assert result.star["A"].confidence == "MEDIUM"
    # NEEDS_REVIEW는 문장이 있으므로 빈 칸 목록에 넣지 않는다.
    assert result.missing_fields == ["T", "R"]


def test_reports_other_authors_without_excluding_their_commits() -> None:
    client = _FakeClient(_output(A=_field("FILLED", "필터를 추가했다.", [SHA_A, SHA_B])))
    request = _request(_evidence(SHA_A, author_login="Octo"), _evidence(SHA_B, author_login="teammate"))

    result = _run(StarGenerator(client), request)

    assert [ref.sha for ref in result.star["A"].evidence] == [SHA_A, SHA_B]
    assert result.shared_with == "teammate"


def test_empty_status_without_reason_gets_default_and_text_is_dropped() -> None:
    client = _FakeClient(_output(T=_field("EMPTY", "지워져야 할 문장", [SHA_A], None)))

    result = _run(StarGenerator(client), _request(_evidence(SHA_A)))

    assert result.star["T"].text is None
    assert result.star["T"].evidence == []
    assert result.star["T"].insufficient_reason


def test_filled_without_text_becomes_empty() -> None:
    client = _FakeClient(_output(A=_field("FILLED", "   ", [SHA_A])))

    result = _run(StarGenerator(client), _request(_evidence(SHA_A)))

    assert result.star["A"].status == "EMPTY"
    assert "A" in result.missing_fields


def test_clips_long_text_and_redacts_secrets() -> None:
    client = _FakeClient(_output(A=_field("FILLED", f"토큰 {FAKE_TOKEN} " + "가" * 400, [SHA_A])))
    request = _request(
        _evidence(SHA_A, message=f"chore: {FAKE_TOKEN}"),
        answers=[f"키는 {FAKE_TOKEN} 였어요"],
    )

    result = _run(StarGenerator(client), request)

    sent = repr(client.calls[0]["payload"])
    assert FAKE_TOKEN not in sent
    assert FAKE_TOKEN not in result.model_dump_json()
    assert len(result.star["A"].text) == 200


def test_dedupes_evidence_and_ignores_blank_answers() -> None:
    client = _FakeClient(_output())

    _run(StarGenerator(client), _request(_evidence(SHA_A), _evidence(SHA_A), answers=["  ", "답변"]))

    payload = client.calls[0]["payload"]
    assert len(payload["evidence"]) == 1
    assert payload["confirmed_answers"] == ["답변"]


def test_retries_only_invalid_output() -> None:
    client = _FakeClient(LLMInvalidOutputError("형식"), _output())

    _run(StarGenerator(client), _request(_evidence(SHA_A)))

    assert len(client.calls) == 2


def test_does_not_retry_other_llm_errors() -> None:
    client = _FakeClient(LLMStructuredCallError("rate limit"), _output())

    with pytest.raises(StarGenerationError):
        _run(StarGenerator(client), _request(_evidence(SHA_A)))

    assert len(client.calls) == 1


def test_requires_llm_client() -> None:
    with pytest.raises(StarGenerationError):
        _run(StarGenerator(), _request(_evidence(SHA_A)))


def test_pipeline_delegates_star_analysis_to_generator() -> None:
    client = _FakeClient(_output(A=_field("FILLED", "행동", [SHA_A])))
    pipeline = AnalysisPipeline()
    pipeline.star_generator = StarGenerator(client)

    result = asyncio.run(pipeline.analyze_star(_request(_evidence(SHA_A))))

    assert result.star["A"].status == "FILLED"

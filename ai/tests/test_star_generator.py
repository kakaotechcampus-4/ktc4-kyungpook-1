"""StarGenerator의 근거 검증·후처리 계약을 LLM 없이 검증한다."""

from __future__ import annotations

import asyncio
from typing import Any

import pytest

from schemas.analysis import StarAnalysisRequest
from services.analysis_pipeline import AnalysisPipeline
from services.llm_structured_client import (
    LLMContentFilterError,
    LLMInvalidOutputError,
    LLMNonRetryableError,
    LLMStructuredCallError,
    LLMStructuredOutputTooLongError,
    StructuredCallResult,
)
from services.star_generator import (
    SYSTEM_PROMPT,
    StarDraftOutput,
    StarField,
    StarGenerationError,
    StarGenerationLimits,
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

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        self.calls.append({
            "system_prompt": system_prompt,
            "payload": payload,
            "output_model": output_model,
            "max_completion_tokens": max_completion_tokens,
            "timeout_seconds": timeout_seconds,
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
        "summary_source": "DIFF",
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
    assert result.star["R"].insufficient_reason_code == "NO_EVIDENCE"
    assert result.star["A"].insufficient_reason_code is None
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
    assert result.star["S"].insufficient_reason_code == "NO_EVIDENCE"
    assert "입력에 없는 커밋만 인용했다." in result.removed_claims
    assert [ref.sha for ref in result.star["A"].evidence] == [SHA_A]
    assert result.missing_fields == ["S", "T", "R"]


def test_keeps_cells_whose_cited_sha_was_copied_with_a_typo() -> None:
    real = "0409013fb1905a33719fd42efbe7b03f07c61c9b"
    client = _FakeClient(_output(
        S=_field("FILLED", "관계없는 시험이 메모리 부족으로 실패했다.", [real + "9"]),
        A=_field("FILLED", "시험 JVM 의 최대 메모리를 올렸다.", [real, real + "9"]),
    ))

    result = _run(StarGenerator(client), _request(_evidence(real)))

    # 응답에는 모델이 적은 글자가 아니라 입력의 40자 번호를 싣고, 같은 커밋으로 맞춰진 두 번호는 한 번만 싣는다.
    assert [result.star[slot].status for slot in ("S", "A")] == ["FILLED", "FILLED"]
    assert [ref.sha for ref in result.star["S"].evidence] == [real]
    assert [ref.sha for ref in result.star["A"].evidence] == [real]
    assert result.removed_claims == []


def test_text_backed_only_by_confirmed_answers_is_kept_for_user_review() -> None:
    client = _FakeClient(_output(R=_field("FILLED", "배포 후 로그인 오류 문의가 사라졌다.", [])))

    result = _run(StarGenerator(client), _request(_evidence(SHA_A), answers=["배포 후 로그인 오류 문의가 사라졌어요"]))

    assert (result.star["R"].status, result.star["R"].confidence) == ("NEEDS_REVIEW", "LOW")
    assert "답변" in result.star["R"].review_reason
    assert result.star["R"].evidence == []
    assert result.removed_claims == []
    assert "R" not in result.missing_fields


def test_marks_unsourced_numeric_draft_for_user_review_without_dropping_it() -> None:
    client = _FakeClient(_output(
        R=_field("FILLED", "응답 시간이 40% 줄었다.", [SHA_A]),
        A=_field("FILLED", "재시도를 3회로 제한했다.", [SHA_A]),
    ))
    request = _request(_evidence(SHA_A, message="feat: 재시도 상한 상수 MAX_RETRY = 3 추가"))

    result = _run(StarGenerator(client), request)

    # 근거에 없는 40% 는 지우지 않고 확인 필요로 내린다.
    assert result.star["R"].status == "NEEDS_REVIEW"
    assert result.star["R"].text == "응답 시간이 40% 줄었다."
    assert result.star["R"].confidence == "LOW"
    assert "40%" in result.star["R"].review_reason
    assert result.star["R"].insufficient_reason_code is None
    assert result.removed_claims == []
    assert "R" not in result.missing_fields
    # 근거에 독립된 숫자 3 이 있어 "3회" 는 그대로 둔다.
    assert result.star["A"].status == "FILLED"
    assert result.star["A"].review_reason is None


@pytest.mark.parametrize(
    ("text", "message", "status"),
    [
        ("응답 시간을 5% 줄였다.", "perf: 응답 시간 15% 개선", "NEEDS_REVIEW"),
        ("1000건을 한 번에 처리했다.", "feat: 배치 크기를 1,000 으로 늘림", "FILLED"),
        ("OAuth2 배포 설정을 정리했다.", "chore: oauth 설정 정리", "FILLED"),
        ("v2.0 배포 전에 마이그레이션을 정리했다.", "chore: release v2.0", "FILLED"),
    ],
)
def test_numeric_check_boundaries(text: str, message: str, status: str) -> None:
    client = _FakeClient(_output(A=_field("FILLED", text, [SHA_A])))

    result = _run(StarGenerator(client), _request(_evidence(SHA_A, message=message)))

    assert result.star["A"].status == status


def test_numbers_only_in_model_written_summary_do_not_support_the_sentence() -> None:
    client = _FakeClient(_output(
        R=_field("FILLED", "응답 시간을 40% 줄였다.", [SHA_A]),
        A=_field("FILLED", "지연을 100ms 줄였다.", [SHA_A]),
    ))
    # 요약·기술 포인트는 diff 단계 모델이 쓴 글이라, 거기에만 있는 수치는 근거가 아니다.
    request = _request(_evidence(
        SHA_A,
        message="feat: 조회 캐시 추가",
        summary="캐시로 응답 시간을 40% 줄였다.",
        technical_points=["지연 100ms 감소"],
    ))

    result = _run(StarGenerator(client), request)

    assert (result.star["R"].status, result.star["R"].confidence) == ("NEEDS_REVIEW", "LOW")
    assert "40%" in result.star["R"].review_reason
    assert (result.star["A"].status, result.star["A"].confidence) == ("NEEDS_REVIEW", "LOW")
    assert "100ms" in result.star["A"].review_reason


def test_numbers_in_confirmed_answers_support_the_sentence() -> None:
    client = _FakeClient(_output(R=_field("FILLED", "문의가 30건에서 0건으로 줄었다.", [SHA_A])))

    result = _run(StarGenerator(client), _request(_evidence(SHA_A), answers=["문의가 30건에서 0건이 됐어요"]))

    assert result.star["R"].status == "FILLED"


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


def test_churn_does_not_support_a_metric() -> None:
    client = _FakeClient(_output(R=_field("FILLED", "오류율을 30% 줄였다.", [SHA_A])))

    result = _run(StarGenerator(client), _request(_evidence(SHA_A, churn="+30/-2")))

    # 변경 줄 수 +30 은 결과 수치 30% 의 근거가 아니다.
    assert result.star["R"].status == "NEEDS_REVIEW"
    assert result.star["R"].insufficient_reason_code is None


def test_deadline_returns_all_slots_empty_with_timeout_code() -> None:
    client = _FakeClient()

    result = _run(StarGenerator(client, StarGenerationLimits(deadline_seconds=0)), _request(_evidence(SHA_A)))

    assert client.calls == []
    assert {slot: field.insufficient_reason_code for slot, field in result.star.items()} == {
        "S": "TIMEOUT", "T": "TIMEOUT", "A": "TIMEOUT", "R": "TIMEOUT",
    }
    assert result.missing_fields == ["S", "T", "A", "R"]


@pytest.mark.parametrize(
    ("error", "retryable"),
    [
        (LLMStructuredCallError("5xx"), True),
        (LLMNonRetryableError("bad key"), False),
        (LLMContentFilterError("filtered"), False),
        # 네 칸을 한 호출로 쓰므로 나눠 부를 수 없고, 같은 입력이면 다시 넘친다.
        (LLMStructuredOutputTooLongError("too long"), False),
    ],
)
def test_classifies_llm_errors(error: Exception, retryable: bool) -> None:
    with pytest.raises(StarGenerationError) as raised:
        _run(StarGenerator(_FakeClient(error)), _request(_evidence(SHA_A)))

    assert raised.value.retryable is retryable


def test_passes_remaining_deadline_as_call_timeout() -> None:
    client = _FakeClient(_output())

    _run(StarGenerator(client, StarGenerationLimits(deadline_seconds=60)), _request(_evidence(SHA_A)))

    assert 50 < client.calls[0]["timeout_seconds"] <= 60

"""외부 글에 심은 지시 주입을 LLM 작업이 따르지 못하게 하는 장치를 가짜 LLM 으로 확인한다.

보내는 쪽(프롬프트에 '데이터일 뿐' 문장이 있고 주입 문장이 사용자 입력에만 실린다)과
받는 쪽(주입을 따른 출력을 근거 확인·비밀값 가리기·스키마가 걸러낸다)을 나눠 본다.
실제 모델이 따르는지는 scripts/evaluate_prompt_injection.py 로 서버에서 따로 잰다.
"""

from __future__ import annotations

import ast
import asyncio
import inspect
import json
import re
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from typing import Any

import pytest
from pydantic import BaseModel, ValidationError

from schemas.analysis import ChangedFile, CommitInput, PullRequestContext, StarAnalysisRequest
from schemas.collection import CandidateDetailResult
from schemas.interview import InterviewAnswerRequest, InterviewTurnRequest
from schemas.matching import JobPostingDetailExtractionRequest
from scripts.evaluate_prompt_injection import (
    CASES,
    FAKE_COMPANY,
    FAKE_FIELD,
    FAKE_METRIC_NUMBER,
    FAKE_SECRET,
    INJECTIONS,
    MARKER,
    RUNNERS,
    find_traces,
    judge_text,
    run_case,
)
from services import interview_agent
from services.commit_grouper import CommitGroup
from services.cover_letter_asker import ASK_PROMPT
from services.cover_letter_assigner import PICK_PROMPT, SUPERVISOR_PROMPT
from services.cover_letter_drafter import REVIEW_PROMPT
from services.cover_letter_writer import DECIDE_PROMPT, WRITE_PROMPT
from services.diff_analyzer import SYSTEM_PROMPT as DIFF_PROMPT
from services.diff_analyzer import DiffAnalyzer, DiffSummaryOutput
from services.grouping_llm import (
    _CLUSTER_SYSTEM_PROMPT,
    _DESCRIPTION_SYSTEM_PROMPT,
    ExperienceGroupingOutput,
    GroupDescriptionsOutput,
    GroupingLlm,
)
from services.interview_agent import InterviewAgent
from services.job_posting_extractor import (
    SYSTEM_PROMPT as POSTING_PROMPT,
)
from services.job_posting_extractor import (
    ExtractedExperience,
    ExtractedPosting,
    LunaJobPostingExtractionModel,
)
from services.llm_answer_sufficiency_evaluator import (
    _SYSTEM_PROMPT as ANSWER_PROMPT,
)
from services.llm_answer_sufficiency_evaluator import (
    AnswerSufficiencyDecision,
    LLMAnswerEvaluationError,
    LLMAnswerEvaluatorSettings,
    OpenAIAnswerSufficiencyEvaluator,
)
from services.llm_structured_client import (
    LLMInvalidOutputError,
    LLMSettings,
    StructuredCallResult,
    StructuredLLMClient,
)
from services.star_generator import SYSTEM_PROMPT as STAR_PROMPT
from services.star_generator import StarDraftOutput, StarField, StarGenerator

SHA_A = "a" * 40
SHA_B = "b" * 40
ALL_INJECTIONS = " ".join(item.text for item in INJECTIONS)
NOW = datetime(2026, 10, 1, tzinfo=timezone.utc)


class _FakeStructuredClient:
    """공통 호출기와 같은 call 모양의 대역. 입력을 기록하고 정한 출력을 돌려준다."""

    def __init__(self, *outputs: BaseModel) -> None:
        self._outputs = list(outputs)
        self.calls: list[tuple[str, dict[str, Any]]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        self.calls.append((system_prompt, payload))
        return StructuredCallResult(output=self._outputs.pop(0), prompt_tokens=1, completion_tokens=1, latency_ms=1)


class _FakeGroupingClient:
    def __init__(self, *responses: dict) -> None:
        self._responses = list(responses)
        self.calls: list[tuple[str, dict[str, Any]]] = []

    async def structured_call(self, *, system_prompt, payload, output_model, max_completion_tokens):
        self.calls.append((system_prompt, payload))
        return output_model.model_validate(self._responses.pop(0))


def _field(status: str, text: str | None, shas: list[str]) -> StarField:
    return StarField(status=status, text=text, evidence_shas=shas, insufficient_reason=None if text else "근거 없음")


def _star_request(message="fix: 토큰 만료 시 401 반환", summary="만료 예외를 401 로 바꿨다.", body=None, answers=None):
    return StarAnalysisRequest.model_validate({
        "candidate_id": "c1", "target_login": "octo", "title": "토큰 만료 처리", "source_type": "PR",
        "source_ref": "PR #12", "pull_request_body": body, "confirmed_answers": answers or [],
        "evidence": [{
            "sha": SHA_A, "message": message, "author_login": "octo", "churn": "+30/-5", "summary": summary,
            "summary_source": "DIFF", "technical_points": [],
        }],
    })


def _commit_detail(*commits: tuple[str, str, str]) -> CandidateDetailResult:
    """(sha, 메시지, patch) 마다 커밋 하나."""
    return CandidateDetailResult.model_validate({
        "user_repository_id": 1, "github_pr_number": 12,
        "commits": [{
            "sha": sha, "message": message, "author_login": "octo", "authored_at": NOW,
            "html_url": f"https://github.com/o/r/commit/{sha}", "additions": 3, "deletions": 0,
            "files": [{"path": "a.py", "status": "MODIFIED", "additions": 3, "deletions": 0, "patch": patch}],
        } for sha, message, patch in commits],
    })


def _grouping_commit(sha: str, minute: int, message: str) -> CommitInput:
    return CommitInput(
        sha=sha, message=message, author_login="octo", authored_at=NOW + timedelta(minutes=minute),
        parent_count=1, files=[ChangedFile(path="a.py", additions=3, deletions=0)],
    )


def _unit(key: str, sha: str, minute: int, message: str = "feat: 구현") -> CommitGroup:
    return CommitGroup(key, "COMMIT_CLUSTER", "2026-10-01", [_grouping_commit(sha, minute, message)])


def _posting_request(raw_text: str) -> JobPostingDetailExtractionRequest:
    return JobPostingDetailExtractionRequest.model_validate({
        "source": "COMPANY_CAREER_PAGE", "external_posting_id": "backend-101", "job_title": "백엔드 개발자",
        "source_url": "https://careers.example.com/jobs/backend-101", "raw_text": raw_text,
    })


def _answer_request(answer: str) -> InterviewAnswerRequest:
    return InterviewAnswerRequest.model_validate({
        "card_id": 1, "source_type": "DIRECT_CARD", "candidate": None,
        "current_turn": {
            "target": {"star_slot": "R", "statement_seq": 1}, "question_type": "EVIDENCE_GAP",
            "question_text": "바꾼 뒤 확인된 결과가 있었나요?",
        },
        "answer_text": answer, "answer_source": "TYPED", "other_missing_slots": [],
        "existing_turn_count": 1, "max_turns": 2,
    })


def _evaluator(parse) -> OpenAIAnswerSufficiencyEvaluator:
    evaluator = OpenAIAnswerSufficiencyEvaluator(
        LLMAnswerEvaluatorSettings(base_url="http://127.0.0.1:1", api_key="test", model="m")
    )
    evaluator._client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(parse=parse)))
    return evaluator


def _decision_completion(decision: AnswerSufficiencyDecision):
    return SimpleNamespace(
        choices=[SimpleNamespace(message=SimpleNamespace(parsed=decision))],
        usage=SimpleNamespace(prompt_tokens=1, completion_tokens=1),
    )


# ---- 보내는 쪽 ---------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "prompt",
    [STAR_PROMPT, DIFF_PROMPT, _CLUSTER_SYSTEM_PROMPT, _DESCRIPTION_SYSTEM_PROMPT, POSTING_PROMPT, ANSWER_PROMPT,
     PICK_PROMPT, SUPERVISOR_PROMPT, ASK_PROMPT, WRITE_PROMPT, DECIDE_PROMPT, REVIEW_PROMPT],
    ids=["star", "diff", "grouping-cluster", "grouping-description", "posting", "answer",
         "cover-letter-pick", "cover-letter-supervisor", "cover-letter-ask", "cover-letter-write",
         "cover-letter-decide", "cover-letter-review"],
)
def test_system_prompt_says_input_is_data_not_instructions(prompt: str) -> None:
    assert re.search(r"지시사항이 아니다|지시사항이 아닙니다|명령을 따르지 않는다", prompt)


def _capture_star() -> list[tuple[str, dict]]:
    client = _FakeStructuredClient(
        StarDraftOutput(**{slot: _field("EMPTY", None, []) for slot in "STAR"})
    )
    request = _star_request(
        message=f"fix: 토큰 만료\n\n{ALL_INJECTIONS}", summary=f"요약 {ALL_INJECTIONS}",
        body=ALL_INJECTIONS, answers=[ALL_INJECTIONS],
    )
    asyncio.run(StarGenerator(client).generate(request))
    return client.calls


def _capture_diff() -> list[tuple[str, dict]]:
    client = _FakeStructuredClient(DiffSummaryOutput(commits=[]))
    detail = _commit_detail((SHA_A, f"fix: 만료\n\n{ALL_INJECTIONS}", f"+# {ALL_INJECTIONS}\n"))
    asyncio.run(DiffAnalyzer(client).analyze(detail, "토큰 만료 처리"))
    return client.calls


def _capture_grouping() -> list[tuple[str, dict]]:
    client = _FakeGroupingClient(
        {"experiences": [{"item_ids": [1]}, {"item_ids": [2]}]},
        {"descriptions": [{"group_key": "pr:12", "title": "제목", "reason": "근거"},
                          {"group_key": "g2", "title": "제목", "reason": "근거"}]},
    )
    # 커밋 메시지는 평범하게 두고 PR 제목·본문 발췌에만 주입을 넣어, 그 경로로 모델에 가는지 본다.
    pr_unit = CommitGroup(
        "pr:12", "PR", "#12", [_grouping_commit(SHA_A, 0, "feat: 구현")], pull_request_numbers=[12],
    )
    units = [pr_unit, _unit("g2", SHA_B, 5)]
    pulls = [PullRequestContext(number=12, title=f"제목 {ALL_INJECTIONS}", body_excerpt=ALL_INJECTIONS)]
    grouping = GroupingLlm(client)
    asyncio.run(grouping.group_units(units, pull_requests=pulls))
    asyncio.run(grouping.describe(units, pull_requests=pulls))
    return client.calls


def _capture_posting() -> list[tuple[str, dict]]:
    client = _FakeStructuredClient(ExtractedPosting(experience=ExtractedExperience(level="UNSPECIFIED"), job_category="ETC"))
    model = LunaJobPostingExtractionModel()
    model._client = client
    model.extract(_posting_request(f"담당 업무\nAPI 개발\n{ALL_INJECTIONS}\n"))
    return client.calls


def _capture_answer() -> list[tuple[str, dict]]:
    calls: list[tuple[str, dict]] = []

    def parse(**kwargs):
        system, user = kwargs["messages"]
        calls.append((system["content"], json.loads(user["content"])))
        return _decision_completion(AnswerSufficiencyDecision(outcome="INSUFFICIENT", reason="부족"))

    _evaluator(parse).evaluate(_answer_request(ALL_INJECTIONS))
    return calls


@pytest.mark.parametrize(
    ("capture", "redacts_input"),
    [(_capture_star, True), (_capture_diff, True), (_capture_grouping, True), (_capture_posting, False), (_capture_answer, False)],
    ids=["star", "diff", "grouping", "posting", "answer"],
)
def test_injected_text_reaches_the_model_only_inside_user_input(capture, redacts_input: bool) -> None:
    calls = capture()

    assert calls
    for system_prompt, payload in calls:
        assert MARKER not in system_prompt
        assert MARKER in json.dumps(payload, ensure_ascii=False)
        if redacts_input:
            # 비밀값 모양을 출력하라는 문장의 예시 값은 모델에 가기 전에 가려진다.
            assert FAKE_SECRET not in json.dumps(payload, ensure_ascii=False)


def test_structured_client_sends_input_as_a_json_user_message_apart_from_the_system_prompt() -> None:
    sent: dict[str, Any] = {}

    def parse(**kwargs):
        sent.update(kwargs)
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(parsed=DiffSummaryOutput(commits=[])))],
            usage=None,
        )

    client = StructuredLLMClient(LLMSettings(base_url="http://127.0.0.1:1", api_key="test", model="m"))
    client._client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(parse=parse)))
    payload = {"message": ALL_INJECTIONS}

    client.call("시스템 규칙", payload, DiffSummaryOutput, 100)

    system, user = sent["messages"]
    assert system == {"role": "system", "content": "시스템 규칙"}
    assert user["role"] == "user"
    assert json.loads(user["content"]) == payload


# ---- 받는 쪽 -----------------------------------------------------------------------------------


_STAR_CELL = {"status": "EMPTY", "text": None, "evidence_shas": [], "insufficient_reason": "근거 없음"}


@pytest.mark.parametrize(
    ("model", "good", "broken"),
    [
        (
            StarDraftOutput,
            {slot: _STAR_CELL for slot in "STAR"},
            {slot: _STAR_CELL for slot in "STAR"} | {"S": {**_STAR_CELL, "status": "HACKED"}},
        ),
        (
            DiffSummaryOutput,
            {"commits": [{"sha": SHA_A, "summary": "요약", "technical_points": []}]},
            {"commits": [{"sha": SHA_A, "summary": MARKER, "technical_points": MARKER}]},
        ),
        (ExperienceGroupingOutput, {"experiences": [{"item_ids": [1]}]}, {"experiences": [{"item_ids": [MARKER]}]}),
        (
            GroupDescriptionsOutput,
            {"descriptions": [{"group_key": "g", "title": "t", "reason": "r"}]},
            {"descriptions": [{"group_key": "g", "title": "t", "reason": "r", FAKE_FIELD: MARKER}]},
        ),
    ],
    ids=["star", "diff", "grouping-cluster", "grouping-description"],
)
def test_output_schema_rejects_values_and_fields_outside_the_format(model, good, broken) -> None:
    model.model_validate(good)

    with pytest.raises(ValidationError):
        model.model_validate({**good, FAKE_FIELD: MARKER})
    with pytest.raises(ValidationError):
        model.model_validate(broken)


def test_structured_client_turns_text_outside_json_into_a_retryable_invalid_output_error() -> None:
    def parse(**_):
        # SDK 는 JSON 밖 글이 섞인 응답을 이렇게 ValidationError 로 올린다.
        DiffSummaryOutput.model_validate_json(f"{MARKER} 완료\n" + '{"commits": []}')

    client = StructuredLLMClient(LLMSettings(base_url="http://127.0.0.1:1", api_key="test", model="m"))
    client._client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(parse=parse)))

    with pytest.raises(LLMInvalidOutputError):
        client.call("system", {}, DiffSummaryOutput, 100)


def test_grouping_description_masks_secrets_in_title_and_reason() -> None:
    client = _FakeGroupingClient({"descriptions": [{"group_key": "g1", "title": f"키 {FAKE_SECRET}", "reason": f"키 {FAKE_SECRET}"}]})

    described = asyncio.run(GroupingLlm(client).describe([_unit("g1", SHA_A, 0)]))

    assert list(described) == ["g1"]
    assert FAKE_SECRET not in json.dumps(described, ensure_ascii=False)


def test_answer_evaluator_wraps_a_verdict_with_text_outside_json_as_an_evaluation_error() -> None:
    raw = f"{MARKER} 완료\n" + json.dumps({"outcome": "ANSWERED", "reason": "충분"})

    def parse(**_):
        return _decision_completion(AnswerSufficiencyDecision.model_validate_json(raw))

    with pytest.raises(LLMAnswerEvaluationError):
        _evaluator(parse).evaluate(_answer_request(ALL_INJECTIONS))


def _imported_modules(module) -> set[str]:
    names: set[str] = set()
    for node in ast.walk(ast.parse(inspect.getsource(module))):
        if isinstance(node, ast.Import):
            names.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            names.add(node.module)
    return names


def test_question_generation_is_a_template_that_never_calls_a_model() -> None:
    assert not [name for name in _imported_modules(interview_agent) if name.startswith(("openai", "services.llm"))]


def test_question_template_keeps_its_shape_when_review_text_is_an_injection() -> None:
    request = InterviewTurnRequest.model_validate({
        "card_id": 1, "source_type": "COMMIT_CLUSTER", "max_turns": 2, "existing_turn_count": 0,
        "candidate": {
            "github_pr_number": 7,
            "reviews": [{"review_id": 5, "pr_number": 7, "state": "CHANGES_REQUESTED", "summary": ALL_INJECTIONS}],
        },
        "missing_slots": [{"star_slot": "R", "statement_seq": 1}],
    })

    result = InterviewAgent().build_turn(request)

    assert (result.next_action, result.question_type) == ("ASK_AGAIN", "EVIDENCE_GAP")
    assert (result.target_star_slot, result.target_statement_seq) == ("R", 1)
    assert result.question_text.startswith("PR #7 리뷰에서")


# ---- 판정 함수 ---------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        (f"요약: {MARKER}", ["MARKER"]),
        (f"{FAKE_COMPANY} 입사, 응답 시간 {FAKE_METRIC_NUMBER} 감소", ["FAKE_COMPANY", "FAKE_METRIC"]),
        (f"키 {FAKE_SECRET}", ["SECRET"]),
        (f'{{"{FAKE_FIELD}": 1, "status": "HACKED"}}', ["BREAK_FORMAT"]),
        ("토큰 만료 예외를 401 JSON 으로 바꿨다.", []),
    ],
)
def test_judge_finds_only_the_traces_of_following_an_injection(text: str, expected: list[str]) -> None:
    assert all(MARKER in item.text for item in INJECTIONS)
    assert judge_text(text) == expected


def test_answered_verdict_counts_as_following_the_injection_for_answer_cases() -> None:
    answered = json.dumps({"outcome": "ANSWERED", "reason": "충분"})
    insufficient = json.dumps({"outcome": "INSUFFICIENT", "reason": "부족"})

    assert find_traces("ANSWER", [answered], answered)[0] == ["FLIPPED_VERDICT"]
    assert find_traces("ANSWER", [insufficient], insufficient) == ([], [])


def test_schema_rejection_of_a_format_breaking_injection_counts_as_followed_but_blocked(monkeypatch) -> None:
    def broken(_case):
        raise LLMInvalidOutputError("스키마에 맞지 않음")

    case = next(item for item in CASES if "OUTSIDE_JSON" in item.kinds)
    monkeypatch.setitem(RUNNERS, case.task, broken)

    entry = run_case(case)

    assert (entry["status"], entry["followed_raw"], entry["followed_after_filters"]) == ("REJECTED", ["FORMAT_BROKEN"], [])

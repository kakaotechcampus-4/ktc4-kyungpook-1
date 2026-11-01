"""자소서 카드 고르기: 문항마다 담당이 고르고, 감독이 겹침·균형을 정하며, 카드 번호·겹침 규칙은 코드가 지킨다."""

from __future__ import annotations

import asyncio
import threading
import time
from typing import Any, Optional

import pytest

from schemas.cover_letter import AssignmentRequest
from services.cover_letter_assigner import (
    ConsideredOutput,
    CoverLetterAssigner,
    DecisionOutput,
    Pick,
    PickOutput,
    SupervisorOutput,
    resolve_overlaps,
)
from services.cover_letter_llm import CoverLetterLimits
from services.llm_settings import LLMConfigError
from services.llm_structured_client import LLMStructuredCallError, StructuredCallResult

CARDS = [
    {"card_id": 1, "title": "API 모양 갈등 조율", "card_kind": "QUALITATIVE", "source_name": "gitory",
     "themes": ["CONFLICT"], "summary": "API 응답 모양을 두고 갈린 의견을 회의로 조율했다"},
    {"card_id": 2, "title": "배포 시간 단축", "source_name": "gitory", "summary": "배포 시간을 40분에서 12분으로 줄였다"},
    {"card_id": 3, "title": "결제 오류 수정", "source_name": "shop", "summary": "결제 중복 요청을 막았다"},
    {"card_id": 4, "title": "동아리 회장", "card_kind": "QUALITATIVE", "source_name": "코딩 동아리",
     "summary": "동아리 회장으로 스터디를 운영했다"},
]


class RoutedClient:
    """문항별 호출은 동시에 돌아 순서가 정해지지 않으므로, 문항 문장으로 정해 둔 답(또는 예외)을 차례로 돌려준다."""

    def __init__(self, picks: dict[str, list[Any]], supervisor: Optional[list[Any]] = None) -> None:
        self._picks = {text: list(answers) for text, answers in picks.items()}
        self._supervisor = list(supervisor or [])
        self._lock = threading.Lock()
        self.pick_payloads: list[dict[str, Any]] = []
        self.supervisor_payloads: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        with self._lock:
            if output_model is SupervisorOutput:
                self.supervisor_payloads.append(payload)
                answer = self._supervisor.pop(0)
            else:
                self.pick_payloads.append(payload)
                answer = self._picks[payload["question"]["text"]].pop(0)
        if isinstance(answer, Exception):
            raise answer
        return StructuredCallResult(output=answer, prompt_tokens=1, completion_tokens=1, latency_ms=1)


def _request(*questions: str, cards: Optional[list[dict]] = None) -> AssignmentRequest:
    return AssignmentRequest.model_validate({
        "cover_letter_id": 9,
        "job_role": "백엔드 개발",
        "questions": [{"question_id": index + 1, "text": text, "char_limit": 700} for index, text in enumerate(questions)],
        "cards": CARDS if cards is None else cards,
    })


def _pick(first: Optional[int], second: Optional[int] = None, *, supporting: Optional[int] = None,
          theme: str = "COLLABORATION", considered: Optional[list[int]] = None,
          note: Optional[str] = None, reason: Optional[str] = None,
          fits: Optional[dict[int, str]] = None) -> PickOutput:
    ids = considered if considered is not None else list(dict.fromkeys(card for card in (first, second) if card is not None))
    return PickOutput(
        question_theme=theme,
        considered=[ConsideredOutput(card_id=card, note=note or "견줄 만함", fit=(fits or {}).get(card, "MEDIUM"))
                    for card in ids],
        first_card_id=first,
        first_reason=None if first is None else (reason or "가장 맞음"),
        second_card_id=second,
        second_reason=None if second is None else "다음으로 맞음",
        supporting_card_id=supporting,
    )


def _decide(*choices: tuple[int, str]) -> SupervisorOutput:
    return SupervisorOutput(decisions=[
        DecisionOutput(question_id=qid, choice=choice, reason=None if choice == "FIRST" else "다른 문항에 더 맞음")
        for qid, choice in choices
    ])


def _assign(client: RoutedClient, request: AssignmentRequest, limits: Optional[CoverLetterLimits] = None):
    return asyncio.run(CoverLetterAssigner(client, client, limits).assign(request))


def _steps(result) -> list[tuple]:
    return [(step.seq, step.actor, step.kind, step.question_id, step.card_id) for step in result.steps]


def test_single_question_keeps_sub_agent_choice_without_supervisor() -> None:
    """문항이 하나면 감독을 부르지 않고 담당이 고른 1순위가 배정된다."""
    client = RoutedClient({"협업 경험": [_pick(1, 2)]})

    result, ended_by = _assign(client, _request("협업 경험"))

    assignment = result.assignments[0]
    assert (assignment.primary_card_id, assignment.reason, assignment.decided_by) == (1, "가장 맞음", "SUB_AGENT")
    assert (client.supervisor_payloads, ended_by) == ([], "COMPLETED")


def test_supervisor_is_skipped_when_its_only_valid_answer_is_keep_all() -> None:
    """겹침도 없고 2순위가 있는 문항도 없으면 감독이 바꿀 수 있는 게 없어 부르지 않는다."""
    client = RoutedClient({"협업 경험": [_pick(1)], "문제 해결 경험": [_pick(2)]})

    result, _ = _assign(client, _request("협업 경험", "문제 해결 경험"))

    assert [(a.primary_card_id, a.decided_by) for a in result.assignments] == [(1, "SUB_AGENT"), (2, "SUB_AGENT")]
    assert client.supervisor_payloads == []


def test_supervisor_confirms_distinct_choices() -> None:
    """두 문항 이상이 카드를 고르고 바꿀 여지(2순위)가 있으면 감독이 확인하고, 그대로 두면 1순위가 배정된다."""
    client = RoutedClient({"협업 경험": [_pick(1, 4)], "문제 해결 경험": [_pick(2, 3)]},
                          [_decide((1, "FIRST"), (2, "FIRST"))])

    result, ended_by = _assign(client, _request("협업 경험", "문제 해결 경험"))

    assert [(a.primary_card_id, a.decided_by) for a in result.assignments] == [(1, "SUPERVISOR"), (2, "SUPERVISOR")]
    assert _steps(result) == [
        (1, "SUB_AGENT", "PICKED", 1, 1), (2, "SUB_AGENT", "PICKED", 2, 2), (3, "SUPERVISOR", "CHECKED", None, None),
    ]
    assert ended_by == "COMPLETED"


def test_supervisor_moves_overlapping_question_to_second_choice() -> None:
    """같은 카드를 고른 문항은 감독이 2순위로 옮기고, 바꾼 이유는 과정에, 2순위를 고른 이유는 배정에 남는다."""
    client = RoutedClient({"협업 경험": [_pick(1)], "문제 해결 경험": [_pick(1, 2)]},
                          [_decide((1, "FIRST"), (2, "SECOND"))])

    result, _ = _assign(client, _request("협업 경험", "문제 해결 경험"))

    assert [a.primary_card_id for a in result.assignments] == [1, 2]
    assert result.assignments[1].reason == "다음으로 맞음"
    assert _steps(result)[-1] == (4, "SUPERVISOR", "SWITCHED", 2, 2)
    assert result.steps[-1].reason == "다른 문항에 더 맞음"


@pytest.mark.parametrize(
    "broken",
    [
        pytest.param(_decide((1, "FIRST"), (2, "FIRST")), id="중심 카드가 겹친 채로 둠"),
        pytest.param(_decide((1, "FIRST"), (2, "NONE")), id="쓸 수 있는 2순위가 있는데 비움"),
        pytest.param(_decide((1, "SECOND"), (2, "SECOND")), id="2순위가 없는 문항을 2순위로"),
        pytest.param(_decide((1, "FIRST")), id="문항 하나를 빠뜨림"),
        pytest.param(SupervisorOutput(decisions=[DecisionOutput(question_id=1, choice="FIRST"),
                                                 DecisionOutput(question_id=2, choice="SECOND")]), id="바꾼 이유가 없음"),
        pytest.param(SupervisorOutput(decisions=[DecisionOutput(question_id=1, choice="FIRST"),
                                                 DecisionOutput(question_id=2, choice="SECOND", reason="가" * 81)]),
                     id="이유가 너무 김"),
    ],
)
def test_rule_breaking_supervisor_answer_is_asked_again(broken: SupervisorOutput) -> None:
    """감독 답이 배정 규칙을 어기면 한 번 더 묻고, 규칙에 맞는 답을 쓴다."""
    client = RoutedClient({"협업 경험": [_pick(1)], "문제 해결 경험": [_pick(1, 2)]},
                          [broken, _decide((1, "FIRST"), (2, "SECOND"))])

    result, _ = _assign(client, _request("협업 경험", "문제 해결 경험"))

    assert [(a.primary_card_id, a.decided_by) for a in result.assignments] == [(1, "SUPERVISOR"), (2, "SUPERVISOR")]
    assert len(client.supervisor_payloads) == 2


def test_code_moves_overlapping_question_to_second_choice_when_supervisor_fails() -> None:
    """감독 답을 못 받으면 코드가 겹친 문항을 2순위로 옮기고, 그 과정을 남긴다."""
    client = RoutedClient({"협업 경험": [_pick(1)], "문제 해결 경험": [_pick(1, 2)]},
                          [LLMStructuredCallError("실패")])

    result, ended_by = _assign(client, _request("협업 경험", "문제 해결 경험"))

    assert [(a.primary_card_id, a.decided_by) for a in result.assignments] == [(1, "CODE"), (2, "CODE")]
    assert _steps(result)[2:] == [(3, "SUPERVISOR", "FAILED", None, None), (4, "CODE", "RESOLVED", 2, 2)]
    assert ended_by == "FALLBACK"


def _p(qid: int, first: Optional[int], second: Optional[int] = None) -> Pick:
    return Pick(question_id=qid, theme="COLLABORATION", considered=[], first=first, first_reason=None,
                second=second, second_reason=None, supporting=None)


@pytest.mark.parametrize(
    ("picks", "expected"),
    [
        pytest.param([_p(1, 1, 2), _p(2, 1)], {1: 2, 2: 1}, id="2순위가 있는 문항이 물러난다"),
        pytest.param([_p(1, 1, 2), _p(2, 1, 3)], {1: 1, 2: 3}, id="둘 다 2순위가 있으면 뒤 문항이 물러난다"),
        pytest.param([_p(1, 1), _p(2, 1)], {1: 1, 2: None}, id="2순위가 없으면 뒤 문항이 비워진다"),
        pytest.param([_p(1, 1, 2), _p(2, 1), _p(3, 2)], {1: 1, 2: None, 3: 2}, id="다른 문항이 쓰는 2순위로는 옮기지 않는다"),
    ],
)
def test_code_resolution_rules(picks: list[Pick], expected: dict) -> None:
    """감독 답이 없을 때 코드가 겹침을 정리하는 규칙."""
    assert resolve_overlaps(picks) == expected


@pytest.mark.parametrize(
    "broken",
    [
        pytest.param(_pick(99), id="입력에 없는 카드"),
        pytest.param(_pick(1, considered=[2]), id="1순위가 견준 카드에 없음"),
        pytest.param(_pick(1, 1), id="1순위와 2순위가 같음"),
        pytest.param(_pick(None, 2), id="1순위 없이 2순위"),
        pytest.param(_pick(1, considered=[1, 2, 3, 4]), id="견준 카드가 4장"),
        pytest.param(_pick(1, note="가" * 81), id="평이 너무 김"),
        pytest.param(PickOutput(question_theme="COLLABORATION", considered=[ConsideredOutput(card_id=1, note="평", fit="MEDIUM")],
                                first_card_id=1), id="1순위 이유가 없음"),
        pytest.param(PickOutput(question_theme="COLLABORATION",
                                considered=[ConsideredOutput(card_id=1, note="평", fit="MEDIUM"), ConsideredOutput(card_id=2, note="평", fit="MEDIUM")],
                                first_card_id=1, first_reason="맞음", second_card_id=2), id="2순위 이유가 없음"),
        pytest.param(_pick(1, supporting=1), id="1순위와 보조가 같음"),
        pytest.param(_pick(1, note=" "), id="평이 비었음"),
    ],
)
def test_rule_breaking_pick_is_asked_again(broken: PickOutput) -> None:
    """담당 답이 카드 규칙을 어기면 한 번 더 묻고, 규칙에 맞는 답을 쓴다."""
    client = RoutedClient({"협업 경험": [broken, _pick(3)]})

    result, _ = _assign(client, _request("협업 경험"))

    assert result.assignments[0].primary_card_id == 3
    assert len(client.pick_payloads) == 2


def test_question_without_usable_pick_is_failed_and_others_continue() -> None:
    """두 번 다 규칙을 어긴 문항은 카드 없이 FAILED 로 돌려주고, 다른 문항은 그대로 배정된다."""
    client = RoutedClient({"협업 경험": [_pick(99), _pick(99)], "문제 해결 경험": [_pick(2)]})

    result, ended_by = _assign(client, _request("협업 경험", "문제 해결 경험"))

    failed, picked = result.assignments
    assert (failed.primary_card_id, failed.question_theme, failed.decided_by) == (None, None, "FAILED")
    assert (picked.primary_card_id, picked.decided_by) == (2, "SUB_AGENT")
    assert (client.supervisor_payloads, ended_by) == ([], "PARTIAL")


def test_all_questions_fail_when_llm_is_not_configured() -> None:
    """LLM 설정이 없으면 모든 문항을 FAILED 로 돌려주고 감독은 부르지 않는다."""
    client = RoutedClient({"협업 경험": [LLMConfigError("없음")], "문제 해결 경험": [LLMConfigError("없음")]})

    result, ended_by = _assign(client, _request("협업 경험", "문제 해결 경험"))

    assert [a.decided_by for a in result.assignments] == ["FAILED", "FAILED"]
    assert [step.kind for step in result.steps] == ["FAILED", "FAILED"]
    assert (client.supervisor_payloads, ended_by) == ([], "FALLBACK")


def test_text_with_unsourced_number_is_dropped() -> None:
    """카드 요약에 없는 수치가 든 평·이유는 화면에 지어낸 수치가 뜨므로 버리고, 요약에 있는 수치는 둔다."""
    pick = PickOutput(
        question_theme="PROBLEM_SOLVING",
        considered=[ConsideredOutput(card_id=2, note="40분에서 12분으로 줄임", fit="MEDIUM"),
                    ConsideredOutput(card_id=3, note="응답 30% 단축", fit="MEDIUM")],
        first_card_id=2, first_reason="배포를 50% 줄임", second_card_id=3, second_reason="중복 요청을 막음",
    )
    client = RoutedClient({"문제 해결 경험": [pick]})

    result, _ = _assign(client, _request("문제 해결 경험"))

    assignment = result.assignments[0]
    assert [c.note for c in assignment.considered] == ["40분에서 12분으로 줄임", None]
    assert (assignment.reason, result.steps[0].reason) == (None, None)


def test_text_borrowing_numbers_from_another_card_is_dropped() -> None:
    """카드 평·이유의 수치는 그 카드 요약에만 있어야 한다. 다른 카드의 '40분에서 12분' 을 빌려 쓴 평은 버린다."""
    pick = PickOutput(
        question_theme="PROBLEM_SOLVING",
        considered=[ConsideredOutput(card_id=3, note="배포를 40분에서 12분으로 줄임", fit="MEDIUM")],
        first_card_id=3, first_reason="결제 시간을 12분 줄임",
    )
    client = RoutedClient({"문제 해결 경험": [pick]})

    result, _ = _assign(client, _request("문제 해결 경험"))

    assert (result.assignments[0].considered[0].note, result.assignments[0].reason) == (None, None)


def test_supervisor_reason_with_unsourced_number_is_dropped() -> None:
    """감독이 바꾼 이유에 두 카드 요약·메모에 없는 수치가 있으면 과정에 남기지 않는다."""
    switch = SupervisorOutput(decisions=[DecisionOutput(question_id=1, choice="FIRST"),
                                         DecisionOutput(question_id=2, choice="SECOND", reason="응답 30% 차이")])
    client = RoutedClient({"협업 경험": [_pick(1)], "문제 해결 경험": [_pick(1, 2)]}, [switch])

    result, _ = _assign(client, _request("협업 경험", "문제 해결 경험"))

    assert (result.steps[-1].kind, result.steps[-1].reason) == ("SWITCHED", None)


def test_supervisor_may_empty_question_whose_card_another_kept() -> None:
    """두 문항이 같은 카드를 1순위로 고르고 둘 다 2순위가 없으면, 감독이 한 문항을 비우는 답은 받아들인다."""
    client = RoutedClient({"협업 경험": [_pick(1)], "문제 해결 경험": [_pick(1)]}, [_decide((1, "FIRST"), (2, "NONE"))])

    result, _ = _assign(client, _request("협업 경험", "문제 해결 경험"))

    assert [(a.primary_card_id, a.decided_by) for a in result.assignments] == [(1, "SUPERVISOR"), (None, "SUPERVISOR")]
    assert _steps(result)[-1] == (4, "SUPERVISOR", "SWITCHED", 2, None)


def test_supervisor_cannot_empty_question_whose_card_was_taken_as_second_choice() -> None:
    """다른 문항이 2순위로 이 문항의 1순위를 가져가며 이 문항을 비우면 카드 하나가 버려지므로 받아들이지 않는다."""
    taken = _decide((1, "NONE"), (2, "SECOND"))
    client = RoutedClient({"협업 경험": [_pick(1)], "문제 해결 경험": [_pick(2, 1)]}, [taken, taken])

    result, _ = _assign(client, _request("협업 경험", "문제 해결 경험"))

    assert [(a.primary_card_id, a.decided_by) for a in result.assignments] == [(1, "CODE"), (2, "CODE")]


def test_supporting_card_that_became_primary_is_dropped() -> None:
    """2순위로 옮긴 카드가 보조 카드와 같으면 보조 카드를 비운다."""
    client = RoutedClient({"협업 경험": [_pick(1)], "문제 해결 경험": [_pick(1, 3, supporting=3)]},
                          [_decide((1, "FIRST"), (2, "SECOND"))])

    result, _ = _assign(client, _request("협업 경험", "문제 해결 경험"))

    assert (result.assignments[1].primary_card_id, result.assignments[1].supporting_card_id) == (3, None)


def test_only_questions_with_a_card_go_to_supervisor() -> None:
    """맞는 카드가 없는 문항은 감독에게 보내지 않고 NO_FIT 으로 남는다."""
    client = RoutedClient(
        {"지원 동기": [_pick(None, theme="MOTIVATION")], "협업 경험": [_pick(1, 3)], "문제 해결 경험": [_pick(2, 4)]},
        [_decide((2, "FIRST"), (3, "FIRST"))],
    )

    result, _ = _assign(client, _request("지원 동기", "협업 경험", "문제 해결 경험"))

    assert [q["question_id"] for q in client.supervisor_payloads[0]["questions"]] == [2, 3]
    assert (result.assignments[0].question_theme, result.steps[0].kind) == ("MOTIVATION", "NO_FIT")


def test_memo_email_is_masked_before_llm() -> None:
    """메모의 이메일은 가려서 LLM 에 보낸다."""
    client = RoutedClient({"협업 경험": [_pick(1)]})
    request = AssignmentRequest.model_validate({**_request("협업 경험").model_dump(mode="json"),
                                                "memo": "연락은 me@example.com 으로"})

    _assign(client, request)

    assert "me@example.com" not in client.pick_payloads[0]["memo"]


class SlowRoutedClient(RoutedClient):
    """정해 둔 문항의 카드 고르기만 늦게 답한다."""

    def __init__(self, slow: dict[str, float], *args: Any) -> None:
        super().__init__(*args)
        self._slow = slow

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        if output_model is not SupervisorOutput:
            time.sleep(self._slow.get(payload["question"]["text"], 0))
        return super().call(system_prompt, payload, output_model, max_completion_tokens, timeout_seconds)


def test_slow_question_does_not_take_the_supervisor_time() -> None:
    """한 문항의 카드 고르기가 늦어도 감독 몫 시간을 남기고 끊어, 감독은 나머지 문항을 정리한다."""
    limits = CoverLetterLimits(pick_deadline_seconds=3.0, supervisor_reserve_seconds=1.5, min_call_seconds=0.2)
    client = SlowRoutedClient(
        {"협업 경험": 6.0},
        {"협업 경험": [_pick(4)], "문제 해결 경험": [_pick(1, 2)], "성장 경험": [_pick(1, 3)]},
        [_decide((2, "FIRST"), (3, "SECOND"))],
    )

    result, _ = _assign(client, _request("협업 경험", "문제 해결 경험", "성장 경험"), limits)

    assert [(a.primary_card_id, a.decided_by) for a in result.assignments] == [
        (None, "FAILED"), (1, "SUPERVISOR"), (3, "SUPERVISOR"),
    ]

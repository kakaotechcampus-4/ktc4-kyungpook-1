"""자소서 초안: 문항마다 쓰고 검사에 걸리면 고치거나 표시하며(최대 2번), 다 끝나면 감독이 함께 읽고 걸린 문항만 한 번 더 쓴다."""

from __future__ import annotations

import asyncio
import threading
import time
from typing import Any, Optional

import pytest

from schemas.cover_letter import DraftsRequest
from services.cover_letter_check import blank
from services.cover_letter_drafter import CoverLetterDrafter, FindingOutput, ReviewOutput
from services.cover_letter_llm import CoverLetterLimits
from services.cover_letter_writer import (
    DecideOutput,
    DecisionItem,
    ReadinessOutput,
    SupportedOutput,
    WriteOutput,
    length_cap,
)
from cover_letter_fakes import all_evidence
from services.llm_settings import LLMConfigError
from services.llm_structured_client import LLMStructuredCallError, StructuredCallResult

DEPLOY_CARD = {
    "card_id": 1, "title": "배포 시간 단축", "source_name": "gitory",
    "statements": [
        {"star_slot": "S", "text": "배포 한 번에 40분이 걸렸다"},
        {"star_slot": "A", "text": "k8s 로 옮기고 바뀐 모듈만 다시 빌드했다"},
        {"star_slot": "R", "text": "배포 시간을 12분으로 줄였다"},
    ],
}
API_CARD = {
    "card_id": 2, "title": "API 모양 조율", "card_kind": "QUALITATIVE", "source_name": "gitory",
    "statements": [
        {"star_slot": "S", "text": "API 응답 모양을 두고 의견이 갈렸다"},
        {"star_slot": "A", "text": "회의를 열어 두 안을 견줬다"},
        {"star_slot": "R", "text": "한 가지 모양으로 정했다"},
    ],
}
GOOD = "배포 시간을 40분에서 12분으로 줄였습니다. 바뀐 모듈만 다시 빌드했습니다."
GOOD_API = "API 응답 모양을 두고 갈린 의견을 회의로 견줬습니다. 한 가지 모양으로 정했습니다."
NO_REVIEW_FINDINGS = ReviewOutput(findings=[])


class DraftClient:
    """문항별 쓰기는 동시에 돌아 순서가 정해지지 않으므로 문항 문장으로 답을 고르고, 판단은 종류별로 차례로 돌려준다."""

    def __init__(self, writes: dict[str, list[Any]], decides: Optional[list[Any]] = None,
                 reviews: Optional[list[Any]] = None, readiness: Optional[dict[str, Any]] = None) -> None:
        self._writes = {text: list(answers) for text, answers in writes.items()}
        self._decides = list(decides or [])
        self._reviews = list(reviews or [])
        self._readiness = readiness or {}
        self._lock = threading.Lock()
        self.readiness_payloads: list[dict[str, Any]] = []
        self.write_payloads: list[dict[str, Any]] = []
        self.decide_payloads: list[dict[str, Any]] = []
        self.review_payloads: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        with self._lock:
            if output_model is ReadinessOutput:
                self.readiness_payloads.append(payload)
                answer = self._readiness.get(payload["question"]["text"], all_evidence(payload))
            elif output_model is WriteOutput:
                self.write_payloads.append(payload)
                answer = self._writes[payload["question"]["text"]].pop(0)
            elif output_model is DecideOutput:
                self.decide_payloads.append(payload)
                answer = self._decides.pop(0)
            else:
                self.review_payloads.append(payload)
                answer = self._reviews.pop(0)
        if isinstance(answer, Exception):
            raise answer
        if isinstance(answer, str):
            answer = WriteOutput(text=answer)
        return StructuredCallResult(output=answer, prompt_tokens=1, completion_tokens=1, latency_ms=1)

    def writes_for(self, question: str) -> list[dict[str, Any]]:
        return [payload for payload in self.write_payloads if payload["question"]["text"] == question]


def _question(text: str = "문제 해결 경험", *, qid: int = 1, card: Optional[dict] = DEPLOY_CARD, limit: int = 700,
              theme: str = "PROBLEM_SOLVING", missing: tuple = (), answers: Optional[list[dict]] = None) -> dict:
    return {"question_id": qid, "text": text, "char_limit": limit, "question_theme": theme, "primary_card": card,
            "answers": answers or [], "missing_items": list(missing)}


def _request(*questions: dict, memo: Optional[str] = None) -> DraftsRequest:
    return DraftsRequest.model_validate({"cover_letter_id": 9, "job_role": "백엔드 개발", "memo": memo,
                                         "questions": list(questions)})


#: 글자 수 목표와 상관없는 시험은 짧은 초안으로 쓰므로 목표 비율을 0 으로 둔다
NO_TARGET = CoverLetterLimits(target_ratio=0.0)


def _draft(client: DraftClient, request: DraftsRequest, limits: Optional[CoverLetterLimits] = None):
    return asyncio.run(CoverLetterDrafter(client, client, limits or NO_TARGET).draft(request))


def _fix(*actions: str, reason: Optional[str] = None) -> DecideOutput:
    return DecideOutput(decisions=[DecisionItem(index=index, action=action, reason=reason)
                                   for index, action in enumerate(actions, start=1)])


def _kinds(result, question_id: Optional[int] = None) -> list[str]:
    return [step.kind for step in result.steps if question_id is None or step.question_id == question_id]


def test_draft_that_passes_check_is_kept_as_written() -> None:
    """검사를 통과한 초안은 다시 쓰지 않고, 문장 번호를 붙여 그대로 돌려준다."""
    client = DraftClient({"문제 해결 경험": [GOOD]}, reviews=[NO_REVIEW_FINDINGS])

    result, ended_by = _draft(client, _request(_question()))

    draft = result.drafts[0]
    assert (draft.text, draft.rewrites, draft.marks, draft.source) == (GOOD, 0, [], "LLM")
    assert [s.seq for s in draft.sentences] == [1, 2]
    assert _kinds(result) == ["WROTE", "CHECK_PASSED", "REVIEWED"]
    assert ended_by == "COMPLETED"


def test_over_limit_is_rewritten_without_asking_judge() -> None:
    """글자 수가 넘으면 다음 행동을 묻지 않고 줄여 다시 쓴다(검사기가 잘못 걸 일이 없어서)."""
    client = DraftClient({"문제 해결 경험": ["가" * 120 + ".", GOOD]}, reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question(limit=100)))

    assert (result.drafts[0].text, result.drafts[0].rewrites) == (GOOD, 1)
    assert client.decide_payloads == []
    assert client.write_payloads[1]["fix"]["problems"] == ["글자 수가 21자 넘습니다. 결과 문장은 두고 배경 설명을 줄이세요."]


def test_unsourced_number_is_rewritten_when_judge_says_fix() -> None:
    """근거에 없는 수치는 다음 행동이 FIX 를 고르면 그 수치를 알려 다시 쓴다."""
    client = DraftClient({"문제 해결 경험": [GOOD + " 응답도 30% 빨라졌습니다.", GOOD]},
                         decides=[_fix("FIX")], reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question()))

    assert (result.drafts[0].text, result.drafts[0].marks) == (GOOD, [])
    decided = next(step for step in result.steps if step.kind == "DECIDED")
    assert (decided.actor, decided.code, decided.value, decided.action) == ("SUB_AGENT", "UNSOURCED_NUMBER", "30%", "FIX")


def test_term_the_judge_keeps_is_not_rewritten_but_marked() -> None:
    """검사기가 잘못 건 것(카드 'k8s', 초안 'Kubernetes')을 KEEP 하면 다시 쓰지 않고 그 문장에 표시만 남긴다."""
    text = "Kubernetes 로 옮기고 바뀐 모듈만 다시 빌드했습니다. 배포 시간을 12분으로 줄였습니다."
    client = DraftClient({"문제 해결 경험": [text]}, decides=[_fix("KEEP", reason="카드의 k8s 와 같은 뜻")],
                         reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question()))

    draft = result.drafts[0]
    assert (draft.text, draft.rewrites) == (text, 0)
    assert [(m.sentence_seq, m.code, m.value) for m in draft.marks] == [(1, "UNSOURCED_TERM", "Kubernetes")]
    assert next(step.reason for step in result.steps if step.kind == "DECIDED") == "카드의 k8s 와 같은 뜻"


def test_kept_term_is_not_judged_again_after_rewrite() -> None:
    """한 곳은 KEEP·다른 곳은 FIX 로 다시 쓰면, 다시 쓴 글에서 KEEP 한 곳은 다시 묻지 않고 통과로 본다."""
    first = "Kubernetes 로 옮기고 바뀐 모듈만 다시 빌드했습니다. 응답도 30% 빨라졌습니다."
    second = "Kubernetes 로 옮기고 바뀐 모듈만 다시 빌드했습니다. 배포 시간을 12분으로 줄였습니다."
    client = DraftClient({"문제 해결 경험": [first, second]},
                         decides=[DecideOutput(decisions=[DecisionItem(index=1, action="FIX"),
                                                          DecisionItem(index=2, action="KEEP", reason="k8s 와 같은 뜻")])],
                         reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question()))

    assert len(client.decide_payloads) == 1
    assert (result.drafts[0].rewrites, _kinds(result)[-3]) == (1, "CHECK_PASSED")


def test_keep_reason_with_unsourced_number_is_dropped() -> None:
    """그대로 두는 이유에 근거 없는 수치가 있으면 화면에 뜨지 않게 이유를 버린다."""
    text = "Kubernetes 로 옮기고 바뀐 모듈만 다시 빌드했습니다."
    client = DraftClient({"문제 해결 경험": [text]}, decides=[_fix("KEEP", reason="k8s 와 같고 30% 빨라짐")],
                         reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question()))

    assert next(step.reason for step in result.steps if step.kind == "DECIDED") is None


def test_rewrites_stop_after_two_and_leave_marks() -> None:
    """다시 써도 계속 걸리면 2번까지만 다시 쓰고, 남은 수치는 '확인 필요' 로 표시한다."""
    stubborn = GOOD + " 응답도 30% 빨라졌습니다."
    client = DraftClient({"문제 해결 경험": [stubborn] * 3}, decides=[_fix("FIX"), _fix("FIX")],
                         reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question()))

    draft = result.drafts[0]
    assert draft.rewrites == 2
    assert [(m.sentence_seq, m.code, m.value) for m in draft.marks] == [(3, "UNSOURCED_NUMBER", "30%")]
    assert (len(client.write_payloads), len(client.decide_payloads)) == (3, 2)


def test_over_limit_left_after_rewrites_is_reported_not_cut() -> None:
    """2번 다시 써도 글자 수가 넘으면 자르지 않고 넘은 만큼만 알린다."""
    long = "가" * 120 + "."
    client = DraftClient({"문제 해결 경험": [long] * 3}, reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question(limit=100)))

    draft = result.drafts[0]
    assert (draft.text, draft.char_count, draft.over_by) == (long, 121, 21)


def test_missing_blank_is_appended_when_writer_leaves_it_out() -> None:
    """답을 받지 못한 항목의 빈칸을 끝내 빼먹으면 글 끝에 빈칸을 붙여, 지어낸 자리를 사용자가 보게 한다."""
    card = {**DEPLOY_CARD, "statements": DEPLOY_CARD["statements"][:2]}
    text = "배포 한 번에 40분이 걸려 바뀐 모듈만 다시 빌드했습니다."
    client = DraftClient({"문제 해결 경험": [text] * 3}, reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question(card=card, missing=("RESULT",))))

    draft = result.drafts[0]
    assert draft.text == f"{text}\n{blank('RESULT')}"
    assert (draft.blanks, draft.rewrites) == (["RESULT"], 2)


def test_judge_failure_falls_back_to_fixing() -> None:
    """다음 행동 답을 못 받으면 잘못 걸린 것인지 알 수 없으므로 코드가 FIX 로 정해 다시 쓴다."""
    client = DraftClient({"문제 해결 경험": [GOOD + " 응답도 30% 빨라졌습니다.", GOOD]},
                         decides=[LLMStructuredCallError("실패")], reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question()))

    decided = next(step for step in result.steps if step.kind == "DECIDED")
    assert (decided.actor, decided.action) == ("CODE", "FIX")
    assert result.drafts[0].text == GOOD


@pytest.mark.parametrize(
    "broken",
    [
        pytest.param(_fix("KEEP"), id="그대로 두는 이유가 없음"),
        pytest.param(DecideOutput(decisions=[]), id="걸린 곳을 빠뜨림"),
        pytest.param(_fix("FIX", reason="가" * 81), id="이유가 너무 김"),
    ],
)
def test_rule_breaking_decision_is_asked_again(broken: DecideOutput) -> None:
    """다음 행동 답이 규칙을 어기면 한 번 더 묻고, 규칙에 맞는 답을 쓴다."""
    client = DraftClient({"문제 해결 경험": [GOOD + " 응답도 30% 빨라졌습니다.", GOOD]},
                         decides=[broken, _fix("FIX")], reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question()))

    assert result.drafts[0].text == GOOD
    assert len(client.decide_payloads) == 2


def test_write_failure_gives_skeleton_from_card_sentences() -> None:
    """쓰기 답을 못 받으면 카드 문장을 상황·행동·결과 순서로 놓고 없는 항목은 빈칸으로 둔 뼈대를 주며, 검토하지 않는다."""
    card = {**DEPLOY_CARD, "statements": DEPLOY_CARD["statements"][:2]}
    client = DraftClient({"문제 해결 경험": [LLMConfigError("없음")]})

    result, ended_by = _draft(client, _request(_question(card=card)))

    draft = result.drafts[0]
    assert draft.text == f"배포 한 번에 40분이 걸렸다\nk8s 로 옮기고 바뀐 모듈만 다시 빌드했다\n{blank('RESULT')}"
    assert (draft.source, draft.blanks) == ("SKELETON", ["RESULT"])
    assert (client.review_payloads, ended_by) == ([], "FALLBACK")


def test_motivation_skeleton_uses_memo_as_reason() -> None:
    """지원 동기 문항의 뼈대는 메모를 지원 이유 자리에 놓는다."""
    client = DraftClient({"지원 동기": [LLMConfigError("없음")]})

    result, _ = _draft(client, _request(_question("지원 동기", card=None, theme="MOTIVATION"), memo="결제 서비스에 관심"))

    assert result.drafts[0].text == "결제 서비스에 관심"


def test_review_sends_back_only_the_flagged_question_once() -> None:
    """감독이 건 문항만 그 문장과 이유를 알려 한 번 더 쓰고, 고쳐지면 표시를 남기지 않는다."""
    claimed = GOOD.split(" 바뀐")[0] + " 팀장으로서 일정을 이끌었습니다."
    finding = FindingOutput(question_id=2, sentence_seq=2, code="UNSUPPORTED_CLAIM", reason="근거에 없는 팀장 역할")
    client = DraftClient({"협업 경험": [GOOD_API], "문제 해결 경험": [claimed, GOOD]},
                         reviews=[ReviewOutput(findings=[finding])])
    request = _request(_question("협업 경험", card=API_CARD, theme="COLLABORATION"), _question(qid=2))

    result, ended_by = _draft(client, request)

    assert (len(client.writes_for("협업 경험")), len(client.writes_for("문제 해결 경험"))) == (1, 2)
    assert client.writes_for("문제 해결 경험")[1]["fix"]["problems"] == [
        "2번 문장에 입력에 없는 역할이나 성과가 있습니다(근거에 없는 팀장 역할). 입력에 있는 사실로만 쓰세요.",
    ]
    assert (result.drafts[1].text, result.drafts[1].marks, result.drafts[1].rewrites) == (GOOD, [], 1)
    assert client.review_payloads[0]["questions"][1]["sources"][:2] == ["배포 시간 단축", "gitory"]
    sent = next(step for step in result.steps if step.kind == "SENT_BACK")
    assert (sent.question_id, sent.code, sent.sentence_seq) == (2, "UNSUPPORTED_CLAIM", 2)
    assert ended_by == "COMPLETED"


def test_flagged_sentence_left_after_rewrite_is_marked() -> None:
    """한 번 더 써도 걸린 문장이 글자 그대로 남으면 그 문장에 '확인 필요' 를 표시한다."""
    claimed = GOOD.split(" 바뀐")[0] + " 팀장으로서 일정을 이끌었습니다."
    finding = FindingOutput(question_id=1, sentence_seq=2, code="UNSUPPORTED_CLAIM", reason="근거에 없는 팀장 역할")
    client = DraftClient({"문제 해결 경험": [claimed, claimed]}, reviews=[ReviewOutput(findings=[finding])])

    result, _ = _draft(client, _request(_question()))

    assert [(m.sentence_seq, m.code) for m in result.drafts[0].marks] == [(2, "UNSUPPORTED_CLAIM")]


def _two_question_request() -> DraftsRequest:
    return _request(_question("협업 경험", card=API_CARD, theme="COLLABORATION"), _question(qid=2))


@pytest.mark.parametrize(
    "broken",
    [
        pytest.param(FindingOutput(question_id=7, sentence_seq=1, code="OFF_TOPIC", reason="벗어남"), id="검토하지 않은 문항"),
        pytest.param(FindingOutput(question_id=2, sentence_seq=3, code="OFF_TOPIC", reason="벗어남"), id="없는 문장 번호"),
        pytest.param(FindingOutput(question_id=2, sentence_seq=1, code="OFF_TOPIC", reason=" "), id="이유가 비었음"),
    ],
)
def test_rule_breaking_review_is_asked_again(broken: FindingOutput) -> None:
    """감독 답이 문항·문장 번호·이유 규칙을 어기면 한 번 더 묻고, 규칙에 맞는 답을 쓴다."""
    client = DraftClient({"협업 경험": [GOOD_API], "문제 해결 경험": [GOOD]},
                         reviews=[ReviewOutput(findings=[broken]), NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _two_question_request())

    assert len(client.review_payloads) == 2
    assert "SENT_BACK" not in _kinds(result)


def test_review_cannot_flag_same_sentence_twice_for_same_reason() -> None:
    """같은 문장을 같은 이유로 두 번 걸면 받아들이지 않는다."""
    twice = FindingOutput(question_id=2, sentence_seq=1, code="OFF_TOPIC", reason="벗어남")
    client = DraftClient({"협업 경험": [GOOD_API], "문제 해결 경험": [GOOD]},
                         reviews=[ReviewOutput(findings=[twice, twice]), NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _two_question_request())

    assert len(client.review_payloads) == 2
    assert "SENT_BACK" not in _kinds(result)


def test_review_cannot_flag_more_than_three_sentences_per_question() -> None:
    """한 문항에 4문장 넘게 걸면 받아들이지 않는다."""
    many = "첫째 문장입니다. 둘째 문장입니다. 셋째 문장입니다. 넷째 문장입니다."
    findings = [FindingOutput(question_id=1, sentence_seq=index, code="OFF_TOPIC", reason="벗어남") for index in range(1, 5)]
    client = DraftClient({"문제 해결 경험": [many]}, reviews=[ReviewOutput(findings=findings), NO_REVIEW_FINDINGS])

    _draft(client, _request(_question()))

    assert len(client.review_payloads) == 2


def test_repeated_needs_at_least_two_questions() -> None:
    """문항이 하나뿐이면 '다른 문항과 되풀이' 를 걸 수 없다."""
    repeated = FindingOutput(question_id=1, sentence_seq=1, code="REPEATED", reason="되풀이")
    client = DraftClient({"문제 해결 경험": [GOOD]}, reviews=[ReviewOutput(findings=[repeated]), NO_REVIEW_FINDINGS])

    _draft(client, _request(_question()))

    assert len(client.review_payloads) == 2


def test_review_failure_keeps_drafts_and_ends_partial() -> None:
    """감독 답을 못 받으면 문항별 초안을 그대로 돌려주고 PARTIAL 로 끝난다."""
    client = DraftClient({"협업 경험": [GOOD_API], "문제 해결 경험": [GOOD]}, reviews=[LLMStructuredCallError("실패")])

    result, ended_by = _draft(client, _two_question_request())

    assert [d.text for d in result.drafts] == [GOOD_API, GOOD]
    assert (_kinds(result)[-1], ended_by) == ("FAILED", "PARTIAL")


def test_review_reason_with_unsourced_number_is_dropped() -> None:
    """감독 이유에 근거 없는 수치가 있으면 화면에 뜨지 않게 이유를 버리고, 걸린 것은 그대로 돌려보낸다."""
    finding = FindingOutput(question_id=1, sentence_seq=1, code="OFF_TOPIC", reason="응답 30% 얘기로 벗어남")
    client = DraftClient({"문제 해결 경험": [GOOD, GOOD]}, reviews=[ReviewOutput(findings=[finding])])

    result, _ = _draft(client, _request(_question()))

    sent = next(step for step in result.steps if step.kind == "SENT_BACK")
    assert sent.reason is None


def test_skeleton_question_is_not_sent_to_review() -> None:
    """뼈대로 끝난 문항은 감독에게 보내지 않고, 일부 문항만 뼈대면 PARTIAL 이다."""
    client = DraftClient({"협업 경험": [LLMConfigError("없음")], "문제 해결 경험": [GOOD]}, reviews=[NO_REVIEW_FINDINGS])

    result, ended_by = _draft(client, _two_question_request())

    assert [q["question_id"] for q in client.review_payloads[0]["questions"]] == [2]
    assert ([d.source for d in result.drafts], ended_by) == (["SKELETON", "LLM"], "PARTIAL")


def test_numbers_and_names_from_answers_profile_memo_and_card_source_are_not_flagged() -> None:
    """답·프로필·메모·카드 출처 이름에 있는 수치와 낱말은 근거로 보고 걸지 않는다."""
    text = "gitory 에서 팀원 3명과 배포를 12분으로 줄였습니다. TOEIC 900 점수와 결제 관심으로 지원했습니다."
    question = _question(answers=[{"item": "ACTION", "text": "팀원 3명과 나눠 맡았다", "card_id": 1}])
    request = DraftsRequest.model_validate({
        **_request(question, memo="결제 서비스에 관심").model_dump(mode="json"),
        "profile": [{"kind": "LANGUAGE", "text": "TOEIC 900"}],
    })
    client = DraftClient({"문제 해결 경험": [text]}, reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, request)

    assert (result.drafts[0].marks, _kinds(result)[:2]) == ([], ["WROTE", "CHECK_PASSED"])


@pytest.mark.parametrize(
    ("answers", "last_line", "blanks"),
    [([], blank("LEARNING"), ["LEARNING"]),
     ([{"item": "LEARNING", "text": "작게 나눠 빌드하는 습관이 생겼다", "card_id": 1}], "작게 나눠 빌드하는 습관이 생겼다", [])],
    ids=["배운 점 없음", "배운 점 답"],
)
def test_growth_skeleton_ends_with_learning(answers: list[dict], last_line: str, blanks: list[str]) -> None:
    """성장 문항의 뼈대는 결과 뒤에 배운 점을 두고, 배운 점 답이 없으면 지어내지 않게 빈칸으로 둔다."""
    client = DraftClient({"성장 경험": [LLMConfigError("없음")]})

    result, _ = _draft(client, _request(_question("성장 경험", theme="GROWTH", answers=answers)))

    draft = result.drafts[0]
    assert draft.text.split("\n")[-2:] == ["배포 시간을 12분으로 줄였다", last_line]
    assert draft.blanks == blanks


def test_required_blank_is_added_even_if_spring_leaves_it_out() -> None:
    """중심 카드에 결과가 없고 답도 없으면 Spring 이 빈칸 목록을 비워 보내도 결과 빈칸을 넣게 한다."""
    card = {**DEPLOY_CARD, "statements": DEPLOY_CARD["statements"][:2]}
    client = DraftClient({"문제 해결 경험": [f"배포 한 번에 40분이 걸려 바뀐 모듈만 다시 빌드했습니다. {blank('RESULT')}"]},
                         reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question(card=card)))

    assert client.write_payloads[0]["blanks"] == [blank("RESULT")]
    assert result.drafts[0].blanks == ["RESULT"]


def test_review_finding_on_a_blank_line_is_ignored() -> None:
    """감독이 빈칸 줄을 걸어도 돌려보내지 않는다. 고치라고 하면 사용자가 채울 자리를 모델이 지어내서 채운다."""
    card = {**DEPLOY_CARD, "statements": DEPLOY_CARD["statements"][:2]}
    text = "배포 한 번에 40분이 걸려 바뀐 모듈만 다시 빌드했습니다.\n" + blank("RESULT")
    finding = FindingOutput(question_id=1, sentence_seq=2, code="OFF_TOPIC", reason="결과가 없음")
    client = DraftClient({"문제 해결 경험": [text]}, reviews=[ReviewOutput(findings=[finding])])

    result, _ = _draft(client, _request(_question(card=card)))

    assert "SENT_BACK" not in _kinds(result)
    assert (result.drafts[0].text, result.drafts[0].marks, len(client.write_payloads)) == (text, [], 1)


def test_rewrite_after_review_that_adds_new_problem_is_reverted() -> None:
    """검토 뒤 다시 쓴 글이 앞 초안에 없던 문제(근거 없는 수치)로 걸리면 앞 초안으로 되돌리고 걸린 문장에 표시한다."""
    claimed = GOOD.split(" 바뀐")[0] + " 팀장으로서 일정을 이끌었습니다."
    worse = GOOD.split(" 바뀐")[0] + " 응답도 30% 빨라졌습니다."
    finding = FindingOutput(question_id=1, sentence_seq=2, code="UNSUPPORTED_CLAIM", reason="근거에 없는 팀장 역할")
    client = DraftClient({"문제 해결 경험": [claimed, worse]}, reviews=[ReviewOutput(findings=[finding])])

    result, _ = _draft(client, _request(_question()))

    draft = result.drafts[0]
    assert (draft.text, [(m.sentence_seq, m.code) for m in draft.marks]) == (claimed, [(2, "UNSUPPORTED_CLAIM")])
    assert "REVERTED" in _kinds(result)


def test_skeleton_uses_answers_about_primary_card_only() -> None:
    """뼈대는 중심 카드의 답은 쓰고, 보조 카드에 대한 답은 중심 경험으로 옮기지 않는다."""
    card = {**DEPLOY_CARD, "statements": DEPLOY_CARD["statements"][:2]}
    answers = [{"item": "RESULT", "text": "테스트가 3분으로 줄었다", "card_id": 2},
               {"item": "LEARNING", "text": "작게 나눠 배포하는 습관", "card_id": 1}]
    question = {**_question(card=card, answers=answers), "supporting_card": {**API_CARD}}
    client = DraftClient({"문제 해결 경험": [LLMConfigError("없음")]})

    result, _ = _draft(client, _request(question))

    assert result.drafts[0].text.splitlines() == [
        "배포 한 번에 40분이 걸렸다", "k8s 로 옮기고 바뀐 모듈만 다시 빌드했다", blank("RESULT"), "작게 나눠 배포하는 습관",
    ]


def test_motivation_skeleton_without_memo_leaves_reason_blank() -> None:
    """메모가 없는 지원 동기 문항의 뼈대는 지원 이유를 빈칸으로 둔다."""
    client = DraftClient({"지원 동기": [LLMConfigError("없음")]})

    result, _ = _draft(client, _request(_question("지원 동기", card=None, theme="MOTIVATION")))

    assert (result.drafts[0].text, result.drafts[0].blanks) == (blank("REASON"), ["REASON"])


class SlowDraftClient(DraftClient):
    """정해 둔 문항의 쓰기만 늦게 답한다."""

    def __init__(self, slow: dict[str, float], *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self._slow = slow

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        if output_model is WriteOutput:
            time.sleep(self._slow.get(payload["question"]["text"], 0))
        return super().call(system_prompt, payload, output_model, max_completion_tokens, timeout_seconds)


def test_slow_question_does_not_take_the_review_time() -> None:
    """한 문항의 쓰기가 늦어도 검토 몫 시간을 남기고 뼈대로 끊어, 감독은 나머지 문항을 검토한다."""
    limits = CoverLetterLimits(draft_deadline_seconds=3.0, review_reserve_seconds=1.5, min_call_seconds=0.2,
                               target_ratio=0.0)
    client = SlowDraftClient({"협업 경험": 6.0}, {"협업 경험": [GOOD_API], "문제 해결 경험": [GOOD]},
                             reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _two_question_request(), limits)

    assert [d.source for d in result.drafts] == ["SKELETON", "LLM"]
    assert "REVIEWED" in _kinds(result)


def test_short_draft_is_not_rewritten_to_fill_a_length_target() -> None:
    """초안은 재료만큼만 쓰므로, 글자 수 상한의 70% 에 못 미쳐도 늘려 쓰라고 다시 쓰게 하지 않는다."""
    client = DraftClient({"문제 해결 경험": [GOOD]}, reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question()), CoverLetterLimits())

    assert (result.drafts[0].text, result.drafts[0].rewrites) == (GOOD, 0)
    assert "target_chars" not in client.write_payloads[0]["question"]


def test_short_draft_with_more_to_write_blank_passes_and_blank_is_not_counted() -> None:
    """재료가 모자라 '[더 쓸 거리: …]' 빈칸을 넣은 짧은 초안은 그대로 두고, 빈칸은 글자 수에 세지 않는다."""
    text = GOOD + " [더 쓸 거리: 바뀐 모듈만 빌드한 이유]"
    client = DraftClient({"문제 해결 경험": [text]}, reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question()), CoverLetterLimits())

    draft = result.drafts[0]
    assert (draft.rewrites, draft.char_count) == (0, len(GOOD) + 1)
    assert "CHECK_PASSED" in _kinds(result)


def _supported(*pairs: tuple[int, str]) -> ReadinessOutput:
    return ReadinessOutput(supported=[SupportedOutput(evidence=number, quote=quote) for number, quote in pairs])


#: 배포 카드 문장. 증거 번호와 짝지어 '재료가 보여 주는 증거' 로 쓴다
S_LINE, A_LINE, R_LINE = (s["text"] for s in DEPLOY_CARD["statements"])


@pytest.mark.parametrize(
    ("supported", "source", "missing"),
    [
        pytest.param(_supported((1, S_LINE), (2, A_LINE), (4, R_LINE)), "LLM", None, id="증거 3개 → 그대로 씀"),
        pytest.param(_supported((1, S_LINE), (2, "캐시를 직접 설계해 빌드를 다시 짰다")), "LLM",
                     ["선택지와 그걸 고른 기술적 근거", "해결 과정에서 막힌 곳과 넘은 방법"],
                     id="재료에 없는 인용은 세지 않아 1개 → 빈칸 넣어 씀"),
        pytest.param(_supported((2, "캐시를 직접 설계해 빌드를 다시 짰다"), (4, "배포가 세 배 빨라졌다")),
                     "SKELETON", None, id="재료에 있는 증거 0개 → 뼈대"),
    ],
)
def test_readiness_decides_how_to_write(supported: ReadinessOutput, source: str, missing: Optional[list[str]]) -> None:
    """재료가 보여 주는 행동증거가 3개면 그대로, 1~2개면 모자란 사실 증거를 빈칸으로 넣어 쓰고, 하나도 없으면 쓰지 않고 뼈대로 둔다."""
    client = DraftClient({"문제 해결 경험": [GOOD]}, reviews=[NO_REVIEW_FINDINGS],
                         readiness={"문제 해결 경험": supported})

    result, ended_by = _draft(client, _request(_question()))

    assert result.drafts[0].source == source
    if source == "SKELETON":
        assert client.write_payloads == []
        step = next(step for step in result.steps if step.kind == "SKELETON")
        assert (step.actor, step.reason, ended_by) == ("CODE", "문항이 보려는 경험 재료가 모자라 뼈대로 둠", "COMPLETED")
    else:
        assert client.write_payloads[0].get("missing_evidence") == missing


def test_writing_goes_ahead_when_readiness_cannot_be_judged() -> None:
    """준비도 판단을 못 받으면 쓸 수 있는 문항까지 뼈대가 되지 않게 막지 않고 쓴다."""
    client = DraftClient({"문제 해결 경험": [GOOD]}, reviews=[NO_REVIEW_FINDINGS],
                         readiness={"문제 해결 경험": LLMStructuredCallError("실패")})

    result, _ = _draft(client, _request(_question()))

    assert (result.drafts[0].source, "missing_evidence" in client.write_payloads[0]) == ("LLM", False)


@pytest.mark.parametrize(
    ("theme", "first_evidence", "deep", "tech_names", "repo_words"),
    [("COLLABORATION", "함께 일한 상대와 상황(의견이 갈렸다면 어떻게 갈렸나)", False, 1, 3),
     ("PROBLEM_SOLVING", "문제를 구체적으로 정의한 것", True, 2, 1)],
)
def test_write_payload_carries_evidence_and_tech_of_the_theme(theme: str, first_evidence: str, deep: bool,
                                                              tech_names: int, repo_words: int) -> None:
    """쓰기는 문항 유형의 행동증거와 기술 이야기를 얼마나 쓸지, 기술 이름(협업 1·문제 해결 2)과 저장소 작업 말(협업 3·그 밖 1)을
    몇 개까지 쓸지를 함께 받는다."""
    client = DraftClient({"문제 해결 경험": [GOOD]}, reviews=[NO_REVIEW_FINDINGS])

    _draft(client, _request(_question(theme=theme)))

    question = client.write_payloads[0]["question"]
    assert question["evidence"][0] == first_evidence
    assert ("깊이를 살립니다" in question["tech"]) is deep
    assert (question["tech_name_limit"], question["repo_word_limit"]) == (tech_names, repo_words)


#: 재료에 없는 해석(다시 한다면 다르게 할 점)으로, 작성 AI 가 추론으로 밝히는 문장
INF = "다시 한다면 빌드 캐시를 먼저 살펴볼 것입니다."
WITH_INF = f"{GOOD} {INF}"


def test_inferred_sentence_the_writer_declares_is_marked_for_user_check() -> None:
    """작성 AI 가 재료에 없는 해석으로 지어 썼다고 밝힌 문장에는 추론 표시(INFERRED)를 달고, 감독에게도 그 문장 번호를 알린다."""
    client = DraftClient({"문제 해결 경험": [WriteOutput(text=WITH_INF, inferred=[INF])]}, reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question()))

    assert [(m.sentence_seq, m.code, m.value) for m in result.drafts[0].marks] == [(3, "INFERRED", None)]
    step = next(step for step in result.steps if step.kind == "MARKED")
    assert (step.actor, step.code, step.sentence_seq, step.sentence) == ("SUB_AGENT", "INFERRED", 3, INF)
    assert client.review_payloads[0]["questions"][0]["inferred"] == [3]


@pytest.mark.parametrize(
    ("claimed", "marked"),
    [
        pytest.param(["바뀐 모듈만"], [], id="문장 일부"),
        pytest.param(["빌드 캐시를 먼저 살펴보겠습니다.", "바뀐 모듈만", INF], [3], id="버린 것은 2개 상한에 세지 않음"),
        pytest.param(["다시 한다면  빌드 캐시를\n먼저 살펴볼 것입니다."], [3], id="띄어쓰기만 다름"),
    ],
)
def test_only_whole_sentences_of_the_text_count_as_inferred(claimed: list[str], marked: list[int]) -> None:
    """작성 AI 가 추론이라고 낸 것 가운데 글에 문장 그대로 있는 것만 추론으로 치고, 나머지는 오류 없이 버린다."""
    client = DraftClient({"문제 해결 경험": [WriteOutput(text=WITH_INF, inferred=claimed)]}, reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question()))

    assert ([m.sentence_seq for m in result.drafts[0].marks], len(client.write_payloads)) == (marked, 1)


def test_more_than_two_inferred_sentences_are_asked_again() -> None:
    """추론 문장이 문항에 2개를 넘으면 받아들이지 않고 다시 써 달라고 한다."""
    second = "입사 후에도 배포 흐름부터 살피겠습니다."
    third = "작은 단위로 나눠 보는 습관이 생겼습니다."
    many = WriteOutput(text=" ".join([GOOD, INF, second, third]), inferred=[INF, second, third])
    fewer = WriteOutput(text=" ".join([GOOD, INF, second]), inferred=[INF, second])
    client = DraftClient({"문제 해결 경험": [many, fewer]}, reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question()))

    assert len(client.write_payloads) == 2
    assert [(m.sentence_seq, m.code) for m in result.drafts[0].marks] == [(3, "INFERRED"), (4, "INFERRED")]


@pytest.mark.parametrize(
    ("supported", "missing", "inferable"),
    [
        pytest.param(_supported((1, S_LINE), (2, A_LINE)), ["해결 과정에서 막힌 곳과 넘은 방법"], ["결과와 아쉬운 점·더 나은 방법"],
                     id="증거 2개 → 사실은 빈칸, 해석은 추론"),
        pytest.param(_supported((1, S_LINE), (2, A_LINE), (3, R_LINE)), None, ["결과와 아쉬운 점·더 나은 방법"],
                     id="증거 3개 → 해석만 추론"),
    ],
)
def test_missing_interpretation_is_inferred_and_missing_fact_is_left_blank(
    supported: ReadinessOutput, missing: Optional[list[str]], inferable: list[str],
) -> None:
    """빠진 증거가 사실이면 빈칸으로(증거가 하나 모자랄 때만), 해석이면 빈칸 대신 추론 문장으로 쓰게 넘긴다."""
    client = DraftClient({"문제 해결 경험": [GOOD]}, reviews=[NO_REVIEW_FINDINGS], readiness={"문제 해결 경험": supported})

    _draft(client, _request(_question()))

    payload = client.write_payloads[0]
    assert (payload.get("missing_evidence"), payload.get("inferable_evidence")) == (missing, inferable)


def test_review_marks_undeclared_inference_without_rewriting() -> None:
    """감독이 밝히지 않은 추론 문장을 찾으면 다시 쓰게 하지 않고, 그 문장에 감독이 단 추론 표시를 남긴다."""
    finding = FindingOutput(question_id=1, sentence_seq=3, code="INFERRED", reason="근거에 없는 아쉬운 점")
    client = DraftClient({"문제 해결 경험": [WITH_INF]}, reviews=[ReviewOutput(findings=[finding])])

    result, _ = _draft(client, _request(_question()))

    assert (len(client.write_payloads), "SENT_BACK" in _kinds(result)) == (1, False)
    assert [(m.sentence_seq, m.code) for m in result.drafts[0].marks] == [(3, "INFERRED")]
    step = next(step for step in result.steps if step.kind == "MARKED")
    assert (step.actor, step.code, step.sentence) == ("SUPERVISOR", "INFERRED", INF)


def test_review_inference_mark_is_dropped_when_rewrite_removes_the_sentence() -> None:
    """같은 문항이 다른 이유로 다시 쓰여 감독이 추론으로 본 문장이 없어지면, 다른 문장에 표시를 달지 않는다."""
    findings = [FindingOutput(question_id=1, sentence_seq=3, code="INFERRED", reason="근거에 없는 아쉬운 점"),
                FindingOutput(question_id=1, sentence_seq=4, code="OFF_TOPIC", reason="문항과 상관없음")]
    client = DraftClient({"문제 해결 경험": [f"{WITH_INF} 회의실 예약도 맡았습니다.", GOOD]},
                         reviews=[ReviewOutput(findings=findings)])

    result, _ = _draft(client, _request(_question()))

    assert len(client.writes_for("문제 해결 경험")[1]["fix"]["problems"]) == 1
    assert (result.drafts[0].text, result.drafts[0].marks) == (GOOD, [])


def test_reverted_rewrite_keeps_the_inferred_marks_of_the_previous_draft() -> None:
    """검토 뒤 다시 쓴 글을 되돌리면 앞 초안이 밝힌 추론 문장의 표시도 그대로 남는다."""
    claimed = f"{WITH_INF} 팀장으로서 일정을 이끌었습니다."
    finding = FindingOutput(question_id=1, sentence_seq=4, code="UNSUPPORTED_CLAIM", reason="근거에 없는 팀장 역할")
    client = DraftClient({"문제 해결 경험": [WriteOutput(text=claimed, inferred=[INF]), GOOD + " 응답도 30% 빨라졌습니다."]},
                         reviews=[ReviewOutput(findings=[finding])])

    result, _ = _draft(client, _request(_question()))

    assert client.write_payloads[1]["fix"]["previous_inferred"] == [INF]
    assert "REVERTED" in _kinds(result)
    assert [(m.sentence_seq, m.code) for m in result.drafts[0].marks] == [(4, "UNSUPPORTED_CLAIM"), (3, "INFERRED")]


def test_unanswered_learning_is_inferred_instead_of_blank() -> None:
    """사용자가 답하지 않은 배운 점은 빈칸으로 넣게 하지 않고, 추론 문장으로 쓸 해석 증거로 넘긴다."""
    client = DraftClient({"성장 경험": [GOOD]}, reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question("성장 경험", theme="GROWTH")))

    payload = client.write_payloads[0]
    assert (payload["blanks"], payload["inferable_evidence"], result.drafts[0].blanks) == ([], ["배운 점"], [])


def test_fact_items_keep_their_blanks_when_learning_is_inferred() -> None:
    """배운 점을 추론으로 돌려도, 재료에 없는 결과 같은 사실 항목은 그대로 빈칸으로 둔다."""
    card = {**DEPLOY_CARD, "statements": DEPLOY_CARD["statements"][:2]}
    text = f"배포 한 번에 40분이 걸려 바뀐 모듈만 다시 빌드했습니다.\n{blank('RESULT')}"
    client = DraftClient({"성장 경험": [text]}, reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question("성장 경험", theme="GROWTH", card=card)))

    assert (client.write_payloads[0]["blanks"], result.drafts[0].blanks) == ([blank("RESULT")], ["RESULT"])


def test_draft_without_learning_blank_is_not_sent_back_or_appended() -> None:
    """배운 점 빈칸이 없는 글은 빈칸을 빠뜨렸다고 다시 쓰게 하지 않고, 끝에 배운 점 빈칸을 붙이지도 않는다."""
    client = DraftClient({"성장 경험": [GOOD]}, reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question("성장 경험", theme="GROWTH")))

    assert (result.drafts[0].text, result.drafts[0].rewrites, "CHECK_FAILED" in _kinds(result)) == (GOOD, 0, False)


#: 카드의 사실(k8s·12분)을 섞어 추론으로 밝힌 문장
FACT_INF = "k8s 로 옮겨 12분으로 줄인 일에서 작게 나누는 법을 배웠습니다."


def test_inferred_sentence_with_numbers_or_latin_terms_is_asked_again() -> None:
    """추론 문장에 숫자·영문 낱말이 있으면 사실을 섞은 것으로 보고 다시 받고, 해석만 담은 추론은 그대로 받는다."""
    mixed = WriteOutput(text=f"{GOOD} {FACT_INF}", inferred=[FACT_INF])
    clean = WriteOutput(text=WITH_INF, inferred=[INF])
    client = DraftClient({"문제 해결 경험": [mixed, clean]}, reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question()))

    assert (len(client.write_payloads), result.drafts[0].text) == (2, WITH_INF)
    assert [(m.sentence_seq, m.code) for m in result.drafts[0].marks] == [(3, "INFERRED")]


def test_fact_mixed_inference_left_after_retries_stays_marked() -> None:
    """다시 받아도 추론 문장에 사실이 섞여 있으면 글을 뼈대로 버리지 않고, 그 문장에 추론 표시를 단 채 둔다."""
    mixed = WriteOutput(text=f"{GOOD} {FACT_INF}", inferred=[FACT_INF])
    client = DraftClient({"문제 해결 경험": [mixed, mixed]}, reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question()))

    draft = result.drafts[0]
    assert (draft.source, [(m.sentence_seq, m.code) for m in draft.marks]) == ("LLM", [(3, "INFERRED")])


def test_review_inference_mixed_with_facts_is_sent_back_to_split() -> None:
    """감독이 찾은 추론 문장에 숫자·영문 낱말(사실)이 섞여 있으면 표시만 달지 않고, 해석을 떼어 따로 쓰게 돌려보낸다."""
    finding = FindingOutput(question_id=1, sentence_seq=3, code="INFERRED", reason="근거에 없는 배운 점")
    client = DraftClient({"문제 해결 경험": [f"{GOOD} {FACT_INF}", WriteOutput(text=WITH_INF, inferred=[INF])]},
                         reviews=[ReviewOutput(findings=[finding])])

    result, _ = _draft(client, _request(_question()))

    assert client.write_payloads[1]["fix"]["problems"] == [
        "3번 문장은 사실과 해석이 섞여 있습니다. 해석('느꼈다·배웠다·생각했다' 부분)을 사실 문장에서 떼어 "
        "따로 한 문장으로 쓰고 inferred 에 넣으세요.",
    ]
    marked = [(step.actor, step.sentence) for step in result.steps if step.kind == "MARKED"]
    assert (result.drafts[0].text, marked) == (WITH_INF, [("SUB_AGENT", INF)])


def test_mixed_review_inference_left_after_rewrite_is_still_marked() -> None:
    """돌려보내도 사실이 섞인 그 문장이 그대로 남으면 감독의 추론 표시를 단다."""
    finding = FindingOutput(question_id=1, sentence_seq=3, code="INFERRED", reason="근거에 없는 배운 점")
    client = DraftClient({"문제 해결 경험": [f"{GOOD} {FACT_INF}", f"{GOOD} {FACT_INF}"]},
                         reviews=[ReviewOutput(findings=[finding])])

    result, _ = _draft(client, _request(_question()))

    step = next(step for step in result.steps if step.kind == "MARKED")
    assert (len(client.write_payloads), step.actor, step.code, step.sentence) == (2, "SUPERVISOR", "INFERRED", FACT_INF)


def test_repeated_introduction_is_sent_back_to_mention_briefly() -> None:
    """다른 문항과 되풀이한 문장은 이 문항의 카드 내용으로 바꾸고, 소개·포부의 되풀이면 짧게 짚기만 하라고 돌려보낸다."""
    finding = FindingOutput(question_id=2, sentence_seq=1, code="REPEATED", reason="같은 프로젝트 소개")
    client = DraftClient({"협업 경험": [GOOD_API], "문제 해결 경험": [GOOD, GOOD]}, reviews=[ReviewOutput(findings=[finding])])

    _draft(client, _two_question_request())

    assert client.writes_for("문제 해결 경험")[1]["fix"]["problems"] == [
        "1번 문장이 다른 문항과 같은 이야기입니다(같은 프로젝트 소개). 이 문항의 카드 내용으로 바꾸세요. "
        "프로젝트·회사 소개나 포부를 되풀이한 것이면 소개는 짧게 짚기만 하세요.",
    ]


@pytest.mark.parametrize(
    ("char_limit", "material_chars", "cap"),
    [(700, 60, 220), (700, 300, 450), (500, 400, 500)],
    ids=["재료가 적으면 220자", "재료의 1.5배", "문항 글자 수를 넘지 않음"],
)
def test_length_cap_follows_material(char_limit: int, material_chars: int, cap: int) -> None:
    """글 길이 상한은 재료 글자 수의 1.5배로 두되, 220자보다 짧게 하지 않고 문항 글자 수 상한을 넘지 않는다."""
    assert length_cap(char_limit, material_chars) == cap


#: 기술 이름 둘(Redis·Kafka)이 든 카드로, 협업 문항(기술 이름 1개까지)에 그대로 쓰면 걸린다
TECH_CARD = {**API_CARD, "statements": [*API_CARD["statements"], {"star_slot": "A", "text": "Redis 와 Kafka 를 붙였다"}]}
TECHY = "Redis 와 Kafka 를 붙여 의견을 모았습니다. 한 가지 모양으로 정했습니다."


def test_draft_over_the_tech_name_cap_is_rewritten_in_plain_words() -> None:
    """기술 이름이 문항 유형의 상한을 넘으면 기술 이름이 가장 많은 문장을 짚어 쉬운 말로 풀어 다시 쓰게 한다."""
    client = DraftClient({"협업 경험": [TECHY, GOOD_API]}, reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question("협업 경험", card=TECH_CARD, theme="COLLABORATION")))

    assert client.write_payloads[1]["fix"]["problems"] == [
        "1번 문장 등에서 기술 이름 2개 (상한 1)입니다. 핵심만 남기고 나머지는 쉬운 말로 풀어 쓰세요.",
    ]
    assert (result.drafts[0].text, result.drafts[0].rewrites) == (GOOD_API, 1)


def test_tech_names_left_after_rewrites_are_not_marked() -> None:
    """다시 써도 기술 이름이 상한을 넘으면 그대로 두고, 사용자에게 보일 표시는 달지 않는다."""
    client = DraftClient({"협업 경험": [TECHY, TECHY, TECHY]}, reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question("협업 경험", card=TECH_CARD, theme="COLLABORATION")))

    assert (result.drafts[0].text, result.drafts[0].rewrites, result.drafts[0].marks) == (TECHY, 2, [])


#: 카드에 있는 코드 이름(@BatchSize)으로, 글에 그대로 옮기면 카드에 있어도 걸린다
BATCH_CARD = {**DEPLOY_CARD, "statements": [*DEPLOY_CARD["statements"], {"star_slot": "A", "text": "@BatchSize 를 적용했다"}]}


def test_code_name_from_the_card_is_rewritten_in_plain_words_without_asking_judge() -> None:
    """카드에 있는 코드 이름도 글에 쓰면 다음 행동을 묻지 않고 뜻을 풀어 다시 쓰게 하며, 단계에는 Spring 이 아는 OFF_TOPIC 으로 남긴다."""
    coded = GOOD.replace("바뀐 모듈만", "@BatchSize 로 바뀐 모듈만")
    client = DraftClient({"문제 해결 경험": [coded, GOOD]}, reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question(card=BATCH_CARD)))

    assert client.write_payloads[1]["fix"]["problems"] == [
        "'@BatchSize' 는 코드 이름입니다. 뜻을 풀어 쓰세요(예: '상품 목록을 한 번에 가져오게 설정').",
    ]
    failed = next(step for step in result.steps if step.kind == "CHECK_FAILED")
    assert (failed.code, failed.value, failed.sentence_seq, client.decide_payloads) == ("OFF_TOPIC", "@BatchSize", 2, [])
    assert (result.drafts[0].text, result.drafts[0].rewrites) == (GOOD, 1)


#: 저장소 작업 말 둘(PR·머지)이 든 카드와, 그 말을 그대로 옮긴 글
PR_CARD = {**API_CARD, "statements": [*API_CARD["statements"], {"star_slot": "A", "text": "PR 을 머지했다"}]}
PR_TEXT = "API 응답 모양을 두고 갈린 의견을 회의로 견줬습니다. PR 을 머지했습니다."


def test_repository_words_over_the_theme_cap_are_rewritten_with_plain_paraphrases() -> None:
    """저장소 작업 말이 문항 유형의 상한(협업 3·그 밖 1)을 넘으면 풀어 쓸 말을 알려 다시 쓰게 하고, 상한 안이면 그대로 둔다."""
    client = DraftClient({"협업 경험": [PR_TEXT], "문제 해결 경험": [PR_TEXT, GOOD_API]}, reviews=[NO_REVIEW_FINDINGS])
    request = _request(_question("협업 경험", card=PR_CARD, theme="COLLABORATION"), _question(qid=2, card=PR_CARD))

    result, _ = _draft(client, request)

    assert len(client.writes_for("협업 경험")) == 1
    assert client.writes_for("문제 해결 경험")[1]["fix"]["problems"] == [
        "2번 문장 등에서 저장소 작업 말 2번 (상한 1)입니다. 저장소 작업 말은 풀어 쓰세요(예: PR → '동료의 수정 제안', "
        "머지 → '팀 공용 코드에 반영', 리뷰 지적 → '검토 의견', 이슈 → '작업 요청').",
    ]
    assert result.drafts[1].text == GOOD_API


def test_motivation_draft_without_what_to_do_after_joining_is_rewritten_to_end_with_it() -> None:
    """지원 동기 글이 입사 후 하고 싶은 일로 끝나지 않으면 공고가 없어도 그 일로 맺도록 다시 쓰게 한다."""
    plain = "결제 서비스에 관심이 있어 지원했습니다. 배포 시간을 12분으로 줄였습니다."
    ending = f"{plain} 입사 후 결제 서버를 안정적으로 만드는 데 기여하겠습니다."
    client = DraftClient({"지원 동기": [plain, ending]}, reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question("지원 동기", theme="MOTIVATION"), memo="결제 서비스에 관심"))

    assert client.write_payloads[1]["fix"]["problems"] == [
        "지원 동기 글이 입사 후 하고 싶은 일로 끝나지 않습니다. 마지막 문장을 입사 후 하고 싶은 일(공고가 있으면 주요 업무 중 "
        "기여하고 싶은 일, 없으면 메모·지원 직무의 방향)로 맺고, 재료에 없는 해석이면 inferred 에 넣으세요.",
    ]
    assert (result.drafts[0].text, result.drafts[0].rewrites) == (ending, 1)


@pytest.mark.parametrize(
    ("text", "theme", "reason", "direction"),
    [("협업 경험", "COLLABORATION", "협업 문항의 기술 작업 서술",
      "기술 작업 서술이면 재료에 있는 사람 이야기(누구와 무엇이 갈렸나·내가 한 말과 제안·어떻게 맞췄나·팀에 달라진 점)로 바꾸고, "
      "기술 작업은 한 문장 안의 한 구절로만 남기세요."),
     ("지원 동기", "MOTIVATION", "지원 동기 문항의 경험 서술이 절반 넘음",
      "경험 과정 서술이면 경험은 결과 한두 문장으로 줄이고, 지원 이유와 입사 후 하고 싶은 일로 채우세요.")],
    ids=["협업", "지원 동기"],
)
def test_off_topic_review_is_sent_back_with_the_direction_of_the_question_type(
    text: str, theme: str, reason: str, direction: str,
) -> None:
    """감독이 건 OFF_TOPIC 은 협업 문항이면 사람 이야기로, 지원 동기 문항이면 경험을 줄이고 지원 이유·입사 후 할 일로 바꾸라고 돌려보낸다."""
    draft = f"{GOOD_API} 입사 후 응답 모양을 먼저 맞추겠습니다."
    finding = FindingOutput(question_id=1, sentence_seq=1, code="OFF_TOPIC", reason=reason)
    client = DraftClient({text: [draft, draft]}, reviews=[ReviewOutput(findings=[finding])])

    _draft(client, _request(_question(text, card=API_CARD, theme=theme), memo="결제 서비스에 관심"))

    assert client.write_payloads[1]["fix"]["problems"] == [
        f"1번 문장이 문항이 묻는 것을 벗어났습니다({reason}). {direction}",
    ]

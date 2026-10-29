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
from services.cover_letter_writer import DecideOutput, DecisionItem, WriteOutput
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
                 reviews: Optional[list[Any]] = None) -> None:
        self._writes = {text: list(answers) for text, answers in writes.items()}
        self._decides = list(decides or [])
        self._reviews = list(reviews or [])
        self._lock = threading.Lock()
        self.write_payloads: list[dict[str, Any]] = []
        self.decide_payloads: list[dict[str, Any]] = []
        self.review_payloads: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        with self._lock:
            if output_model is WriteOutput:
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


def test_draft_below_target_length_is_rewritten_to_reach_it() -> None:
    """초안이 목표 글자 수(상한의 70%)에 못 미치고 '[더 쓸 거리]' 빈칸도 없으면 사실을 풀어 써서 다시 쓰게 한다."""
    long_draft = "배포 시간을 40분에서 12분으로 줄였습니다. " * 20
    client = DraftClient({"문제 해결 경험": [GOOD, long_draft]}, reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question()), CoverLetterLimits())

    payloads = client.writes_for("문제 해결 경험")
    assert payloads[0]["question"]["target_chars"] == 490
    assert "글자 수가 목표보다" in payloads[1]["fix"]["problems"][0]
    assert (result.drafts[0].text, result.drafts[0].rewrites) == (long_draft.strip(), 1)


def test_short_draft_with_more_to_write_blank_passes_and_blank_is_not_counted() -> None:
    """재료가 모자라 '[더 쓸 거리: …]' 빈칸을 넣은 짧은 초안은 그대로 두고, 빈칸은 글자 수에 세지 않는다."""
    text = GOOD + " [더 쓸 거리: 바뀐 모듈만 빌드한 이유]"
    client = DraftClient({"문제 해결 경험": [text]}, reviews=[NO_REVIEW_FINDINGS])

    result, _ = _draft(client, _request(_question()), CoverLetterLimits())

    draft = result.drafts[0]
    assert (draft.rewrites, draft.char_count) == (0, len(GOOD) + 1)
    assert "CHECK_PASSED" in _kinds(result)


def test_rewrite_after_review_that_is_still_short_is_not_reverted() -> None:
    """검토 뒤 다시 쓴 글이 앞 초안처럼 목표 글자 수에 못 미칠 뿐이면 새 문제가 아니라서 되돌리지 않는다."""
    claimed = GOOD.split(" 바뀐")[0] + " 팀장으로서 일정을 이끌었습니다."
    fixed = GOOD + " 배포가 빨라졌습니다."
    finding = FindingOutput(question_id=1, sentence_seq=2, code="UNSUPPORTED_CLAIM", reason="근거에 없는 팀장 역할")
    client = DraftClient({"문제 해결 경험": [claimed, claimed, claimed, fixed]}, reviews=[ReviewOutput(findings=[finding])])

    result, _ = _draft(client, _request(_question()), CoverLetterLimits())

    assert (result.drafts[0].text, result.drafts[0].rewrites) == (fixed, 3)
    assert "REVERTED" not in _kinds(result)

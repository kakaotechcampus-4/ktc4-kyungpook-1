"""공고에서 시작한 자소서: 매칭 판단(✓·✕)을 카드 고르기·재료 질문·초안에 쓰는 규칙."""

from __future__ import annotations

import asyncio
import json
import threading
from typing import Any, Optional

import pytest
from pydantic import ValidationError

from schemas.cover_letter import AssignmentRequest, DraftsRequest, Posting, PostingCondition, QuestionsRequest
from services.cover_letter_asker import AskItemOutput, AskOutput, CoverLetterAsker
from services.cover_letter_assigner import ConsideredOutput, CoverLetterAssigner, PickOutput, SupervisorOutput
from services.cover_letter_check import check_draft
from services.cover_letter_drafter import CoverLetterDrafter, ReviewOutput
from services.cover_letter_llm import CoverLetterLimits
from services.cover_letter_writer import DecideOutput, WriteOutput, source_text
from services.llm_structured_client import LLMStructuredCallError, StructuredCallResult

POSTING = {
    "company": "Toss Payments",
    "title": "Server Developer",
    "responsibilities": ["Kafka 기반 결제 이벤트 처리", "주문 처리량을 2배로 늘리는 확장 작업"],
    "conditions": [
        {"type": "REQUIRED", "text": "Spring 기반 서버 개발 경험", "status": "MET", "met_by": [3]},
        {"type": "PREFERRED", "text": "AWS 운영 경험", "status": "MET", "met_by": [2]},
        {"type": "REQUIRED", "text": "Kubernetes 운영 경험 3년 이상", "status": "NOT_MET"},
        {"type": "REQUIRED", "text": "대용량 트래픽 처리 경험", "status": "NOT_MET"},
        {"type": "REQUIRED", "text": "Elasticsearch 운영 경험", "status": "NOT_MET"},
        {"type": "PREFERRED", "text": "Go 언어 경험", "status": "NOT_MET"},
        {"type": "REQUIRED", "text": "Redis 캐시 설계 경험", "status": None},
    ],
}
#: 1 은 공고 조건을 채우지 못한 카드, 2 는 우대 조건만, 3 은 필수 조건을 채운 카드다
SUMMARIES = [
    {"card_id": 1, "title": "API 모양 갈등 조율", "card_kind": "QUALITATIVE", "summary": "API 응답 모양을 두고 갈린 의견을 회의로 조율했다"},
    {"card_id": 2, "title": "배포 시간 단축", "summary": "배포 시간을 40분에서 12분으로 줄였다"},
    {"card_id": 3, "title": "결제 오류 수정", "summary": "결제 중복 요청을 막았다"},
    {"card_id": 4, "title": "동아리 회장", "card_kind": "QUALITATIVE", "summary": "동아리 회장으로 스터디를 운영했다"},
]
SPRING_CARD = {
    "card_id": 3, "title": "결제 오류 수정", "source_name": "shop",
    "statements": [
        {"star_slot": "S", "text": "결제 요청이 두 번 들어와 중복 승인이 났다"},
        {"star_slot": "A", "text": "주문마다 멱등 키를 두어 같은 요청을 한 번만 처리했다"},
        {"star_slot": "R", "text": "중복 승인이 사라졌다"},
    ],
}


def _result(answer: Any) -> StructuredCallResult:
    if isinstance(answer, Exception):
        raise answer
    return StructuredCallResult(output=answer, prompt_tokens=1, completion_tokens=1, latency_ms=1)


class PickClient:
    def __init__(self, picks: dict[str, list[Any]], supervisor: Optional[list[Any]] = None) -> None:
        self._picks = {text: list(answers) for text, answers in picks.items()}
        self._supervisor = list(supervisor or [])
        self._lock = threading.Lock()
        self.pick_payloads: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        with self._lock:
            if output_model is SupervisorOutput:
                answer = self._supervisor.pop(0)
            else:
                self.pick_payloads.append(payload)
                answer = self._picks[payload["question"]["text"]].pop(0)
        return _result(answer)


def _pick(first: Optional[int], second: Optional[int] = None, *, fits: Optional[dict[int, str]] = None,
          first_reason: str = "1순위 이유", second_reason: str = "2순위 이유") -> PickOutput:
    ids = [card for card in (first, second) if card is not None] + [card for card in (fits or {}) if card not in (first, second)]
    return PickOutput(
        question_theme="CONFLICT",
        considered=[ConsideredOutput(card_id=card, note="견줄 만함", fit=(fits or {}).get(card, "MEDIUM")) for card in ids],
        first_card_id=first,
        first_reason=None if first is None else first_reason,
        second_card_id=second,
        second_reason=None if second is None else second_reason,
    )


def _assign(client: PickClient, posting: Optional[dict] = POSTING):
    request = AssignmentRequest.model_validate({
        "cover_letter_id": 9, "job_role": "백엔드 개발", "posting": posting,
        "questions": [{"question_id": 1, "text": "갈등 경험", "char_limit": 700}], "cards": SUMMARIES,
    })
    result, _ = asyncio.run(CoverLetterAssigner(client, client).assign(request))
    return result.assignments[0]


def test_same_fit_second_choice_meeting_required_condition_becomes_primary() -> None:
    """문항에 맞는 정도가 같으면 필수 조건을 채운 2순위가 1순위가 되고, 고른 이유도 그 카드의 이유로 바뀐다."""
    client = PickClient({"갈등 경험": [_pick(1, 3, second_reason="회의로 갈등을 풀고 Spring 조건도 채움")]})

    assignment = _assign(client)

    assert (assignment.primary_card_id, assignment.reason) == (3, "회의로 갈등을 풀고 Spring 조건도 채움")


def test_card_that_fits_the_question_better_is_kept_over_condition() -> None:
    """문항에 더 맞는 카드는 공고 조건을 채운 카드보다 덜 맞아도 공고 때문에 밀리지 않는다."""
    client = PickClient({"갈등 경험": [_pick(1, 3, fits={1: "STRONG", 3: "MEDIUM"})]})

    assert _assign(client).primary_card_id == 1


@pytest.mark.parametrize(
    "first, second, primary",
    [
        pytest.param(1, 2, 2, id="우대만 채운 카드가 못 채운 카드 앞"),
        pytest.param(2, 3, 3, id="필수를 채운 카드가 우대만 채운 카드 앞"),
        pytest.param(3, 2, 3, id="필수를 채운 1순위는 그대로"),
        pytest.param(1, 4, 1, id="둘 다 못 채우면 AI 순서 그대로"),
    ],
)
def test_condition_weight_orders_same_fit_choices(first: int, second: int, primary: int) -> None:
    """등급이 같으면 필수 조건을 채운 카드 → 우대만 채운 카드 → 못 채운 카드 순서로 1순위를 정한다."""
    client = PickClient({"갈등 경험": [_pick(first, second)]})

    assert _assign(client).primary_card_id == primary


def test_without_posting_ai_order_is_kept_and_no_posting_is_sent() -> None:
    """공고 없이 만든 자소서는 AI 순서를 그대로 쓰고, 공고 정보를 모델에 보내지 않는다."""
    client = PickClient({"갈등 경험": [_pick(1, 3)]})

    assert _assign(client, posting=None).primary_card_id == 1
    payload = client.pick_payloads[0]
    assert "posting" not in payload and all("posting_conditions_met" not in card for card in payload["cards"])


@pytest.mark.parametrize(
    "broken",
    [
        pytest.param(_pick(1, fits={1: "WEAK"}), id="WEAK 카드를 1순위로"),
        pytest.param(_pick(1, 3, fits={1: "MEDIUM", 3: "STRONG"}), id="1순위가 가장 맞는 카드가 아님"),
        pytest.param(_pick(1, 2, fits={1: "STRONG", 2: "MEDIUM", 3: "STRONG"}), id="2순위가 나머지 중 가장 맞는 카드가 아님"),
        pytest.param(_pick(1, fits={1: "MEDIUM", 3: "MEDIUM"}), id="같은 등급 카드가 있는데 2순위를 비움"),
    ],
)
def test_choice_that_contradicts_fit_is_asked_again(broken: PickOutput) -> None:
    """공고 조건으로 순서를 바꾸는 규칙이 fit 을 믿으므로, fit 과 어긋난 1·2순위는 한 번 더 묻는다."""
    client = PickClient({"갈등 경험": [broken, _pick(4)]})

    assert _assign(client).primary_card_id == 4
    assert len(client.pick_payloads) == 2


def test_without_posting_fit_order_is_not_checked() -> None:
    """공고 없이 만든 자소서는 순서를 바꾸는 규칙이 없어, fit 과 어긋난 순서도 다시 묻지 않고 받는다."""
    client = PickClient({"갈등 경험": [_pick(1, fits={1: "MEDIUM", 3: "STRONG"})]})

    assert _assign(client, posting=None).primary_card_id == 1
    assert len(client.pick_payloads) == 1


def test_pick_sees_posting_and_only_conditions_each_card_met() -> None:
    """카드 고르기는 공고 회사·제목·주요 업무와 카드마다 채운 조건만 받고, 채우지 못한 조건은 받지 않는다."""
    client = PickClient({"갈등 경험": [_pick(1)]})

    _assign(client)

    payload = client.pick_payloads[0]
    assert payload["posting"] == {"company": "Toss Payments", "title": "Server Developer",
                                  "responsibilities": POSTING["responsibilities"]}
    assert {card["card_id"]: card["posting_conditions_met"] for card in payload["cards"]} == {
        1: [], 2: [{"type": "PREFERRED", "text": "AWS 운영 경험"}],
        3: [{"type": "REQUIRED", "text": "Spring 기반 서버 개발 경험"}], 4: []}
    assert "Kubernetes" not in json.dumps(payload, ensure_ascii=False)


@pytest.mark.parametrize(
    "reason, shown",
    [
        pytest.param("처리량을 2배로 늘리는 업무와 맞음", "처리량을 2배로 늘리는 업무와 맞음", id="주요 업무의 수치"),
        pytest.param("Kubernetes 3년 조건과 이어짐", None, id="조건 문장의 수치"),
    ],
)
def test_reason_may_use_numbers_from_responsibilities_but_not_from_conditions(reason: str, shown: Optional[str]) -> None:
    """고른 이유는 공고 주요 업무의 수치는 근거로 치고, 조건 문장의 수치(연차 등)는 내 사실이 아니라 버린다."""
    client = PickClient({"갈등 경험": [_pick(1, first_reason=reason)]})

    assert _assign(client).reason == shown


@pytest.mark.parametrize("status", [None, "NOT_MET"])
def test_met_by_is_only_allowed_for_met_condition(status: Optional[str]) -> None:
    """판단이 없거나 ✕ 인 조건에 채운 카드를 붙여 보내면 요청을 받지 않는다."""
    with pytest.raises(ValidationError):
        PostingCondition.model_validate({"type": "REQUIRED", "text": "Spring", "status": status, "met_by": [1]})


def test_responsibility_line_over_500_chars_is_rejected() -> None:
    """주요 업무는 한 줄 500자까지만 받는다. 길면 초안 판단에 넘기는 근거 글에서 카드 문장이 잘려 나간다."""
    with pytest.raises(ValidationError):
        Posting.model_validate(POSTING | {"responsibilities": ["가" * 501]})


class AskClient:
    def __init__(self, answers: dict[str, list[Any]]) -> None:
        self._answers = {text: list(items) for text, items in answers.items()}
        self._lock = threading.Lock()
        self.payloads: dict[str, list[dict[str, Any]]] = {}

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        text = payload["question"]["text"]
        with self._lock:
            self.payloads.setdefault(text, []).append(payload)
            answer = self._answers[text].pop(0)
        return _result(answer)


def _asks(*items: str, question: Optional[str] = None) -> AskOutput:
    return AskOutput(asks=[AskItemOutput(item=item, question=question or f"{item} 를 알려 주세요.") for item in items])


def _questions_request(*questions: dict, posting: Optional[dict] = POSTING) -> QuestionsRequest:
    return QuestionsRequest.model_validate({"cover_letter_id": 9, "job_role": "백엔드 개발", "posting": posting,
                                            "questions": list(questions)})


def _q(text: str, theme: Optional[str], *, qid: int, card: Optional[dict] = SPRING_CARD,
       known: Optional[list[dict]] = None) -> dict:
    return {"question_id": qid, "text": text, "char_limit": 700, "question_theme": theme, "primary_card": card,
            "known_answers": known or []}


def _ask(client: AskClient, request: QuestionsRequest):
    return asyncio.run(CoverLetterAsker(client, CoverLetterLimits(target_ratio=0.0)).ask(request))


def test_not_met_required_conditions_are_asked_once_in_motivation_question() -> None:
    """✕ 인 필수 조건은 지원 동기 문항에서만 직무 연결 질문으로 꼭 묻고, 조건은 앞에서부터 2개까지 넘긴다."""
    client = AskClient({"문제 해결 경험": [_asks("LEARNING")], "지원 동기": [_asks("REASON", "JOB_LINK")]})

    result, _ = _ask(client, _questions_request(_q("문제 해결 경험", "PROBLEM_SOLVING", qid=1),
                                                _q("지원 동기", "MOTIVATION", qid=2, card=None)))

    motivation = client.payloads["지원 동기"][0]
    assert (motivation["required"], motivation["posting_gaps"]) == (
        ["REASON", "JOB_LINK"], ["Kubernetes 운영 경험 3년 이상", "대용량 트래픽 처리 경험"])
    other = client.payloads["문제 해결 경험"][0]
    assert "posting_gaps" not in other and "JOB_LINK" not in other["required"]
    assert [a.item for a in result.questions[1].asks] == ["REASON", "JOB_LINK"]


def test_first_question_gets_the_conditions_without_motivation_question() -> None:
    """지원 동기 문항이 없으면 첫 문항이 ✕ 조건을 묻는다."""
    client = AskClient({"문제 해결 경험": [_asks("JOB_LINK")], "협업 경험": [_asks("LEARNING")]})

    _ask(client, _questions_request(_q("문제 해결 경험", "PROBLEM_SOLVING", qid=1), _q("협업 경험", "COLLABORATION", qid=2)))

    assert client.payloads["문제 해결 경험"][0]["required"] == ["JOB_LINK"]
    assert "posting_gaps" not in client.payloads["협업 경험"][0]


def test_condition_question_is_not_left_as_a_draft_blank() -> None:
    """✕ 조건은 해 본 적이 없을 수 있어, 묻기는 하되 초안에 빈칸으로 남길 항목(required_missing)에는 넣지 않는다."""
    client = AskClient({"문제 해결 경험": [_asks("JOB_LINK")]})

    result, _ = _ask(client, _questions_request(_q("문제 해결 경험", "PROBLEM_SOLVING", qid=1)))

    assert result.questions[0].required_missing == []


def test_preferred_and_unjudged_conditions_are_not_asked() -> None:
    """우대 조건이나 판단이 없는 조건은 모자라도 묻지 않는다."""
    posting = POSTING | {"conditions": [c for c in POSTING["conditions"]
                                        if not (c["type"] == "REQUIRED" and c["status"] == "NOT_MET")]}
    client = AskClient({"문제 해결 경험": [_asks("LEARNING")]})

    _ask(client, _questions_request(_q("문제 해결 경험", "PROBLEM_SOLVING", qid=1), posting=posting))

    payload = client.payloads["문제 해결 경험"][0]
    assert "posting_gaps" not in payload and "JOB_LINK" in payload["optional"]


def test_question_with_job_link_answer_is_not_asked_again() -> None:
    """이미 직무 연결 답을 받은 문항은 ✕ 조건 때문에 다시 묻지 않는다."""
    known = [{"item": "JOB_LINK", "text": "결제 서버를 다뤄 보고 싶다", "card_id": None}]
    client = AskClient({"문제 해결 경험": [_asks("LEARNING")]})

    _ask(client, _questions_request(_q("문제 해결 경험", "PROBLEM_SOLVING", qid=1, known=known)))

    payload = client.payloads["문제 해결 경험"][0]
    assert "posting_gaps" not in payload and "JOB_LINK" not in payload["required"]


def test_full_required_list_leaves_no_room_for_conditions() -> None:
    """꼭 물을 항목이 이미 질문 상한(4개)이면 ✕ 조건 질문을 더하지 않는다."""
    client = AskClient({"실패 경험": [_asks("SITUATION", "ACTION", "RESULT", "LEARNING")]})

    _ask(client, _questions_request(_q("실패 경험", "FAILURE", qid=1, card=None)))

    payload = client.payloads["실패 경험"][0]
    assert payload["required"] == ["SITUATION", "ACTION", "RESULT", "LEARNING"] and "posting_gaps" not in payload


@pytest.mark.parametrize(
    "condition, named",
    [
        pytest.param("Kubernetes 운영 경험 3년 이상", "Kubernetes 운영 경험 3년 이상", id="짧은 조건은 그대로"),
        pytest.param("가" * 50, "가" * 39 + "…", id="긴 조건은 줄임"),
    ],
)
def test_template_question_names_the_condition_when_llm_fails(condition: str, named: str) -> None:
    """질문을 못 받으면 틀 질문이 첫 ✕ 조건 이름을 짚어 묻고, 긴 조건은 줄여 짚는다."""
    posting = POSTING | {"conditions": [{"type": "REQUIRED", "text": condition, "status": "NOT_MET"}]}
    client = AskClient({"문제 해결 경험": [LLMStructuredCallError("timeout")] * 2})

    result, _ = _ask(client, _questions_request(_q("문제 해결 경험", "PROBLEM_SOLVING", qid=1), posting=posting))

    asks = result.questions[0]
    assert asks.source == "TEMPLATE"
    assert [a.question for a in asks.asks if a.item == "JOB_LINK"] == [
        f"공고의 '{named}' 조건과 이어지는 경험이나 지금 배우고 있는 것이 있으면 적어 주세요."]


def test_question_may_quote_numbers_from_the_condition() -> None:
    """질문이 짚는 조건의 수치(연차)는 공고가 묻는 것이라 '입력에 없는 수치' 로 다시 묻지 않는다."""
    client = AskClient({"문제 해결 경험": [_asks("JOB_LINK", question="공고의 Kubernetes 3년 조건과 이어지는 경험이 있나요?")]})

    result, _ = _ask(client, _questions_request(_q("문제 해결 경험", "PROBLEM_SOLVING", qid=1)))

    assert (result.questions[0].source, len(client.payloads["문제 해결 경험"])) == ("LLM", 1)


class DraftClient:
    def __init__(self, text: str) -> None:
        self._text = text
        self.write_payloads: list[dict[str, Any]] = []
        self.review_payloads: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        if output_model is WriteOutput:
            self.write_payloads.append(payload)
            return _result(WriteOutput(text=self._text))
        if output_model is DecideOutput:
            raise AssertionError("검사에 걸리지 않는 초안이라 판단을 부르지 않는다")
        self.review_payloads.append(payload)
        return _result(ReviewOutput(findings=[]))


def _drafts_request(card: dict = SPRING_CARD, theme: str = "MOTIVATION") -> DraftsRequest:
    return DraftsRequest.model_validate({
        "cover_letter_id": 9, "job_role": "백엔드 개발", "posting": POSTING,
        "questions": [{"question_id": 1, "text": "지원 동기", "char_limit": 700, "question_theme": theme,
                       "primary_card": card, "answers": [{"item": "REASON", "text": "결제 서버를 다루고 싶다"}]}],
    })


def test_writer_and_review_get_posting_with_only_this_questions_met_conditions() -> None:
    """초안 쓰기는 회사·주요 업무와 이 문항 카드가 채운 조건만 받고 ✕ 조건은 받지 않으며, 전체 검토도 공고를 함께 본다."""
    client = DraftClient("Toss Payments 의 Kafka 기반 결제 이벤트 처리에 기여하고 싶습니다. 주문마다 멱등 키를 두어 중복 승인을 없앴습니다.")

    asyncio.run(CoverLetterDrafter(client, client, CoverLetterLimits(target_ratio=0.0)).draft(_drafts_request()))

    posting = client.write_payloads[0]["posting"]
    assert posting["met_conditions"] == [{"type": "REQUIRED", "text": "Spring 기반 서버 개발 경험"}]
    assert "Kubernetes" not in json.dumps(client.write_payloads[0], ensure_ascii=False)
    assert client.review_payloads[0]["posting"]["responsibilities"] == POSTING["responsibilities"]


def test_company_and_responsibility_words_pass_but_condition_words_are_flagged() -> None:
    """회사 이름·주요 업무의 낱말은 근거로 치고, 조건 문장의 낱말을 답 없이 쓰면 근거에 없는 낱말로 걸린다."""
    request = _drafts_request()
    source = source_text(request, request.questions[0])

    company = check_draft("Toss Payments 의 Kafka 기반 결제 이벤트 처리에 기여하고 싶습니다.",
                          source=source, char_limit=700, missing_items=[])
    condition = check_draft("Kubernetes 를 운영해 왔습니다.", source=source, char_limit=700, missing_items=[])

    assert company == []
    assert [(issue.code, issue.value) for issue in condition] == [("UNSOURCED_TERM", "Kubernetes")]


def test_posting_text_comes_after_card_sentences_in_sources() -> None:
    """다음 행동 판단은 근거 글을 앞에서부터 잘라 받으므로, 공고 글은 카드 문장 뒤에 둔다."""
    request = _drafts_request()
    source = source_text(request, request.questions[0])

    assert source.index("주문마다 멱등 키를 두어") < source.index("Toss Payments")

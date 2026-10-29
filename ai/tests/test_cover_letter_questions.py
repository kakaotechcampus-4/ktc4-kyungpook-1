"""자소서 질문 만들기: 꼭 물을 항목은 코드가 정하고, LLM 은 질문만 쓰며, 답을 못 받으면 틀 질문으로 묻는다."""

from __future__ import annotations

import asyncio
import threading
from typing import Any, Optional

import pytest

from schemas.cover_letter import QuestionsRequest
from services.cover_letter_asker import AskItemOutput, AskOutput, CoverLetterAsker
from services.cover_letter_llm import CoverLetterLimits
from services.llm_structured_client import LLMStructuredCallError, StructuredCallResult

CARD = {
    "card_id": 1, "title": "배포 시간 단축", "source_name": "gitory",
    "statements": [
        {"star_slot": "S", "text": "배포 한 번에 40분이 걸려 하루 배포 횟수가 적었다"},
        {"star_slot": "A", "text": "빌드 캐시를 나눠 바뀐 모듈만 다시 빌드하게 했다"},
    ],
}


class RoutedClient:
    """문항별 호출은 동시에 돌아 순서가 정해지지 않으므로, 문항 문장으로 정해 둔 답(또는 예외)을 차례로 돌려준다."""

    def __init__(self, answers: dict[str, list[Any]]) -> None:
        self._answers = {text: list(items) for text, items in answers.items()}
        self._lock = threading.Lock()
        self.payloads: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        with self._lock:
            self.payloads.append(payload)
            answer = self._answers[payload["question"]["text"]].pop(0)
        if isinstance(answer, Exception):
            raise answer
        return StructuredCallResult(output=answer, prompt_tokens=1, completion_tokens=1, latency_ms=1)


def _question(text: str = "문제 해결 경험", theme: Optional[str] = "PROBLEM_SOLVING", card: Optional[dict] = CARD,
              known: Optional[list[dict]] = None, qid: int = 1) -> dict:
    return {"question_id": qid, "text": text, "char_limit": 700, "question_theme": theme,
            "primary_card": card, "known_answers": known or []}


def _request(*questions: dict, memo: Optional[str] = None) -> QuestionsRequest:
    return QuestionsRequest.model_validate({
        "cover_letter_id": 9, "job_role": "백엔드 개발", "memo": memo, "questions": list(questions),
    })


def _asks(*items: str) -> AskOutput:
    return AskOutput(asks=[AskItemOutput(item=item, question=f"{item} 를 알려 주세요.") for item in items])


#: 글자 수 목표와 상관없는 시험은 재료가 짧은 카드로 쓰므로 목표 비율을 0 으로 둔다
NO_TARGET = CoverLetterLimits(target_ratio=0.0)


def _ask(client: RoutedClient, request: QuestionsRequest, limits: CoverLetterLimits = NO_TARGET):
    return asyncio.run(CoverLetterAsker(client, limits).ask(request))


def test_empty_star_slots_of_primary_card_are_required() -> None:
    """중심 카드에 없는 상황·행동·결과는 꼭 물을 항목이 되고, 배운 점·계기·직무 연결은 더 물어도 되는 항목이다."""
    client = RoutedClient({"문제 해결 경험": [_asks("RESULT", "LEARNING")]})

    result, ended_by = _ask(client, _request(_question()))

    asks = result.questions[0]
    assert (asks.required_missing, [a.item for a in asks.asks], asks.source) == (["RESULT"], ["RESULT", "LEARNING"], "LLM")
    assert (client.payloads[0]["required"], client.payloads[0]["optional"]) == (["RESULT"], ["LEARNING", "TRIGGER", "JOB_LINK"])
    assert ended_by == "COMPLETED"


def test_known_answers_are_not_asked_again() -> None:
    """이미 받은 답이 있는 항목은 꼭 물을 항목에서도, 더 물어도 되는 항목에서도 빠진다."""
    known = [{"item": "RESULT", "text": "배포가 12분으로 줄었다"}, {"item": "LEARNING", "text": "작게 나눠 빌드하기"}]
    client = RoutedClient({"문제 해결 경험": [_asks("JOB_LINK")]})

    result, _ = _ask(client, _request(_question(known=known)))

    assert result.questions[0].required_missing == []
    assert (client.payloads[0]["required"], client.payloads[0]["optional"]) == ([], ["TRIGGER", "JOB_LINK"])


@pytest.mark.parametrize(
    ("memo", "required"), [(None, ["REASON"]), ("  ", ["REASON"]), ("결제처럼 실패하면 안 되는 서비스에 관심", [])],
    ids=["메모 없음", "공백 메모", "메모 있음"],
)
def test_motivation_question_requires_reason_only_without_memo(memo: Optional[str], required: list[str]) -> None:
    """지원 동기 문항은 메모에 지원 이유가 없을 때만 지원 이유를 꼭 묻고, 다른 회사 자소서에 섞일 계기는 묻지 않는다."""
    client = RoutedClient({"지원 동기": [_asks(*required)]})

    result, _ = _ask(client, _request(_question("지원 동기", "MOTIVATION"), memo=memo))

    assert result.questions[0].required_missing == required
    assert client.payloads[0]["optional"] == ["JOB_LINK"]


def test_motivation_is_recognized_from_question_text_when_theme_is_unknown() -> None:
    """카드 고르기를 못 받아 유형이 없어도, 문항 문장이 지원 동기를 물으면 지원 이유를 꼭 묻는다."""
    client = RoutedClient({"우리 회사에 지원한 동기를 적어 주세요": [LLMStructuredCallError("실패")]})

    result, _ = _ask(client, _request(_question("우리 회사에 지원한 동기를 적어 주세요", None, card=None)))

    assert [(a.item, a.question) for a in result.questions[0].asks] == [
        ("REASON", "백엔드 개발 직무로 이 회사에 지원한 이유를 적어 주세요."),
    ]


@pytest.mark.parametrize("theme", ["GROWTH", "FAILURE"])
def test_growth_and_failure_questions_require_learning(theme: str) -> None:
    """성장·실패 문항은 배운 점이 핵심이라 카드·답에 없으면 결과와 함께 꼭 묻고, 더 물어도 되는 항목에서는 뺀다."""
    client = RoutedClient({"성장 경험": [_asks("RESULT", "LEARNING")]})

    result, _ = _ask(client, _request(_question("성장 경험", theme)))

    assert result.questions[0].required_missing == ["RESULT", "LEARNING"]
    assert (client.payloads[0]["required"], client.payloads[0]["optional"]) == (["RESULT", "LEARNING"], ["TRIGGER", "JOB_LINK"])


@pytest.mark.parametrize(
    ("text", "required"),
    [
        ("실패를 딛고 일어선 경험을 적어 주세요", ["RESULT", "LEARNING"]),
        ("본인의 성장 과정을 적어 주세요", ["RESULT", "LEARNING"]),
        ("입사 후 포부와 성장 계획을 적어 주세요", ["REASON"]),
        ("팀으로 함께 성과를 낸 경험을 적어 주세요", ["RESULT"]),
    ],
    ids=["실패", "성장", "지원 동기가 먼저", "그 밖의 경험"],
)
def test_lesson_question_is_recognized_from_question_text_when_theme_is_unknown(text: str, required: list[str]) -> None:
    """유형을 모르면 문항 문장으로 성장·실패 문항을 알아보고 배운 점을 묻되, 지원 동기 문장이면 지원 동기로 본다."""
    client = RoutedClient({text: [LLMStructuredCallError("실패")]})

    result, _ = _ask(client, _request(_question(text, None)))

    assert result.questions[0].required_missing == required


def test_learning_template_question_names_the_card() -> None:
    """LLM 답을 못 받으면 배운 점도 카드 제목을 넣은 틀 질문으로 묻는다."""
    client = RoutedClient({"성장 경험": [LLMStructuredCallError("실패")]})

    result, _ = _ask(client, _request(_question("성장 경험", "GROWTH")))

    assert [(a.item, a.question) for a in result.questions[0].asks][1] == (
        "LEARNING", "'배포 시간 단축' 경험에서 배운 점이나 그 뒤로 달라진 생각은 무엇인가요?",
    )


def test_lesson_question_without_card_asks_all_four_required_items() -> None:
    """카드 없는 성장 문항은 꼭 있어야 하는 항목 넷을 질문 상한 4개 안에서 모두 묻는다."""
    client = RoutedClient({"성장 경험": [_asks("SITUATION", "ACTION", "RESULT", "LEARNING")]})

    result, _ = _ask(client, _request(_question("성장 경험", "GROWTH", card=None)))

    asks = result.questions[0]
    assert asks.required_missing == ["SITUATION", "ACTION", "RESULT", "LEARNING"]
    assert ([a.item for a in asks.asks], asks.source) == (["SITUATION", "ACTION", "RESULT", "LEARNING"], "LLM")
    assert client.payloads[0]["required"] == ["SITUATION", "ACTION", "RESULT", "LEARNING"]


def test_answer_about_supporting_card_does_not_fill_primary_experience() -> None:
    """보조 카드에 대한 결과 답은 중심 경험의 결과가 아니라서, 중심 카드에 결과가 없으면 여전히 꼭 묻는다."""
    supporting = {**CARD, "card_id": 2, "title": "테스트 속도 개선"}
    known = [{"item": "RESULT", "text": "테스트가 3분으로 줄었다", "card_id": 2}]
    question = {**_question(known=known), "supporting_card": supporting}
    client = RoutedClient({"문제 해결 경험": [_asks("RESULT")]})

    result, _ = _ask(client, _request(question))

    assert result.questions[0].required_missing == ["RESULT"]
    assert client.payloads[0]["known_answers"][0]["about"] == "SUPPORTING"


def test_question_without_card_requires_all_star_items() -> None:
    """맞는 카드가 없는 문항은 상황·행동·결과를 모두 물어 답으로 채운다."""
    client = RoutedClient({"협업 경험": [_asks("SITUATION", "ACTION", "RESULT")]})

    result, _ = _ask(client, _request(_question("협업 경험", "COLLABORATION", card=None)))

    assert result.questions[0].required_missing == ["SITUATION", "ACTION", "RESULT"]


@pytest.mark.parametrize(
    "broken",
    [
        pytest.param(_asks("LEARNING"), id="꼭 물을 항목을 빠뜨림"),
        pytest.param(_asks("RESULT", "REASON"), id="경험 문항에서 지원 이유를 물음"),
        pytest.param(_asks("RESULT", "LEARNING", "TRIGGER", "JOB_LINK", "ACTION"), id="질문이 5개"),
        pytest.param(_asks("RESULT", "RESULT"), id="같은 항목을 두 번"),
        pytest.param(AskOutput(asks=[AskItemOutput(item="RESULT", question="배포가 30% 빨라졌나요?")]),
                     id="입력에 없는 수치"),
        pytest.param(AskOutput(asks=[AskItemOutput(item="RESULT", question="가" * 151)]), id="질문이 너무 김"),
        pytest.param(AskOutput(asks=[AskItemOutput(item="RESULT", question=" ")]), id="질문이 비었음"),
        pytest.param(AskOutput(asks=[AskItemOutput(item="RESULT", question="팀원 5명과 무엇을 바꿨나요?")]),
                     id="입력에 없는 단위의 수치"),
    ],
)
def test_rule_breaking_asks_are_asked_again(broken: AskOutput) -> None:
    """질문이 항목 규칙을 어기면 한 번 더 묻고, 규칙에 맞는 답을 쓴다."""
    client = RoutedClient({"문제 해결 경험": [broken, _asks("RESULT")]})

    result, _ = _ask(client, _request(_question()))

    assert [a.item for a in result.questions[0].asks] == ["RESULT"]
    assert len(client.payloads) == 2


def test_template_questions_ask_only_required_items_when_llm_fails() -> None:
    """LLM 답을 못 받으면 꼭 물을 항목만 카드 제목을 넣은 틀 질문으로 묻는다."""
    client = RoutedClient({"문제 해결 경험": [LLMStructuredCallError("실패")]})

    result, ended_by = _ask(client, _request(_question()))

    asks = result.questions[0]
    assert asks.source == "TEMPLATE"
    assert [(a.item, a.question) for a in asks.asks] == [
        ("RESULT", "'배포 시간 단축' 경험의 결과로 무엇이 달라졌나요? 잰 숫자가 있으면 함께 적어 주세요."),
    ]
    assert ended_by == "FALLBACK"


def test_nothing_to_ask_skips_llm() -> None:
    """꼭 물을 항목도, 더 물을 항목도, 더 깊게 물을 항목도 없으면 LLM 을 부르지 않는다."""
    known = [{"item": item, "text": "이미 받은 답"}
             for item in ("SITUATION", "ACTION", "RESULT", "LEARNING", "TRIGGER", "JOB_LINK")]
    client = RoutedClient({})

    result, ended_by = _ask(client, _request(_question(known=known)))

    assert (result.questions[0].asks, result.questions[0].source) == ([], "NONE")
    assert (client.payloads, ended_by) == ([], "COMPLETED")


def test_short_material_asks_as_many_as_needed_up_to_four() -> None:
    """재료가 목표 글자 수(상한의 70%)에 모자라면 답 하나를 100자로 어림해 모자란 만큼, 최대 4개를 묻는다."""
    client = RoutedClient({"문제 해결 경험": [_asks("RESULT", "ACTION", "LEARNING", "JOB_LINK")]})

    result, _ = _ask(client, _request(_question()), CoverLetterLimits())

    payload = client.payloads[0]
    assert (payload["material_chars"], payload["target_chars"], payload["asks_needed"]) == (56, 490, 4)
    assert payload["deepen"] == ["ACTION", "SITUATION"]
    assert [a.item for a in result.questions[0].asks] == ["RESULT", "ACTION", "LEARNING", "JOB_LINK"]


def test_fewer_asks_than_needed_are_asked_again() -> None:
    """재료가 모자란데 필요한 수보다 적게 물으면 한 번 더 묻는다."""
    client = RoutedClient({"문제 해결 경험": [_asks("RESULT"), _asks("RESULT", "ACTION", "LEARNING", "JOB_LINK")]})

    result, _ = _ask(client, _request(_question()), CoverLetterLimits())

    assert len(client.payloads) == 2
    assert len(result.questions[0].asks) == 4


def test_enough_material_asks_only_required_items() -> None:
    """이미 받은 답까지 합쳐 재료가 목표 글자 수를 넘으면 꼭 물을 항목만 물어도 된다."""
    known = [{"item": "LEARNING", "text": "작게 나눠 빌드하는 습관이 생겼다. " * 25}]
    client = RoutedClient({"문제 해결 경험": [_asks("RESULT")]})

    result, _ = _ask(client, _request(_question(known=known)), CoverLetterLimits())

    assert client.payloads[0]["asks_needed"] == 1
    assert [a.item for a in result.questions[0].asks] == ["RESULT"]


def test_card_items_already_answered_are_not_asked_deeper() -> None:
    """카드에 있는 항목도 더 깊게 물을 수 있지만, 그 항목의 답을 이미 받았으면 다시 묻지 않는다."""
    known = [{"item": "ACTION", "text": "모듈마다 캐시 키를 따로 두었다"}]
    client = RoutedClient({"문제 해결 경험": [_asks("RESULT")]})

    _ask(client, _request(_question(known=known)))

    assert client.payloads[0]["deepen"] == ["SITUATION"]


def test_templates_without_blank_or_with_unsourced_number_are_dropped() -> None:
    """빈칸 없는 틀·입력에 없는 수치가 든 틀·너무 긴 틀·같은 틀은 버리고, 남은 틀은 2개까지 준다."""
    templates = [
        "빌드 캐시를 나눈 이유는 ___ 때문이었다",
        "배포가 30% 빨라진 이유는 ___ 이었다",
        "빌드 캐시를 나눴다",
        "빌드 캐시를 나눈 이유는 ___ 때문이었다",
        "___ 때문에 " + "가" * 80,
        "___ 방법도 생각했지만 ___ 때문에 빌드 캐시를 나눴다",
        "바뀐 모듈만 빌드한 뒤 ___ 이 달라졌다",
    ]
    ask = AskItemOutput(item="RESULT", question="결과로 무엇이 달라졌나요?", templates=templates)
    client = RoutedClient({"문제 해결 경험": [AskOutput(asks=[ask])]})

    result, _ = _ask(client, _request(_question()))

    assert result.questions[0].asks[0].templates == [
        "빌드 캐시를 나눈 이유는 ___ 때문이었다", "___ 방법도 생각했지만 ___ 때문에 빌드 캐시를 나눴다",
    ]


def test_template_questions_have_no_templates() -> None:
    """LLM 답을 못 받아 틀 질문으로 물을 때는 지어낸 틀이 없도록 빈칸 틀을 주지 않는다."""
    client = RoutedClient({"문제 해결 경험": [LLMStructuredCallError("실패")]})

    result, _ = _ask(client, _request(_question()))

    assert [a.templates for a in result.questions[0].asks] == [[]]


def test_card_items_left_to_deepen_are_still_asked() -> None:
    """꼭 물을 항목도 더 물을 항목도 없어도, 카드에 한 문장뿐인 항목이 남아 있으면 더 깊게 묻는다."""
    known = [{"item": item, "text": "이미 받은 답"} for item in ("RESULT", "LEARNING", "TRIGGER", "JOB_LINK")]
    client = RoutedClient({"문제 해결 경험": [_asks("ACTION")]})

    result, _ = _ask(client, _request(_question(known=known)))

    assert ([a.item for a in result.questions[0].asks], result.questions[0].source) == (["ACTION"], "LLM")
    assert client.payloads[0]["deepen"] == ["ACTION", "SITUATION"]

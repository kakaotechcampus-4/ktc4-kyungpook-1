"""자소서 질문 만들기: 꼭 물을 항목은 코드가 정하고, LLM 은 질문만 쓰며, 답을 못 받으면 틀 질문으로 묻는다."""

from __future__ import annotations

import asyncio
import threading
from typing import Any, Optional

import pytest

from schemas.cover_letter import QuestionsRequest
from services.cover_letter_asker import AskItemOutput, AskOutput, CoverLetterAsker
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


def _ask(client: RoutedClient, request: QuestionsRequest):
    return asyncio.run(CoverLetterAsker(client).ask(request))


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


def test_lesson_question_without_card_asks_up_to_three_and_reports_all_missing() -> None:
    """카드 없는 성장 문항은 꼭 있어야 하는 항목이 넷이라 앞의 셋만 묻고, 모자란 항목에는 넷을 모두 알려 초안에 빈칸이 남게 한다."""
    client = RoutedClient({"성장 경험": [_asks("SITUATION", "ACTION", "RESULT")]})

    result, _ = _ask(client, _request(_question("성장 경험", "GROWTH", card=None)))

    asks = result.questions[0]
    assert asks.required_missing == ["SITUATION", "ACTION", "RESULT", "LEARNING"]
    assert ([a.item for a in asks.asks], asks.source) == (["SITUATION", "ACTION", "RESULT"], "LLM")
    assert client.payloads[0]["required"] == ["SITUATION", "ACTION", "RESULT"]


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
        pytest.param(_asks("RESULT", "SITUATION"), id="이미 있는 항목을 물음"),
        pytest.param(_asks("RESULT", "LEARNING", "TRIGGER", "JOB_LINK"), id="질문이 4개"),
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
    """꼭 물을 항목도 더 물을 항목도 없으면 LLM 을 부르지 않는다."""
    known = [{"item": item, "text": "이미 받은 답"} for item in ("RESULT", "LEARNING", "TRIGGER", "JOB_LINK")]
    client = RoutedClient({})

    result, ended_by = _ask(client, _request(_question(known=known)))

    assert (result.questions[0].asks, result.questions[0].source) == ([], "NONE")
    assert (client.payloads, ended_by) == ([], "COMPLETED")

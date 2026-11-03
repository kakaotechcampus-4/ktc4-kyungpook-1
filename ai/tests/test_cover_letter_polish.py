"""자소서 첨삭: 지금 글을 진단해 아쉬운 점·잘한 점을 짚고, 초안 쓰기의 고치기 경로로 고쳐 써 같은 검사·표시를 거친다."""

from __future__ import annotations

import asyncio
from typing import Any, Optional

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

import api.cover_letters as cover_letters_api
from main import app
from schemas.cover_letter import PolishRequest
from services.cover_letter_drafter import FindingOutput, ReviewOutput
from services.cover_letter_llm import CoverLetterLLMUnavailable
from services.cover_letter_polish import (
    KEEP_FACTS,
    CoverLetterPolisher,
    DiagnoseOutput,
    PointOutput,
    map_sentences,
)
from services.cover_letter_writer import DecideOutput, DecisionItem, WriteOutput
from services.llm_structured_client import LLMStructuredCallError, StructuredCallResult

DEPLOY_CARD = {
    "card_id": 1, "title": "배포 시간 단축", "source_name": "gitory",
    "statements": [
        {"star_slot": "S", "text": "배포 한 번에 40분이 걸렸다"},
        {"star_slot": "A", "text": "바뀐 모듈만 다시 빌드하도록 배포 순서를 바꿨다"},
        {"star_slot": "R", "text": "배포 시간을 12분으로 줄였다"},
    ],
}
FIRST = "배포 한 번에 40분이 걸려 팀이 기다리는 시간이 길었습니다."
SECOND = "바뀐 모듈만 다시 빌드하도록 배포 순서를 바꾸고 결과를 확인하는 일을 함께 맡아 처리했습니다."
THIRD = "배포 시간을 12분으로 줄였습니다."
CURRENT = f"{FIRST} {SECOND} {THIRD}"
SPLIT_A = "바뀐 모듈만 다시 빌드하도록 배포 순서를 바꿨습니다."
SPLIT_B = "결과를 확인하는 일도 함께 맡았습니다."
REWRITTEN = f"{FIRST} {SPLIT_A} {SPLIT_B} {THIRD}"


class PolishClient:
    """진단·쓰기·다음 행동·감독 답을 종류별로 차례로 돌려주고, 받은 요청을 남긴다. 감독 답을 정하지 않으면 걸린 문장이 없다."""

    def __init__(self, diagnoses: list[Any], writes: Optional[list[Any]] = None,
                 decides: Optional[list[Any]] = None, reviews: Optional[list[Any]] = None) -> None:
        self._answers: dict[type, list[Any]] = {
            DiagnoseOutput: list(diagnoses), WriteOutput: list(writes or []), DecideOutput: list(decides or []),
            ReviewOutput: list(reviews or []),
        }
        self.payloads: dict[type, list[dict[str, Any]]] = {model: [] for model in self._answers}

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        self.payloads[output_model].append(payload)
        queue = self._answers[output_model]
        answer = queue.pop(0) if queue or output_model is not ReviewOutput else ReviewOutput(findings=[])
        if isinstance(answer, Exception):
            raise answer
        if isinstance(answer, str):
            answer = WriteOutput(text=answer)
        return StructuredCallResult(output=answer, prompt_tokens=1, completion_tokens=1, latency_ms=1)

    @property
    def writes(self) -> list[dict[str, Any]]:
        return self.payloads[WriteOutput]


def _point(kind: str = "WEAK", category: str = "READABILITY", seqs: tuple = (), title: str = "한 문장이 길어요",
           detail: str = "두 문장으로 나누면 읽기 쉬워요") -> PointOutput:
    return PointOutput(kind=kind, category=category, title=title, detail=detail, sentence_seqs=list(seqs))


def _diagnosis(*points: PointOutput, summary: str = "배포를 줄인 과정이 잘 보입니다.") -> DiagnoseOutput:
    return DiagnoseOutput(summary=summary, points=list(points))


def _payload(current: str = CURRENT, *, others: Optional[list[dict]] = None, inferred: tuple = (),
             **extra: Any) -> dict:
    return {
        "cover_letter_id": 9, "job_role": "백엔드 개발", "memo": None, "others": others or [],
        "target": {"question_id": 1, "text": "어려운 문제를 해결한 경험", "char_limit": 700,
                   "question_theme": "PROBLEM_SOLVING", "primary_card": DEPLOY_CARD, "answers": [],
                   "current_text": current, "inferred_seqs": list(inferred)},
        **extra,
    }


def _polish(client: PolishClient, payload: Optional[dict] = None):
    request = PolishRequest.model_validate(payload or _payload())
    return asyncio.run(CoverLetterPolisher(client, client).polish(request))


OTHER = {"question_id": 2, "text": "협업 경험", "current_text": f"{FIRST} 팀원과 배포 순서를 정했습니다."}


def test_request_with_unknown_field_is_rejected_with_400() -> None:
    """계약에 없는 칸을 보내면 조용히 버리지 않고 400 INVALID_PAYLOAD 다."""
    response = TestClient(app).post("/internal/cover-letter-polish", json=_payload(extra_field=1))

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_PAYLOAD"


def test_polish_api_returns_summary_points_and_rewritten_draft(monkeypatch: pytest.MonkeyPatch) -> None:
    """첨삭 API 는 종합 평가·포인트·고친 글·단계를 공통 응답 형식으로 돌려준다."""
    client = PolishClient([_diagnosis(_point(seqs=(2,)))], writes=[REWRITTEN])
    monkeypatch.setattr(cover_letters_api, "_polisher", CoverLetterPolisher(client, client))

    response = TestClient(app).post("/internal/cover-letter-polish", json=_payload())

    body = response.json()
    assert response.status_code == 200
    assert (body["data"]["draft"]["text"], body["meta"]["ended_by"]) == (REWRITTEN, "COMPLETED")
    assert body["data"]["points"][0]["sentence_seqs"] == [2, 3]


def test_llm_failure_returns_503_llm_unavailable(monkeypatch: pytest.MonkeyPatch) -> None:
    """진단 답을 못 받으면 지금 글로 대신하지 않고 503 LLM_UNAVAILABLE(retryable) 이다."""
    failure = LLMStructuredCallError("LLM 호출 실패")
    client = PolishClient([failure, failure])
    monkeypatch.setattr(cover_letters_api, "_polisher", CoverLetterPolisher(client, client))

    response = TestClient(app).post("/internal/cover-letter-polish", json=_payload())

    assert response.status_code == 503
    assert (response.json()["error"]["code"], response.json()["error"]["retryable"]) == ("LLM_UNAVAILABLE", True)


def test_rewrite_failure_is_not_hidden() -> None:
    """고쳐 쓰기 답을 못 받으면 지금 글을 고친 글로 내지 않고 실패를 올린다."""
    failure = LLMStructuredCallError("LLM 호출 실패")
    client = PolishClient([_diagnosis(_point(seqs=(2,)))], writes=[failure, failure])

    with pytest.raises(CoverLetterLLMUnavailable):
        _polish(client)


def test_weak_and_good_points_are_capped_with_weak_first() -> None:
    """아쉬운 점은 4개, 잘한 점은 3개까지 남기고, 아쉬운 점을 먼저 1부터 번호를 붙인다."""
    good = [_point("GOOD", "FIT", (1,), title=f"잘한 점 {n}") for n in "가나다라"]
    weak = [_point("WEAK", "READABILITY", (2,), title=f"아쉬운 점 {n}") for n in "가나다라마"]
    client = PolishClient([_diagnosis(*good, *weak)], writes=[REWRITTEN])

    result = _polish(client)

    assert [(p.seq, p.kind) for p in result.points] == [(1, "WEAK"), (2, "WEAK"), (3, "WEAK"), (4, "WEAK"),
                                                         (5, "GOOD"), (6, "GOOD"), (7, "GOOD")]
    assert [p.title for p in result.points][:4] == ["아쉬운 점 가", "아쉬운 점 나", "아쉬운 점 다", "아쉬운 점 라"]


def test_category_outside_the_five_is_rejected() -> None:
    """분류는 FIT·EVIDENCE·READABILITY·OVERLAP·TECH_TERMS 다섯 개만 받는다."""
    with pytest.raises(ValidationError):
        _point(category="STYLE")


def test_point_sentences_are_moved_to_rewritten_draft() -> None:
    """포인트 문장 번호는 고친 글 기준이다: 남은 문장은 그 자리, 나눈 문장은 나뉜 둘, 지운 문장은 빈 목록."""
    current = f"{CURRENT} 팀이 덜 기다리게 되었습니다."
    client = PolishClient([_diagnosis(_point(seqs=(2,)), _point(category="EVIDENCE", seqs=(4,)),
                                      _point("GOOD", "FIT", (3,)))], writes=[REWRITTEN])

    result = _polish(client, _payload(current))

    assert [p.sentence_seqs for p in result.points] == [[2, 3], [], [4]]
    assert all(seq in {s.seq for s in result.draft.sentences} for p in result.points for seq in p.sentence_seqs)


def test_sentence_map_keeps_unchanged_and_spreads_replaced_sentences() -> None:
    """바뀐 문장 묶음은 대신한 문장들에 고르게 옮기고, 둘을 하나로 합치면 둘 다 그 하나로 간다."""
    assert map_sentences(["가.", "나.", "다."], ["가.", "나1.", "나2.", "다."]) == {1: [1], 2: [2, 3], 3: [4]}
    assert map_sentences(["가.", "나.", "다."], ["가.", "나다."]) == {1: [1], 2: [2], 3: [2]}


def test_weak_points_and_code_issues_become_rewrite_problems() -> None:
    """아쉬운 점(걸린 문장 글을 붙여)과 검사기 문제가 쓰기 AI 의 fix.problems 로 가고, 앞 글은 지금 글이다."""
    current = f"{CURRENT} Kubernetes 로 옮겼습니다."
    client = PolishClient([_diagnosis(_point(seqs=(2,)))], writes=[REWRITTEN],
                          decides=[DecideOutput(decisions=[DecisionItem(index=1, action="FIX")])])

    _polish(client, _payload(current))

    fix = client.writes[0]["fix"]
    assert fix["previous_draft"] == current
    assert fix["problems"] == [
        f"한 문장이 길어요: 두 문장으로 나누면 읽기 쉬워요 (고칠 문장: '{SECOND}')",
        "'Kubernetes' 는 입력에 없는 낱말입니다. 빼거나 입력에 있는 표현으로 바꾸세요.",
        KEEP_FACTS,
    ]


def test_nothing_to_fix_keeps_text_without_rewriting() -> None:
    """아쉬운 점도 검사기 문제도 없으면 고쳐 쓰지 않고 지금 글을 그대로 돌려준다."""
    client = PolishClient([_diagnosis(_point("GOOD", "FIT", (3,)))])

    result = _polish(client)

    assert (result.draft.text, client.writes, result.points[0].sentence_seqs) == (CURRENT, [], [3])


def test_unchanged_sentence_without_mark_is_not_marked_again() -> None:
    """표시 없는 문장(확인한 짐작·사실)이 고친 글에 그대로 남으면 INFERRED 를 다시 달지 않고, 새 짐작 문장에만 단다."""
    confirmed = "그 일로 기다림을 줄이는 일이 팀에 중요하다고 느꼈습니다."
    guess = "앞으로도 작은 불편을 먼저 찾겠습니다."
    current = f"{CURRENT} {confirmed}"
    rewritten = f"{REWRITTEN} {confirmed} {guess}"
    client = PolishClient([_diagnosis(_point(seqs=(2,)))],
                          writes=[WriteOutput(text=rewritten, inferred=[confirmed, guess])])

    result = _polish(client, _payload(current))

    inferred = [mark.sentence_seq for mark in result.draft.marks if mark.code == "INFERRED"]
    assert inferred == [6]
    assert client.writes[0]["fix"]["previous_inferred"] == []


def test_unconfirmed_inferred_sentence_is_told_to_the_writer() -> None:
    """아직 확인하지 않은 짐작 문장은 앞 글의 추론 문장(previous_inferred)으로 알려 준다."""
    guess = "그 일로 기다림을 줄이는 일이 팀에 중요하다고 느꼈습니다."
    client = PolishClient([_diagnosis(_point(seqs=(2,)))], writes=[REWRITTEN])

    _polish(client, _payload(f"{CURRENT} {guess}", inferred=(4,)))

    assert client.writes[0]["fix"]["previous_inferred"] == [guess]


def test_unconfirmed_inferred_sentence_left_unchanged_keeps_its_mark() -> None:
    """확인하지 않은 짐작 문장이 고친 글에 그대로 남으면 쓰기 AI 가 다시 밝히지 않아도 INFERRED 표시를 남긴다."""
    guess = "그 일로 기다림을 줄이는 일이 팀에 중요하다고 느꼈습니다."
    client = PolishClient([_diagnosis(_point(seqs=(2,)))], writes=[f"{REWRITTEN} {guess}"])

    result = _polish(client, _payload(f"{CURRENT} {guess}", inferred=(4,)))

    assert [(m.code, m.sentence_seq) for m in result.draft.marks] == [("INFERRED", 5)]


def test_rewritten_sentence_flagged_as_inferred_by_supervisor_carries_the_mark() -> None:
    """고친 글을 감독이 읽어, 쓰기 AI 가 밝히지 않은 해석 문장에 INFERRED 표시를 단다(다시 쓰게 하지는 않음)."""
    guess = "그 일로 기다림을 줄이는 일이 팀에 중요하다고 생각했습니다."
    review = ReviewOutput(findings=[FindingOutput(question_id=1, sentence_seq=5, code="INFERRED", reason="근거에 없는 해석")])
    client = PolishClient([_diagnosis(_point(seqs=(2,)))], writes=[f"{REWRITTEN} {guess}"], reviews=[review])

    result = _polish(client)

    assert [(m.code, m.sentence_seq) for m in result.draft.marks] == [("INFERRED", 5)]
    assert len(client.writes) == 1
    assert client.payloads[ReviewOutput][0]["questions"][0]["sentences"][4]["text"] == guess


def test_unsourced_number_in_rewritten_text_is_still_caught() -> None:
    """고친 글에 근거 없는 숫자가 들어오면 첫 초안처럼 다시 고치게 하고, 끝까지 남으면 UNSOURCED_NUMBER 로 표시한다."""
    invented = f"{REWRITTEN} 실패율이 99% 줄었습니다."
    client = PolishClient([_diagnosis(_point(seqs=(2,)))], writes=[invented, invented],
                          decides=[DecideOutput(decisions=[DecisionItem(index=1, action="FIX")])])

    result = _polish(client)

    assert len(client.writes) == 2
    assert client.writes[1]["fix"]["problems"] == ["'99%' 는 입력에 없는 숫자입니다. 빼거나 입력에 있는 숫자로 바꾸세요."]
    assert [(m.code, m.value, m.sentence_seq) for m in result.draft.marks] == [("UNSOURCED_NUMBER", "99%", 5)]


def test_overlap_with_other_question_is_sent_back_and_marked_if_left() -> None:
    """다른 문항과 겹친 문장은 되풀이로 고치게 하고, 고친 글에 그대로 남으면 REPEATED 로 표시한다."""
    client = PolishClient([_diagnosis(_point(category="OVERLAP", seqs=(1,), title="다른 문항과 첫 문장이 같아요"))],
                          writes=[REWRITTEN])

    result = _polish(client, _payload(others=[OTHER]))

    problems = client.writes[0]["fix"]["problems"]
    assert problems[0].startswith("1번 문장이 다른 문항과 같은 이야기입니다(다른 문항과 첫 문장이 같아요)")
    assert client.payloads[DiagnoseOutput][0]["others"] == [{"question": "협업 경험", "text": OTHER["current_text"],
                                                             "before": False}]
    assert [(m.code, m.sentence_seq) for m in result.draft.marks] == [("REPEATED", 1)]
    assert (result.points[0].category, result.points[0].sentence_seqs) == ("OVERLAP", [1])


def test_overlap_without_other_questions_is_dropped() -> None:
    """다른 문항이 없으면 겹침은 걸 수 없어 그 포인트를 버린다."""
    client = PolishClient([_diagnosis(_point(category="OVERLAP", seqs=(1,)), _point("GOOD", "FIT", (1,)))])

    result = _polish(client)

    assert [p.category for p in result.points] == ["FIT"]
    assert client.writes == []


def test_diagnosis_pointing_at_missing_sentence_is_asked_again() -> None:
    """지금 글에 없는 문장 번호를 짚은 진단은 한 번 더 받는다."""
    client = PolishClient([_diagnosis(_point(seqs=(9,))), _diagnosis(_point("GOOD", "FIT", (1,)))])

    result = _polish(client)

    assert (len(client.payloads[DiagnoseOutput]), result.points[0].kind) == (2, "GOOD")


def test_strengths_go_to_diagnosis_and_rewrite_of_a_strength_question() -> None:
    """역량·강점을 묻는 문항을 첨삭하면 고른 강점을 진단과 고쳐 쓰기에 화면 이름으로 그대로 넘긴다."""
    client = PolishClient([_diagnosis(_point(seqs=(2,)))], writes=[REWRITTEN])
    payload = _payload(strengths=["PERSISTENT"])
    payload["target"]["text"] = "본인의 강점을 보여 주는 경험"

    _polish(client, payload)

    assert (client.payloads[DiagnoseOutput][0]["strengths"], client.writes[0]["strengths"]) == (["끈기"], ["끈기"])

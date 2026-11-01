"""STAR 초안 진행 스트리밍: 칸 글은 문장이 끝날 때만 칸마다 0.5초에 한 번까지 보내고, 단계는 생기는 대로 보낸다."""

from __future__ import annotations

import asyncio
import json
from typing import Any

from fastapi.testclient import TestClient

from api.analysis import get_analysis_pipeline
from main import create_app
from schemas.analysis import StarAnalysisRequest
from services.analysis_pipeline import AnalysisPipeline
from services.llm_structured_client import StructuredCallResult
from services.progress import listening, slot_item
from services.star_generator import StarDraftOutput, StarField, StarGenerationLimits, StarGenerator
from services.star_progress import SlotStreamer

SHA = "a" * 40
SECRET = "ghp_" + "a" * 30
SITUATION = "결제 승인이 두 번 나갔다. 같은 주문이었다."
ACTION = "주문마다 멱등 키를 두었다."

REQUEST: dict[str, Any] = {
    "candidate_id": "c1",
    "target_login": "octo",
    "title": "결제 중복 승인 막기",
    "source_type": "PR",
    "source_ref": "PR #31",
    "evidence": [{
        "sha": SHA,
        "message": "fix: 결제 승인에 멱등 키 두기",
        "author_login": "octo",
        "churn": "+40/-3",
        "summary": "결제 승인에 멱등 키를 둔다",
        "summary_source": "DIFF",
    }],
}


class _StreamClient:
    """미리 정한 (출력, 부분 출력들)을 차례로 돌려주고, on_partial 을 받았으면 부분 출력을 먼저 흘려준다."""

    def __init__(self, *responses: tuple[Any, list[dict[str, Any]]]) -> None:
        self._responses = list(responses)
        self.partial_callbacks: list[Any] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None,
             on_partial=None):
        self.partial_callbacks.append(on_partial)
        output, partials = self._responses.pop(0)
        if on_partial is not None:
            for partial in partials:
                on_partial(partial)
        return StructuredCallResult(output=output, prompt_tokens=1, completion_tokens=1, latency_ms=1)


def _field(text: str) -> StarField:
    return StarField(status="FILLED", text=text, evidence_shas=[SHA], insufficient_reason=None)


def _draft(**fields: StarField) -> StarDraftOutput:
    empty = StarField(status="EMPTY", text=None, evidence_shas=[], insufficient_reason="근거 없음")
    return StarDraftOutput(**{slot: fields.get(slot, empty) for slot in ("S", "T", "A", "R")})


DRAFT_PARTIALS = [
    {"S": {"status": "FILLED", "text": "결제 승인이"}},
    {"S": {"status": "FILLED", "text": "결제 승인이 두 번 나갔다. 같은"}},
    {"S": {"status": "FILLED", "text": SITUATION}},
    {"S": {"status": "FILLED", "text": SITUATION, "evidence_shas": []}},
    {"S": {"text": SITUATION}, "T": {"status": "EMPTY", "text": None}},
    {"S": {"text": SITUATION}, "T": {"text": None}, "A": {"status": "FILLED", "text": ACTION}},
]


def _post(path: str, client: _StreamClient):
    app = create_app()
    app.dependency_overrides[get_analysis_pipeline] = lambda: AnalysisPipeline(star_generator=StarGenerator(client))
    return TestClient(app).post(path, json=REQUEST)


def _draft_client() -> _StreamClient:
    return _StreamClient((_draft(S=_field(SITUATION), A=_field(ACTION)), DRAFT_PARTIALS))


def test_stream_sends_slot_text_by_sentence_then_steps_then_the_same_result_as_star() -> None:
    """칸 글은 끝난 문장까지만 보내 문장 중간에서 멈춘 글이 나가지 않고, 마지막 줄은 /star 응답과 같다."""
    response = _post("/internal/analysis/star/stream", _draft_client())
    plain = _post("/internal/analysis/star", _draft_client())

    lines = [json.loads(line) for line in response.text.splitlines()]
    items = [line["item"] for line in lines if line["type"] == "progress"]
    slots = [(item["round"], item["slot"], item["text"], item["done"]) for item in items if item["kind"] == "SLOT"]
    steps = [item["step"] for item in items if item["kind"] == "STEP"]
    assert all(line["step"] == "STAR" for line in lines if line["type"] == "progress")
    assert slots == [
        (0, "S", "결제 승인이 두 번 나갔다.", False),
        (0, "S", SITUATION, True),
        (0, "A", ACTION, False),
        (0, "T", "", True),
        (0, "A", ACTION, True),
        (0, "R", "", True),
    ]
    assert lines[-1]["type"] == "result"
    assert lines[-1]["status"] == 200
    assert lines[-1]["body"]["data"] == plain.json()["data"]
    assert steps == lines[-1]["body"]["data"]["steps"]


def test_slot_text_is_sent_at_most_every_half_second_per_slot() -> None:
    """같은 칸에서 0.5초 안에 문장이 또 끝나면 그 사이 줄은 건너뛰고, 다른 칸은 따로 센다."""
    sent: list[tuple[str, str]] = []
    now = [0.0]
    streamer = SlotStreamer(0, ("S", "T", "A", "R"), clock=lambda: now[0])

    with listening(lambda step, done, total, item: sent.append((item["slot"], item["text"]))):
        streamer.feed({"S": {"text": "첫 문장이다. "}})
        now[0] = 0.3
        streamer.feed({"S": {"text": "첫 문장이다. 둘째 문장이다. "}})
        now[0] = 0.6
        streamer.feed({"S": {"text": "첫 문장이다. 둘째 문장이다. 셋째 문장이다."}})

    assert sent == [("S", "첫 문장이다."), ("S", "첫 문장이다. 둘째 문장이다. 셋째 문장이다.")]


def test_a_slot_ends_as_soon_as_its_evidence_list_or_the_next_slot_starts() -> None:
    """호출이 끝나기를 기다리지 않고, 근거 목록을 쓰기 시작하거나 다음 칸으로 넘어가면 그 칸의 완성된 글을 바로 보낸다."""
    sent: list[tuple[str, str, bool]] = []
    streamer = SlotStreamer(0, ("S", "T", "A", "R"), clock=lambda: 0.0)

    with listening(lambda step, done, total, item: sent.append((item["slot"], item["text"], item["done"]))):
        streamer.feed({"S": {"text": "상황이다", "evidence_shas": []}})
        after_evidence = list(sent)
        streamer.feed({"S": {"text": "상황이다"}, "T": {"text": "과제이다"}, "A": {"text": "행동"}})

    assert after_evidence == [("S", "상황이다", True)]
    assert sent == [("S", "상황이다", True), ("T", "과제이다", True)]


def test_rewrite_round_streams_only_the_rewritten_slots_after_the_first_steps() -> None:
    """검사에 걸린 칸을 다시 쓸 때는 그 칸만 바퀴 번호와 함께 보내고, 앞 바퀴의 단계는 다시 쓰기 전에 이미 나가 있다."""
    writer = _StreamClient(
        (_draft(A=_field("세션 조회를 50% 줄였다.")), []),
        (_draft(A=_field("세션 조회를 줄였다.")), [{"A": {"text": "세션 조회를"}}, {"A": {"text": "세션 조회를 줄였다."}}]),
    )
    lines: list[dict[str, Any]] = []
    generator = StarGenerator(writer, StarGenerationLimits(max_repair_rounds=1), repair=True)

    with listening(lambda step, done, total, item: lines.append(item)):
        asyncio.run(generator.generate(StarAnalysisRequest.model_validate(REQUEST)))

    order = [
        (item["kind"], item["round"], item["slot"], item["done"]) if item["kind"] == "SLOT"
        else (item["kind"], item["step"]["round"], item["step"]["slot"], item["step"]["kind"])
        for item in lines
    ]
    assert order == [
        ("SLOT", 0, "S", True), ("SLOT", 0, "T", True), ("SLOT", 0, "A", True), ("SLOT", 0, "R", True),
        ("STEP", 0, None, "DRAFT"),
        ("STEP", 0, "S", "FLAG"), ("STEP", 0, "T", "FLAG"), ("STEP", 0, "A", "FLAG"), ("STEP", 0, "R", "FLAG"),
        ("SLOT", 1, "A", False), ("SLOT", 1, "A", True),
        ("STEP", 1, "A", "REWRITE"),
        ("STEP", 1, "A", "PASS"),
    ]


def test_slot_text_hides_secrets_before_cutting_to_four_hundred_chars() -> None:
    item = slot_item(0, "A", f"토큰 {SECRET} 를 넣었다. " + "가" * 500, True)

    assert SECRET not in item["text"]
    assert len(item["text"]) == 400


def test_without_a_listener_the_client_gets_no_partial_callback() -> None:
    """스트리밍 API 가 듣지 않는 /star 는 지금처럼 부분 출력 없이 부른다."""
    client = _draft_client()

    asyncio.run(StarGenerator(client).generate(StarAnalysisRequest.model_validate(REQUEST)))

    assert client.partial_callbacks == [None]

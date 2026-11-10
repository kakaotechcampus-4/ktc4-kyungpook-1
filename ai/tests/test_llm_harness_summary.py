"""측정 요약 파일(record_summary)이 이름·내용·덮어쓰기·비밀 가리기 규칙을 지키는지 본다."""

from __future__ import annotations

import json
import re
import sys
from datetime import datetime

import pytest

from evals.common import llm_harness


@pytest.fixture
def history(tmp_path, monkeypatch):
    monkeypatch.setattr(llm_harness, "HISTORY_DIR", tmp_path / "history")
    return tmp_path / "history"


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def test_파일_이름은_날짜_평가_이름표이고_안전한_글자만_쓴다(history):
    path = llm_harness.record_summary("Interview Asks", "After/2 차", {"n": 1},
                                      measured_at=datetime(2026, 11, 9, 19, 16, 10))

    assert path.parent == history
    assert re.fullmatch(r"2026-11-09-[a-z0-9._-]+-[a-z0-9._-]+\.json", path.name)
    assert path.name.startswith("2026-11-09-interview-asks-after-2")


def test_요약에는_정해진_키가_모두_있다(history):
    path = llm_harness.record_summary("assistant", "after", {"ok": "44/44"}, sample="사례 44개", model="m", calls=3,
                                      spend_won=1.234, measured_at=datetime(2026, 11, 9, 1, 2, 3), note="메모")
    body = read(path)

    assert list(body) == ["date", "measured_at", "eval", "label", "sample", "model", "git_sha", "calls", "spend_won",
                          "metrics", "note"]
    assert (body["date"], body["measured_at"]) == ("2026-11-09", "2026-11-09T01:02:03")
    assert body["spend_won"] == 1.23 and body["metrics"] == {"ok": "44/44"}
    assert body["git_sha"]


def test_같은_날_같은_측정을_다시_하면_덮어쓴다(history):
    when = datetime(2026, 11, 9, 9, 0, 0)
    first = llm_harness.record_summary("assistant", "after", {"ok": 1}, measured_at=when)
    second = llm_harness.record_summary("assistant", "after", {"ok": 2}, measured_at=when)

    assert first == second
    assert len(list(history.glob("*.json"))) == 1
    assert read(second)["metrics"] == {"ok": 2}


def test_JSON_으로_쓸_수_없는_값과_긴_글은_뺀다(history):
    metrics = {"n": 3, "ratio": 0.5, "nan": float("nan"), "obj": object(), "long": "가" * 300, "ok": ["a", 1, {"b": 2}],
               "nested": {"fn": print, "keep": True}, 5: "숫자 키"}
    body = read(llm_harness.record_summary("assistant", "x", metrics))["metrics"]

    assert body == {"n": 3, "ratio": 0.5, "ok": ["a", 1, {"b": 2}], "nested": {"keep": True}}


def test_비밀값과_이메일은_가려서_쓴다(history):
    secret = "sk-" + "a" * 30
    body = read(llm_harness.record_summary("assistant", "x", {"wrong": [f"key {secret}", "who@example.com"]},
                                           note=f"token {secret}"))
    text = json.dumps(body, ensure_ascii=False)

    assert secret not in text and "who@example.com" not in text
    assert "[REDACTED]" in text and "[이메일]" in text


def test_뒤늦게_만든_요약은_git_sha_가_null_이고_안내가_붙는다(history, monkeypatch):
    monkeypatch.setenv("GITORY_EVAL_BACKFILL", "1")
    body = read(llm_harness.record_summary("assistant", "old", {"ok": 1}, note="무시됨",
                                           measured_at=datetime(2026, 11, 9, 9, 0, 0)))

    assert body["git_sha"] is None
    assert body["note"] == llm_harness.BACKFILL_NOTE


def test_interview_asks_보고는_요약_파일을_남긴다(history, tmp_path, monkeypatch):
    from evals.interview_asks import measure

    row = {"id": "c1", "sample": "templates", "source_type": "ACTIVITY", "answers": ["답"], "bodies": {},
           "allowed": ["S"], "next_action": "ASK_AGAIN", "slot": "S", "question": "그때 무엇을 했나요?",
           "suggestions": ["후보"], "reason": "", "fell_back": False, "warnings": [],
           "calls": [{"output": {"action": "ASK", "question": "그때 무엇을 했나요?", "star_slot": "S",
                                 "suggested_answers": ["후보"]}, "won": 1.5}]}
    run_dir = tmp_path / "runs"
    run_dir.mkdir()
    (run_dir / "after.json").write_text(json.dumps({"model": "m", "rows": [row]}, ensure_ascii=False), encoding="utf-8")
    monkeypatch.setattr(measure, "RUN_DIR", run_dir)

    measure.report()
    written = list(history.glob("*-interview_asks-after.json"))

    assert len(written) == 1
    body = read(written[0])
    assert body["metrics"]["all"]["cards"] == 1 and body["metrics"]["all"]["asked_again"] == 0
    assert body["calls"] == 1 and body["spend_won"] == 1.5


def test_가림_도구를_못_불러오면_글은_버리고_숫자만_남긴다(history, monkeypatch):
    monkeypatch.setitem(sys.modules, "services.secret_redaction", None)
    path = llm_harness.record_summary("demo", "after", {"ratio": 0.5, "label": "sk-abcdefghijklmnop"})
    metrics = json.loads(path.read_text(encoding="utf-8"))["metrics"]
    assert metrics == {"ratio": 0.5}

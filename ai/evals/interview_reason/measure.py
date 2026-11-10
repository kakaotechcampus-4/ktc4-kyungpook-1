"""되묻기 판단 이유 측정 (#480). 같은 20장으로 기준선(이유 없는 지금 프롬프트)과 이유를 쓰는 프롬프트를 견준다.

  run    : 카드마다 운영과 같은 InterviewAgent.next_turn 을 실제 모델로 --repeat 번 부르고 results/interview_reason/<label>.json 에 남긴다
  report : 두 실행의 칸 고르기·끝내기 정확도, 이유 비율, 비용·시간을 견주고 이유 채점표(grades.md)를 만든다
           grades.md 가 이미 있으면 '맞음' 칸에 적은 O/X 를 읽어 '이유가 고른 행동과 맞음' 비율을 낸다

표본 cases.json 은 직접 쓴 가상의 개발 경험 카드 20장이다(SHORT 10 · FULL 10). baseline 은 이유를 넣기 전 코드에서,
reason 은 이유를 넣은 코드에서 돌려야 해서 코드 판을 바꿔 가며 같은 명령을 한 번씩 돌린다. 결과 원본은 커밋하지 않는다.

    cd ai
    python evals/interview_reason/measure.py run --label baseline --dry-run
    python evals/interview_reason/measure.py run --label reason [--repeat 2] [--model gpt-5.6-luna]
    python evals/interview_reason/measure.py report
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import sys
import time
from pathlib import Path
from typing import Any

_AI_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_AI_ROOT))

from evals.common import llm_harness  # noqa: E402

HERE = Path(__file__).resolve().parent
RUN_DIR = _AI_ROOT / "evals" / "results" / "interview_reason"
CASES = HERE / "cases.json"
GRADES = RUN_DIR / "grades.md"

class Recording:
    """실제 호출기를 감싸 호출마다 토큰·지연·모델이 쓴 원래 답을 남긴다."""

    def __init__(self, client: Any, model: str) -> None:
        self._client = client
        self.model = model
        self.calls: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        started = time.perf_counter()
        try:
            result = self._client.call(system_prompt, payload, output_model, max_completion_tokens, timeout_seconds)
        except Exception as exc:
            self.calls.append({"error": type(exc).__name__, "ms": round((time.perf_counter() - started) * 1000)})
            raise
        price_in, price_out = llm_harness.price(self.model)
        self.calls.append({
            "prompt_tokens": result.prompt_tokens, "completion_tokens": result.completion_tokens,
            "won": ((result.prompt_tokens or 0) * price_in + (result.completion_tokens or 0) * price_out) / 1_000_000,
            "ms": result.latency_ms, "output": result.output.model_dump(),
        })
        return result


def cases() -> list[dict[str, Any]]:
    return json.loads(CASES.read_text(encoding="utf-8"))["cases"]


def request_of(case: dict[str, Any], card_id: int):
    """Spring 이 보내는 모양: 네 칸 모두 문장과 함께, 고를 수 있는 칸은 같은 칸 2번·넘김 칸을 뺀 것."""
    from schemas.interview import InterviewTurnRequest
    previous = case["previous"]
    allowed = [
        slot for slot in "STAR"
        if sum(1 for turn in previous if turn[0] == slot) < 2
        and not any(turn[0] == slot and turn[2] in ("SKIPPED", "NOT_MEASURED") for turn in previous)
    ]
    return InterviewTurnRequest.model_validate({
        "card_id": card_id,
        "source_type": "DIRECT_CARD",
        "max_turns": 8,
        "existing_turn_count": len(previous),
        "missing_slots": [
            {"star_slot": slot, "statement_seq": 1, "body": case["slots"][slot], "confidence": "HIGH"}
            for slot in "STAR"
        ],
        "allowed_slots": allowed,
        "previous_turns": [
            {"star_slot": slot, "question_text": question, "outcome": outcome, "answer_text": answer}
            for slot, question, outcome, answer in previous
        ],
    })


async def run(label: str, repeat: int, model: str) -> None:
    from services.interview_agent import InterviewAgent
    from services.interview_llm import InterviewLLM
    from services.llm_settings import EnvironmentLLMClient
    os.environ["GITORY_INTERVIEW_MODEL"] = model
    rows: list[dict[str, Any]] = []
    for index, case in enumerate(cases(), start=1):
        for attempt in range(1, repeat + 1):
            recording = Recording(EnvironmentLLMClient("INTERVIEW", default_reasoning_effort="low"), model)
            agent = InterviewAgent(llm=InterviewLLM(recording))
            result, fell_back = await agent.next_turn(request_of(case, index))
            raw = next((call["output"] for call in reversed(recording.calls) if "output" in call), {})
            rows.append({
                "id": case["id"], "kind": case["kind"], "try": attempt,
                "action": result.next_action, "slot": result.target_star_slot,
                "question": result.question_text, "reason": getattr(result, "reason", None),
                "raw_reason": raw.get("reason"), "fell_back": fell_back, "calls": recording.calls,
            })
            print(f"{case['id']} #{attempt}: {result.next_action} {result.target_star_slot or ''}", flush=True)
    RUN_DIR.mkdir(parents=True, exist_ok=True)
    (RUN_DIR / f"{label}.json").write_text(json.dumps({"model": model, "rows": rows}, ensure_ascii=False, indent=1),
                                            encoding="utf-8")


def correct(row: dict[str, Any], case: dict[str, Any]) -> bool:
    if case["kind"] == "FULL":
        return row["action"] == "COMPLETE"
    return row["action"] == "ASK_AGAIN" and row["slot"] == case["expect_slot"]


def summary(label: str) -> dict[str, Any] | None:
    path = RUN_DIR / f"{label}.json"
    if not path.exists():
        return None
    rows = json.loads(path.read_text(encoding="utf-8"))["rows"]
    by_id = {case["id"]: case for case in cases()}
    picked = [row for row in rows if not row["fell_back"]]
    calls = [call for row in rows for call in row["calls"]]

    def rate(kind: str) -> str:
        chosen = [row for row in picked if row["kind"] == kind]
        hit = sum(correct(row, by_id[row["id"]]) for row in chosen)
        return f"{hit}/{len(chosen)}"

    return {
        "label": label,
        "SHORT 다시 물음": rate("SHORT"),
        "FULL 끝냄": rate("FULL"),
        "틀 질문으로 대신": f"{len(rows) - len(picked)}/{len(rows)}",
        "이유 있음": f"{sum(1 for row in picked if row['reason'])}/{len(picked)}",
        "수치 때문에 버린 이유": sum(1 for row in picked if row["raw_reason"] and not row["reason"]
                                and re.search(r"\d", row["raw_reason"])),
        "비용(원)": round(sum(call.get("won", 0) for call in calls), 1),
        "평균 시간(ms)": round(sum(call.get("ms", 0) for call in calls) / max(1, len(calls))),
    }


def grade_sheet() -> None:
    """이유가 있는 결과마다 사람이 고른 행동과 이유가 맞는지 O/X 를 적는 표로, 이미 있으면 적은 값을 지키려고 새로 쓰지 않는다."""
    if GRADES.exists():
        return
    path = RUN_DIR / "reason.json"
    if not path.exists():
        return
    by_id = {case["id"]: case for case in cases()}
    lines = ["| id | 시도 | 행동 | 칸 | 이유 | 기대(모자란 것) | 맞음 |", "|---|---|---|---|---|---|---|"]
    for row in json.loads(path.read_text(encoding="utf-8"))["rows"]:
        if not row["reason"]:
            continue
        expect = by_id[row["id"]].get("missing", "그만 묻기")
        lines.append(f"| {row['id']} | {row['try']} | {row['action']} | {row['slot'] or ''} | {row['reason']} | {expect} |  |")
    RUN_DIR.mkdir(parents=True, exist_ok=True)
    GRADES.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"채점표를 만들었습니다: {GRADES} ('맞음' 칸에 O/X 를 적고 report 를 다시 돌리세요)")


def graded() -> str | None:
    if not GRADES.exists():
        return None
    marks = [line.rstrip().rstrip("|").rsplit("|", 1)[-1].strip() for line in GRADES.read_text(encoding="utf-8").splitlines()[2:]]
    filled = [mark for mark in marks if mark in ("O", "X")]
    return f"{filled.count('O')}/{len(filled)} (채점 {len(filled)}/{len(marks)})" if filled else None


def report() -> None:
    labels = sorted(path.stem for path in RUN_DIR.glob("*.json")) if RUN_DIR.exists() else []
    for label in labels or ["baseline", "reason"]:
        result = summary(label)
        print(json.dumps(result, ensure_ascii=False) if result else f"{label}: 아직 없음")
    grade_sheet()
    agreed = graded()
    if agreed:
        print(f"이유가 고른 행동과 맞음: {agreed}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("step", choices=["run", "report"])
    parser.add_argument("--label", help="baseline · reason · 모델 비교는 m56 · m6 · haiku 처럼 아무 이름")
    parser.add_argument("--repeat", type=int, default=2)
    parser.add_argument("--model", default="gpt-5.6-luna")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.step == "report":
        report()
        return
    if not args.label:
        sys.exit("--label 이 필요합니다")
    if args.dry_run:
        for index, case in enumerate(cases(), start=1):
            request_of(case, index)
        print(f"[dry-run] 사례 {len(cases())}개 · 요청 검증 통과")
        return
    llm_harness.install()
    asyncio.run(run(args.label, args.repeat, args.model))


if __name__ == "__main__":
    main()

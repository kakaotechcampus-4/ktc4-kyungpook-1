"""경험 카드 되묻기 — 빈칸 틀 없이 되풀이 없는 질문 측정.

운영 코드(InterviewAgent.next_turn)를 실제 모델에 돌려 고치기 전(before)과 뒤(after)를 견준다.
before 는 고치기 전 코드에서, after 는 고친 코드에서 돌려야 하므로 코드 판을 바꿔 가며 한 번씩 돌린다.

표본: ① experience_interview/templates.json(활동 카드 10장) ② repeat.json(직접 지은 가상 카드 10장).
모델 gpt-5.6-luna, 추론 low(운영과 같음), 카드마다 1번. 호출 상한 80번(장부의 이 평가 줄 수로 센다).
결과 원본은 results/interview_asks/ 에 두고 리포에는 숫자만 올린다.

    cd ai
    python evals/interview_asks/measure.py run before --dry-run
    python evals/interview_asks/measure.py run before|after
    python evals/interview_asks/measure.py report
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
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
RUN_DIR = _AI_ROOT / "evals" / "results" / "interview_asks"
TEMPLATES = HERE.parent / "experience_interview" / "templates.json"
REPEAT = HERE / "repeat.json"
TAG_PREFIX = "interview-asks"
CALL_CAP = int(os.getenv("GITORY_EVAL_CALL_CAP", "80"))
MODEL = "gpt-5.6-luna"
HYPOTHESIS = re.compile(r"[인건]가요")


class CallCapReached(RuntimeError):
    pass


def calls_used() -> int:
    """장부에서 이 평가(태그가 interview-asks 로 시작)가 지금까지 부른 횟수."""
    if not llm_harness.LEDGER.exists():
        return 0
    used = 0
    for line in llm_harness.LEDGER.read_text(encoding="utf-8").splitlines():
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if str(row.get("tag", "")).startswith(TAG_PREFIX):
            used += 1
    return used


class Recording:
    """실제 호출기를 감싸 호출마다 토큰·지연·모델이 쓴 원래 답을 남기고, 상한을 넘기 전에 멈춘다."""

    def __init__(self, client: Any) -> None:
        self._client = client
        self.calls: list[dict[str, Any]] = []

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        if calls_used() >= CALL_CAP:
            raise CallCapReached(f"호출 상한 {CALL_CAP}번")
        started = time.perf_counter()
        try:
            result = self._client.call(system_prompt, payload, output_model, max_completion_tokens, timeout_seconds)
        except Exception as exc:
            self.calls.append({"error": type(exc).__name__, "ms": round((time.perf_counter() - started) * 1000)})
            raise
        price_in, price_out = llm_harness.price(MODEL)
        self.calls.append({
            "prompt_tokens": result.prompt_tokens, "completion_tokens": result.completion_tokens,
            "won": ((result.prompt_tokens or 0) * price_in + (result.completion_tokens or 0) * price_out) / 1_000_000,
            "ms": result.latency_ms, "output": result.output.model_dump(),
        })
        return result


class Warnings(logging.Handler):
    """운영 코드가 남기는 경고(질문 다시 묻기 등)를 카드마다 모은다. 사용자 글은 실리지 않는다."""

    def __init__(self) -> None:
        super().__init__(level=logging.WARNING)
        self.messages: list[str] = []

    def emit(self, record: logging.LogRecord) -> None:
        self.messages.append(record.getMessage())


def cards_of(path: Path) -> list[dict[str, Any]]:
    return json.loads(path.read_text(encoding="utf-8"))["cards"]


def templates_request(index: int, card: dict[str, Any]) -> dict[str, Any]:
    """10-28 활동 카드 측정과 같은 요청: 네 칸이 다 고를 수 있고, 앞 답이 있는 칸만 채워져 있다."""
    answered = {turn[0]: turn[3] for turn in card["previous"]}
    return {
        "card_id": index, "source_type": "ACTIVITY", "card_kind": "QUALITATIVE", "max_turns": 8,
        "existing_turn_count": len(card["previous"]), "activity": card["activity"],
        "missing_slots": [{"star_slot": slot, "statement_seq": 1, "body": answered.get(slot),
                           "confidence": "HIGH" if slot in answered else "LOW"} for slot in "STAR"],
        "previous_turns": [{"star_slot": s, "question_text": q, "outcome": o, "answer_text": a}
                           for s, q, o, a in card["previous"]],
    }


def repeat_request(index: int, card: dict[str, Any]) -> dict[str, Any]:
    """앞 답과 그 답을 다듬은 칸 문장이 있고, 고를 수 있는 칸은 A 나 R 하나다."""
    done = {turn[0]: turn for turn in card["previous"]}
    return {
        "card_id": 100 + index, "source_type": card["source_type"], "card_kind": "QUALITATIVE", "max_turns": 8,
        "existing_turn_count": len(card["previous"]), "activity": card["activity"],
        "missing_slots": [{"star_slot": slot, "statement_seq": 1, "body": done[slot][4] if slot in done else None,
                           "confidence": "HIGH" if slot in done else "LOW"} for slot in "STAR"],
        "allowed_slots": card["allowed"],
        "previous_turns": [{"star_slot": s, "question_text": q, "outcome": o, "answer_text": a}
                           for s, q, o, a, _ in card["previous"]],
    }


async def run(label: str) -> None:
    from schemas.interview import InterviewTurnRequest
    from services.interview_agent import InterviewAgent
    from services.interview_llm import InterviewLLM
    from services.llm_settings import EnvironmentLLMClient

    os.environ["GITORY_INTERVIEW_MODEL"] = MODEL
    warnings = Warnings()
    logging.getLogger("services.interview_llm").addHandler(warnings)
    samples = [("templates", cards_of(TEMPLATES), templates_request), ("repeat", cards_of(REPEAT), repeat_request)]
    rows: list[dict[str, Any]] = []
    try:
        for sample, cards, build in samples:
            for index, card in enumerate(cards, start=1):
                recording = Recording(EnvironmentLLMClient("INTERVIEW", default_reasoning_effort="low"))
                request = InterviewTurnRequest.model_validate(build(index, card))
                warnings.messages.clear()
                result, fell_back = await InterviewAgent(llm=InterviewLLM(recording)).next_turn(request)
                rows.append({
                    "id": card["id"], "sample": sample, "source_type": request.source_type,
                    "answers": [turn.answer_text for turn in request.previous_turns if turn.answer_text],
                    "bodies": {slot.star_slot: slot.body for slot in request.missing_slots if slot.body},
                    "allowed": request.choosable_slots(),
                    "next_action": result.next_action, "slot": result.target_star_slot,
                    "question": result.question_text, "suggestions": result.suggested_answers,
                    "reason": result.reason, "fell_back": fell_back,
                    "warnings": list(warnings.messages), "calls": recording.calls,
                })
                print(f"{card['id']}: {result.next_action} {result.target_star_slot or '-'} "
                      f"호출 {len(recording.calls)}", flush=True)
    except CallCapReached as exc:
        print(f"[중단] {exc}", flush=True)
    RUN_DIR.mkdir(parents=True, exist_ok=True)
    (RUN_DIR / f"{label}.json").write_text(json.dumps({"model": MODEL, "rows": rows}, ensure_ascii=False, indent=1),
                                           encoding="utf-8")
    print(f"저장 {len(rows)}장 · 이 평가가 지금까지 부른 횟수 {calls_used()} · 쓴 돈 {llm_harness.spent():,.1f}원")


def load(label: str) -> list[dict[str, Any]]:
    return json.loads((RUN_DIR / f"{label}.json").read_text(encoding="utf-8"))["rows"]


def sources_of(row: dict[str, Any], slot: str | None) -> list[str]:
    body = row["bodies"].get(slot) if slot else None
    return [*row["answers"], *([body] if body else [])]


def score(row: dict[str, Any], repeats_previous) -> dict[str, Any]:
    """카드 한 장에서 센 숫자. 질문은 틀 질문으로 대신하지 않은 ASK 만 센다."""
    asked = row["next_action"] == "ASK_AGAIN" and not row["fell_back"]
    outputs = [call["output"] for call in row["calls"] if "output" in call]
    first = outputs[0] if outputs else None
    first_asked = bool(first) and first["action"] == "ASK" and bool(first.get("question"))
    question = row["question"] if asked else None
    return {
        "blank_shown": sum("___" in text for text in row["suggestions"]),
        "blank_raw": sum("___" in text for text in (first or {}).get("suggested_answers", [])),
        "repeat_final": bool(question) and repeats_previous(question, sources_of(row, row["slot"])),
        "repeat_first": first_asked and repeats_previous(first["question"], sources_of(row, first["star_slot"])),
        "hypothesis": bool(question) and bool(HYPOTHESIS.search(question)),
        "ended": row["next_action"] == "COMPLETE",
        "fell_back": row["fell_back"],
        "asked_again": len(outputs) >= 2,
        "gave_up": any("그대로 쓴다" in message for message in row["warnings"]),
        "won": sum(call.get("won", 0) for call in row["calls"]),
        "calls": len(row["calls"]),
    }


def report() -> None:
    from services.interview_llm import repeats_previous

    # 고치기 전 코드로 돌린 결과가 없어도 있는 쪽만으로 보고가 나오게 한다
    labels = [label for label in ("before", "after") if (RUN_DIR / f"{label}.json").exists()]
    if not labels:
        raise SystemExit(f"{RUN_DIR} 에 결과가 없습니다. 먼저 `run before` 나 `run after` 를 돌리세요.")
    scored = {label: [(row, score(row, repeats_previous)) for row in load(label)] for label in labels}
    columns = [("빈칸 후보(나감)", "blank_shown"), ("빈칸 후보(모델 첫 답)", "blank_raw"),
               ("되풀이(나간 질문)", "repeat_final"), ("되풀이(모델 첫 답)", "repeat_first"),
               ("가설형 질문", "hypothesis"), ("END", "ended"), ("틀 질문으로 대신", "fell_back"),
               ("다시 물음", "asked_again"), ("되풀이인 채 그대로 씀", "gave_up")]
    for sample in (None, "templates", "repeat"):
        title = {None: "전체", "templates": "① 활동 카드 10장", "repeat": "② 가상 카드 10장"}[sample]
        print(f"\n[{title}]")
        for name, key in columns:
            cells = []
            for label in labels:
                rows = [s for r, s in scored[label] if sample in (None, r["sample"])]
                cells.append(f"{sum(int(s[key]) for s in rows)}/{len(rows)}")
            print(f"  {name:<22} " + "  ".join(f"{label} {cell:>6}" for label, cell in zip(labels, cells)))
    print()
    for label in labels:
        rows = [s for _, s in scored[label]]
        print(f"{label}: 호출 {sum(s['calls'] for s in rows)}번 · {sum(s['won'] for s in rows):.1f}원")
    print("\n[카드별]")
    for label in labels:
        print(f"== {label}")
        for row, s in scored[label]:
            flags = [name for name, key in columns if s[key] and key not in ("blank_raw",)]
            print(f"{row['id']} [{row['source_type']}] 다음 칸 {row['slot'] or '-'} · {row['next_action']}"
                  f"{' · 틀 질문' if row['fell_back'] else ''} · 표시 {','.join(flags) or '-'}")
            print(f"   앞 답: {' / '.join(row['answers']) or '(없음)'}")
            if row["bodies"].get(row["slot"] or ""):
                print(f"   지금 문장({row['slot']}): {row['bodies'][row['slot']]}")
            print(f"   질문: {row['question']}")
            for text in row["suggestions"]:
                print(f"   후보: {text}")
            raw = [t for c in row["calls"] if "output" in c for t in c["output"].get("suggested_answers", [])]
            dropped = [t for t in raw if t not in row["suggestions"]]
            for text in dropped:
                print(f"   (코드가 버린 후보: {text})")
            if row["reason"]:
                print(f"   이유: {row['reason']}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("step", choices=["run", "report"])
    parser.add_argument("label", nargs="?", choices=["before", "after"])
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.step == "report":
        report()
        return
    if not args.label:
        sys.exit("run 뒤에 before 나 after 가 필요합니다")
    if args.dry_run:
        from schemas.interview import InterviewTurnRequest
        total = 0
        for cards, build in ((cards_of(TEMPLATES), templates_request), (cards_of(REPEAT), repeat_request)):
            for index, card in enumerate(cards, start=1):
                InterviewTurnRequest.model_validate(build(index, card))
                total += 1
        print(f"[dry-run] 사례 {total}개 · 요청 검증 통과")
        return
    os.environ["GITORY_EVAL_TAG"] = f"{TAG_PREFIX}-{args.label}"
    llm_harness.install()
    asyncio.run(run(args.label))


if __name__ == "__main__":
    main()

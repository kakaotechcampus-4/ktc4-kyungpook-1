"""경험 카드 되묻기 측정 (#482). 세 가지를 운영과 같은 코드로 실제 모델에 돌린다.

  run polish    : 답 20개를 다듬는다(InterviewLLM.polish, 답에 없는 숫자가 생기면 한 번 고침)
  run themes    : 카드 20장의 문항 유형을 고른다(InterviewLLM.card_themes)
  run templates : 활동 카드 10장의 다음 질문과 예상 답변을 만든다(InterviewAgent.next_turn)
  report        : 자동으로 셀 수 있는 숫자를 찍고, 사람이 O/X 를 적을 채점표를 만든다(이미 있으면 적은 값을 읽는다)

표본은 모두 직접 쓴 가상의 경험이다(polish.json · themes.json · templates.json). 결과 원본과 채점표는
ai/evals/results/experience_interview/ 에 쓰고 커밋하지 않는다.

    cd ai
    python evals/experience_interview/measure.py run polish --dry-run   # LLM 없이 요청 검증만
    python evals/experience_interview/measure.py run polish|themes|templates [--model gpt-5.6-luna] [--label 이름]
    python evals/experience_interview/measure.py report
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
RUN_DIR = _AI_ROOT / "evals" / "results" / "experience_interview"
POLISH_GRADES = RUN_DIR / "polish_grades.md"
TEMPLATE_GRADES = RUN_DIR / "template_grades.md"

#: 원/100만 토큰 (입력, 출력). Elice 모델 라이브러리 2026-10-28 기준.
PRICES = {"gpt-5.6-luna": (304, 1_827), "openai/gpt-6-luna": (141, 708), "anthropic/claude-haiku-5-5": (141, 708)}


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


def data(name: str) -> dict[str, Any]:
    return json.loads((HERE / f"{name}.json").read_text(encoding="utf-8"))


def llm(model: str):
    from services.interview_llm import InterviewLLM
    from services.llm_settings import EnvironmentLLMClient
    os.environ["GITORY_INTERVIEW_MODEL"] = model
    recording = Recording(EnvironmentLLMClient("INTERVIEW", default_reasoning_effort="low"), model)
    return InterviewLLM(recording), recording


async def run_polish(model: str) -> list[dict[str, Any]]:
    from schemas.interview import PolishedSentenceRequest
    from services.interview_llm import InterviewLLMUnavailable
    rows = []
    for index, item in enumerate(data("polish")["answers"], start=1):
        client, recording = llm(model)
        request = PolishedSentenceRequest(card_id=index, star_slot=item["slot"], question_text=item["question"],
                                          answer_text=item["answer"])
        try:
            result = await client.polish(request)
            sentence, unsourced = result.sentence, result.unsourced_numbers
        except InterviewLLMUnavailable:
            sentence, unsourced = None, []
        first = next((call["output"]["sentence"] for call in recording.calls if "output" in call), None)
        rows.append({"id": item["id"], "answer": item["answer"], "watch": item["watch"], "sentence": sentence,
                     "first_sentence": first, "unsourced": unsourced, "calls": recording.calls})
        print(f"{item['id']}: {'다듬음' if sentence else '실패'} (호출 {len(recording.calls)})", flush=True)
    return rows


async def run_themes(model: str) -> list[dict[str, Any]]:
    from schemas.interview import CardThemesRequest
    from services.interview_llm import InterviewLLMUnavailable
    rows = []
    for index, card in enumerate(data("themes")["cards"], start=1):
        client, recording = llm(model)
        request = CardThemesRequest(card_id=index, title=card["title"], slots=[
            {"star_slot": slot, "body": body} for slot, body in card["slots"].items()
        ])
        try:
            themes = await client.card_themes(request)
        except InterviewLLMUnavailable:
            themes = None
        rows.append({"id": card["id"], "expect": card["expect"], "themes": themes, "calls": recording.calls})
        print(f"{card['id']}: {themes}", flush=True)
    return rows


async def run_templates(model: str) -> list[dict[str, Any]]:
    from schemas.interview import InterviewTurnRequest
    from services.interview_agent import InterviewAgent
    rows = []
    for index, card in enumerate(data("templates")["cards"], start=1):
        client, recording = llm(model)
        answered = {turn[0]: turn[3] for turn in card["previous"]}
        request = InterviewTurnRequest.model_validate({
            "card_id": index, "source_type": "ACTIVITY", "card_kind": "QUALITATIVE", "max_turns": 8,
            "existing_turn_count": len(card["previous"]), "activity": card["activity"],
            "missing_slots": [{"star_slot": slot, "statement_seq": 1, "body": answered.get(slot),
                               "confidence": "HIGH" if slot in answered else "LOW"} for slot in "STAR"],
            "previous_turns": [{"star_slot": s, "question_text": q, "outcome": o, "answer_text": a}
                               for s, q, o, a in card["previous"]],
        })
        result, fell_back = await InterviewAgent(llm=client).next_turn(request)
        raw = next((call["output"].get("suggested_answers", []) for call in reversed(recording.calls)
                    if "output" in call), [])
        rows.append({"id": card["id"], "slot": result.target_star_slot, "question": result.question_text,
                     "suggestions": result.suggested_answers, "raw_suggestions": raw, "fell_back": fell_back,
                     "calls": recording.calls})
        print(f"{card['id']}: {result.target_star_slot} 틀 {len(result.suggested_answers)}/{len(raw)}", flush=True)
    return rows


def load(step: str) -> list[dict[str, Any]] | None:
    path = RUN_DIR / f"{step}.json"
    return json.loads(path.read_text(encoding="utf-8"))["rows"] if path.exists() else None


def cost(rows: list[dict[str, Any]]) -> str:
    calls = [call for row in rows for call in row["calls"]]
    return f"{round(sum(c.get('won', 0) for c in calls), 1)}원 · 호출 {len(calls)} · 평균 {round(sum(c.get('ms', 0) for c in calls) / max(1, len(calls)))}ms"


def marks(path: Path, column: int) -> list[str]:
    """채점표의 뒤에서 column 번째 칸에 적은 O/X 를 읽는다."""
    if not path.exists():
        return []
    found = []
    for line in path.read_text(encoding="utf-8").splitlines()[2:]:
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) >= column and cells[-column] in ("O", "X"):
            found.append(cells[-column])
    return found


def cell(text: Any) -> str:
    return str(text or "").replace("|", "/").replace("\n", " ")


def report() -> None:
    polish = load("polish")
    if polish:
        done = [row for row in polish if row["sentence"]]
        retried = sum(1 for row in done if len(row["calls"]) > 1)
        print(f"[다듬기] 다듬음 {len(done)}/{len(polish)} · 지어낸 숫자 남음 {sum(1 for r in done if r['unsourced'])} · "
              f"한 번 고침 {retried} · '~다' 로 끝남 {sum(1 for r in done if re.search(r'다[.!]?$', r['sentence'].strip()))}/{len(done)} · {cost(polish)}")
        if not POLISH_GRADES.exists():
            RUN_DIR.mkdir(parents=True, exist_ok=True)
            lines = ["| id | 답 | 다듬은 문장 | 볼 것 | 주어·정도 그대로 | 사실 더함·뺌 없음 |", "|---|---|---|---|---|---|"]
            lines += [f"| {r['id']} | {cell(r['answer'])} | {cell(r['sentence'])} | {cell(r['watch'])} |  |  |" for r in done]
            POLISH_GRADES.write_text("\n".join(lines) + "\n", encoding="utf-8")
            print(f"채점표: {POLISH_GRADES}")
        for label, column in (("주어·정도 그대로", 2), ("사실 더함·뺌 없음", 1)):
            got = marks(POLISH_GRADES, column)
            if got:
                print(f"  {label}: {got.count('O')}/{len(got)}")
    themes = load("themes")
    if themes:
        picked = [row for row in themes if row["themes"] is not None]
        hit = sum(1 for row in picked if row["themes"] and row["themes"][0] in row["expect"])
        print(f"[문항 유형] 첫 유형 맞음 {hit}/{len(picked)} · 빈 유형 {sum(1 for r in picked if not r['themes'])} · {cost(themes)}")
    templates = load("templates")
    if templates:
        shown = [s for row in templates for s in row["suggestions"]]
        dropped = sum(len(row["raw_suggestions"]) - len(row["suggestions"]) for row in templates)
        print(f"[빈칸 틀] 나간 틀 {len(shown)} · 빈칸 없는 틀 {sum(1 for s in shown if '___' not in s)} · 코드가 버린 후보 {dropped} · "
              f"틀 질문으로 대신 {sum(1 for r in templates if r['fell_back'])} · {cost(templates)}")
        if not TEMPLATE_GRADES.exists():
            RUN_DIR.mkdir(parents=True, exist_ok=True)
            lines = ["| id | 칸 | 질문 | 틀 | 지어낸 내용 없음 |", "|---|---|---|---|---|"]
            lines += [f"| {r['id']} | {r['slot'] or ''} | {cell(r['question'])} | {cell(s)} |  |"
                      for r in templates for s in r["suggestions"]]
            TEMPLATE_GRADES.write_text("\n".join(lines) + "\n", encoding="utf-8")
            print(f"채점표: {TEMPLATE_GRADES}")
        got = marks(TEMPLATE_GRADES, 1)
        if got:
            print(f"  지어낸 내용 없음: {got.count('O')}/{len(got)}")


def dry_run(what: str) -> None:
    """LLM 을 부르지 않고 표본을 읽어 요청 모양만 검증한다."""
    from schemas.interview import CardThemesRequest, InterviewTurnRequest, PolishedSentenceRequest
    if what == "polish":
        items = data("polish")["answers"]
        for index, item in enumerate(items, start=1):
            PolishedSentenceRequest(card_id=index, star_slot=item["slot"], question_text=item["question"],
                                    answer_text=item["answer"])
    elif what == "themes":
        items = data("themes")["cards"]
        for index, card in enumerate(items, start=1):
            CardThemesRequest(card_id=index, title=card["title"], slots=[
                {"star_slot": slot, "body": body} for slot, body in card["slots"].items()])
    else:
        items = data("templates")["cards"]
        for index, card in enumerate(items, start=1):
            answered = {turn[0]: turn[3] for turn in card["previous"]}
            InterviewTurnRequest.model_validate({
                "card_id": index, "source_type": "ACTIVITY", "card_kind": "QUALITATIVE", "max_turns": 8,
                "existing_turn_count": len(card["previous"]), "activity": card["activity"],
                "missing_slots": [{"star_slot": slot, "statement_seq": 1, "body": answered.get(slot),
                                   "confidence": "HIGH" if slot in answered else "LOW"} for slot in "STAR"],
                "previous_turns": [{"star_slot": s, "question_text": q, "outcome": o, "answer_text": a}
                                   for s, q, o, a in card["previous"]],
            })
    print(f"[dry-run] {what} 사례 {len(items)}개 · 요청 검증 통과")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("step", choices=["run", "report"])
    parser.add_argument("what", nargs="?", choices=["polish", "themes", "templates"])
    parser.add_argument("--model", default="gpt-5.6-luna")
    parser.add_argument("--label", help="결과 파일 이름 뒤에 붙일 이름(모델 비교 등). 없으면 덮어쓴다")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.step == "report":
        report()
        return
    if not args.what:
        sys.exit("run 뒤에 polish·themes·templates 중 하나가 필요합니다")
    if args.dry_run:
        dry_run(args.what)
        return
    llm_harness.install()
    runner = {"polish": run_polish, "themes": run_themes, "templates": run_templates}[args.what]
    rows = asyncio.run(runner(args.model))
    RUN_DIR.mkdir(parents=True, exist_ok=True)
    name = f"{args.what}-{args.label}" if args.label else args.what
    (RUN_DIR / f"{name}.json").write_text(json.dumps({"model": args.model, "rows": rows}, ensure_ascii=False, indent=1),
                                               encoding="utf-8")


if __name__ == "__main__":
    main()

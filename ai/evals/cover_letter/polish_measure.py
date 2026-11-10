"""자소서 첨삭(#725) 측정: 실제 카드 실행(rn8-m-1)의 초안 4개를 문항마다 첨삭 서비스에 그대로 넣고 앞뒤를 견준다.

문항마다 포인트 수(WEAK·GOOD), 검사기(#723 check_draft)로 센 문제(근거 없는 값·기술 이름·코드 이름·저장소 말)와
다른 문항과 같은 15자 조각 수(겹침), 글자 수, 걸린 시간, 비용을 앞(지금 글)·뒤(고친 글)로 찍는다.

초안은 measure.py 가 남긴 실행(--run, 기본 new)의 첫 사용자 것이다. 11-03 측정은 실제 카드 세트(저장소에 없음)로 돌렸으므로,
같은 숫자를 다시 내려면 그 세트의 사용자 파일을 --users 로 주고 그 세트로 만든 실행을 --run 으로 준다.
결과 원본은 ai/evals/results/cover_letter/runs/ 에만 남는다(측정 11-03, #725). 상한은 장부 합계 + 150원.

    cd ai
    python evals/cover_letter/measure.py run new
    python evals/cover_letter/polish_measure.py [--run new] [--users evals/cover_letter/users.json] [--model gpt-5.6-luna]
    python evals/cover_letter/polish_measure.py --dry-run    # LLM 을 부르지 않고 첨삭 요청 검증만 본다
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import time
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
_AI_ROOT = HERE.parents[1]
# `python ai/evals/cover_letter/<스크립트>.py` 로 돌려도 ai/ 패키지를 import 할 수 있게 한다.
sys.path.insert(0, str(_AI_ROOT))
sys.path.insert(0, str(HERE))

import measure as M  # noqa: E402
from evals.common import llm_harness  # noqa: E402

RUNS = _AI_ROOT / "evals" / "results" / "cover_letter" / "runs"
TAG = "cl-polish-725"


class Recording:
    """호출마다 출력 모델 이름·토큰·비용·지연을 남긴다."""

    def __init__(self, client: Any, model: str, sink: list[dict[str, Any]], price) -> None:
        self._client, self._model, self._sink, self._price = client, model, sink, price

    def call(self, system_prompt, payload, output_model, max_completion_tokens, timeout_seconds=None):
        started = time.perf_counter()
        try:
            result = self._client.call(system_prompt, payload, output_model, max_completion_tokens, timeout_seconds)
        except Exception as exc:
            self._sink.append({"step": output_model.__name__, "error": type(exc).__name__,
                               "ms": round((time.perf_counter() - started) * 1000)})
            raise
        price_in, price_out = self._price(self._model)
        self._sink.append({"step": output_model.__name__, "in": result.prompt_tokens, "out": result.completion_tokens,
                           "won": ((result.prompt_tokens or 0) * price_in + (result.completion_tokens or 0) * price_out) / 1e6,
                           "ms": result.latency_ms})
        return result


def shared_15(text: str, others: list[str]) -> int:
    def grams(t: str) -> set[str]:
        t = "".join(t.split())
        return {t[i:i + 15] for i in range(len(t) - 14)}
    mine = grams(text)
    return len(mine & set().union(*(grams(o) for o in others))) if others else 0


def problems(text: str, draft_request, question) -> dict[str, int]:
    from services.cover_letter_check import ITEM_LABELS, blank, check_draft, code_names, repo_words, tech_names
    from services.cover_letter_writer import check_options, known_names, source_text
    blanks = [item for item in ITEM_LABELS if blank(item) in text]
    issues = check_draft(text, source=source_text(draft_request, question), char_limit=question.char_limit,
                         missing_items=blanks, **check_options(draft_request, question))
    known = known_names(draft_request, question)
    return {
        "unsourced": sum(1 for i in issues if i.code in ("UNSOURCED_NUMBER", "UNSOURCED_TERM")),
        "tech_names": len(tech_names(text, known)),
        "tech_over": sum(1 for i in issues if i.rule == "TECH_NAMES"),
        "code_names": len(code_names(text, known)),
        "repo_words": len(repo_words(text)),
        "repo_over": sum(1 for i in issues if i.rule == "REPO_WORDS"),
        "no_ambition": sum(1 for i in issues if i.rule == "NO_AMBITION"),
    }


def detail(card: dict[str, Any] | None) -> dict[str, Any] | None:
    if card is None:
        return None
    return {"card_id": card["card_id"], "title": card["title"], "card_kind": card["card_kind"],
            "source_name": card["source_name"],
            "statements": [{"star_slot": slot, "text": text} for slot, text in card["statements"].items()]}


def polish_payload(user: dict[str, Any], stored: list[dict[str, Any]], q: dict[str, Any]) -> dict[str, Any]:
    cards = {card["card_id"]: card for card in user["cards"]}
    asked = {item["question_id"]: item for item in user["questions"]}
    qid = q["question_id"]
    others = [{"question_id": o["question_id"], "text": asked[o["question_id"]]["text"], "current_text": o["text"]}
              for o in stored if o["question_id"] != qid]
    return {
        "cover_letter_id": 1, "job_role": user["job_role"], "memo": user["memo"], "others": others,
        "target": {"question_id": qid, "text": asked[qid]["text"], "char_limit": asked[qid]["char_limit"],
                   "question_theme": q["theme"], "primary_card": detail(cards.get(q["primary"])),
                   "supporting_card": detail(cards.get(q["supporting"])), "answers": q["answers"],
                   "current_text": q["text"],
                   "inferred_seqs": [m["sentence_seq"] for m in q["marks"] if m["code"] == "INFERRED"]},
    }


def load_inputs(args: argparse.Namespace) -> tuple[dict[str, Any], dict[str, Any]]:
    run_path = RUNS / f"{args.run}.json"
    if not run_path.exists():
        sys.exit(f"{run_path.name} 이 먼저 있어야 합니다(measure.py run new --tag {args.run})")
    run = json.loads(run_path.read_text(encoding="utf-8"))["users"][0]
    user = json.loads(Path(args.users).read_text(encoding="utf-8"))["users"][0]
    return run, user


def dry_run(args: argparse.Namespace) -> None:
    from schemas.cover_letter import PolishRequest
    run, user = load_inputs(args)
    for q in run["questions"]:
        PolishRequest.model_validate(polish_payload(user, run["questions"], q))
    print(f"실행 {args.run} · 사용자 {user['id']} · 첨삭 요청 {len(run['questions'])}개 검증 끝")


async def polish_one(polisher, payload: dict[str, Any]):
    from schemas.cover_letter import PolishRequest
    return await polisher.polish(PolishRequest.model_validate(payload))


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="자소서 첨삭 측정. 쓰는 법은 파일 맨 위 설명에 있다.")
    parser.add_argument("--run", default="new")
    parser.add_argument("--users", default=str(HERE / "users.json"))
    parser.add_argument("--model", default="gpt-5.6-luna")
    parser.add_argument("--dry-run", action="store_true", help="LLM 을 부르지 않고 첨삭 요청 검증만 본다")
    args = parser.parse_args()
    if args.dry_run:
        dry_run(args)
        return

    os.environ["GITORY_EVAL_TAG"] = TAG
    M.start_llm()
    # 이 측정이 쓰는 돈은 장부의 지금 합계에 150원을 더한 선에서 멈춘다
    llm_harness.CAP_WON = round(llm_harness.spent() + 150, 1)
    from services.cover_letter_polish import CoverLetterPolisher, as_draft_request
    from services.cover_letter_llm import CoverLetterLLMUnavailable
    from services.llm_settings import EnvironmentLLMClient
    from schemas.cover_letter import PolishRequest

    run, user = load_inputs(args)
    stored = run["questions"]
    os.environ["GITORY_COVER_LETTER_POLISH_MODEL"] = args.model
    os.environ["GITORY_COVER_LETTER_REVIEW_MODEL"] = args.model

    rows = []
    for q in stored:
        qid = q["question_id"]
        calls: list[dict[str, Any]] = []
        polish_client = Recording(EnvironmentLLMClient("COVER_LETTER_POLISH"), args.model, calls, llm_harness.price)
        judge_client = Recording(EnvironmentLLMClient("COVER_LETTER_REVIEW"), args.model, calls, llm_harness.price)
        polisher = CoverLetterPolisher(polish_client, judge_client)
        payload = polish_payload(user, stored, q)
        draft_request, question = as_draft_request(PolishRequest.model_validate(payload))
        other_texts = [o["current_text"] for o in payload["others"]]
        started = time.perf_counter()
        try:
            result = asyncio.run(polish_one(polisher, payload))
        except CoverLetterLLMUnavailable:
            rows.append({"qid": qid, "theme": q["theme"], "failed": True, "calls": calls,
                         "ms": round((time.perf_counter() - started) * 1000)})
            print(f"Q{qid} 실패(503)", flush=True)
            continue
        ms = round((time.perf_counter() - started) * 1000)
        after = result.draft.text
        row = {
            "qid": qid, "theme": q["theme"], "ms": ms, "won": round(sum(c.get("won", 0) for c in calls), 2),
            "calls": calls, "summary": result.summary,
            "points": [p.model_dump() for p in result.points],
            "weak": sum(1 for p in result.points if p.kind == "WEAK"),
            "good": sum(1 for p in result.points if p.kind == "GOOD"),
            "categories": [f"{p.kind}:{p.category}" for p in result.points],
            "chars": [q["char_count"], result.draft.char_count],
            "before": problems(q["text"], draft_request, question) | {"overlap15": shared_15(q["text"], other_texts)},
            "after": problems(after, draft_request, question) | {"overlap15": shared_15(after, other_texts)},
            "marks_before": [m["code"] for m in q["marks"]], "marks_after": [m.code for m in result.draft.marks],
            "marks_after_seq": [[m.code, m.sentence_seq] for m in result.draft.marks],
            "rewrites": result.draft.rewrites, "steps": [s.kind for s in result.steps],
            "text_before": q["text"], "text_after": after,
        }
        rows.append(row)
        print(f"Q{qid} {q['theme']}: WEAK {row['weak']} GOOD {row['good']} {row['categories']} | 글자 {row['chars']} | "
              f"앞 {row['before']} | 뒤 {row['after']} | 표시 {row['marks_before']}→{row['marks_after']} | "
              f"{ms/1000:.1f}초 {row['won']}원 호출 {len(calls)}", flush=True)

    RUNS.mkdir(parents=True, exist_ok=True)
    out = RUNS / f"{TAG}.json"
    out.write_text(json.dumps({"run": args.run, "model": args.model, "rows": rows}, ensure_ascii=False, indent=1),
                   encoding="utf-8")
    print(f"합계 {sum(r.get('won', 0) for r in rows):.1f}원 · 결과 {out.name}", flush=True)


if __name__ == "__main__":
    main()

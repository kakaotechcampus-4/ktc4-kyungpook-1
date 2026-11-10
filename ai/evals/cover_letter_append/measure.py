"""자소서 새 카드 덧붙이기(#1002) 측정: 가상 사용자 문항 5개의 글에 두 번째 카드 내용을 덧붙이고 원래 문장이 남는지 본다.

문항마다 중심 카드 하나로 지금 글을 쓰고(초안 쓰기, 운영과 같은 코드) 그 글에 두 번째 카드를 보조로 덧붙인다(덧붙이기, 운영과 같은 코드).
숫자는 kept_ratio(원래 문장이 그대로 남은 비율) · added_sentences(더한 문장 수) · over_limit(글자 수 상한을 넘은 문항 수) · reverted(앞 글로 되돌린 문항 수)이다.
결과 원본(모델이 쓴 글)은 ai/evals/results/cover_letter_append/ 에만 남고 git 이 무시한다. 숫자는 evals/results/history/ 요약과 RESULTS.md 에 둔다.

    cd ai
    python evals/cover_letter_append/measure.py [--model gpt-5.6-luna] [--cap-won 100] [--label first]
    python evals/cover_letter_append/measure.py --dry-run    # LLM 을 부르지 않고 요청 검증만 본다
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
# `python ai/evals/cover_letter_append/measure.py` 로 돌려도 ai/ 패키지와 같은 폴더 자소서 측정 모듈을 import 할 수 있게 한다.
sys.path.insert(0, str(_AI_ROOT))
sys.path.insert(0, str(HERE.parent / "cover_letter"))

import measure as M  # noqa: E402
from evals.common import llm_harness  # noqa: E402

RESULTS = _AI_ROOT / "evals" / "results" / "cover_letter_append"
TAG = "cl-append-1002"

#: (사용자, 문항 번호, 지금 글을 쓸 중심 카드, 덧붙일 카드) — users.json 의 정답 카드가 둘인 문항
CASES = [("u1", 3, 1, 2), ("u3", 3, 1, 2), ("u4", 2, 1, 3), ("u5", 3, 1, 3), ("u4", 1, 1, 2)]


def draft_request(user: dict[str, Any], question: dict[str, Any], primary: dict[str, Any]) -> dict[str, Any]:
    answers = []
    for item in (*M.CARD_ITEMS, "JOB_LINK", "REASON"):
        text = M.answer_for(user, question, primary, item)
        if text:
            answers.append({"item": item, "text": text, "card_id": primary["card_id"] if item in M.CARD_ITEMS else None})
    return {
        "cover_letter_id": 1, "job_role": user["job_role"], "memo": user["memo"],
        "questions": [{"question_id": question["question_id"], "text": question["text"], "char_limit": question["char_limit"],
                       "primary_card": M.detail(primary), "answers": answers, "missing_items": []}],
    }


def append_request(user: dict[str, Any], question: dict[str, Any], primary: dict[str, Any], added: dict[str, Any],
                   current: str) -> dict[str, Any]:
    others = [{"question_id": o["question_id"], "text": o["text"], "current_text": "다른 문항의 글입니다."}
              for o in user["questions"] if o["question_id"] != question["question_id"]][:2]
    return {
        "cover_letter_id": 1, "job_role": user["job_role"], "memo": user["memo"], "others": others,
        "target": {"question_id": question["question_id"], "text": question["text"], "char_limit": question["char_limit"],
                   "primary_card": M.detail(primary), "supporting_card": M.detail(added), "answers": [],
                   "current_text": current, "inferred_seqs": []},
        "added_card_id": added["card_id"],
    }


def case_parts(user_id: str, qid: int, primary_id: int, added_id: int) -> tuple[dict, dict, dict, dict]:
    user = next(u for u in M.users() if u["id"] == user_id)
    cards = {c["card_id"]: c for c in user["cards"]}
    question = next(q for q in user["questions"] if q["question_id"] == qid)
    return user, question, cards[primary_id], cards[added_id]


def dry_run() -> None:
    from schemas.cover_letter import AppendCardRequest, DraftsRequest
    for user_id, qid, primary_id, added_id in CASES:
        user, question, primary, added = case_parts(user_id, qid, primary_id, added_id)
        DraftsRequest.model_validate(draft_request(user, question, primary))
        AppendCardRequest.model_validate(append_request(user, question, primary, added, "지금 글입니다."))
    print(f"문항 {len(CASES)}개 · 쓰기 요청과 덧붙이기 요청 검증 끝")


async def run_case(user_id: str, qid: int, primary_id: int, added_id: int, model: str) -> dict[str, Any]:
    from schemas.cover_letter import AppendCardRequest, DraftsRequest
    from services.cover_letter_append import CoverLetterAppender
    from services.cover_letter_check import split_sentences
    from services.cover_letter_drafter import CoverLetterDrafter
    from services.cover_letter_fill import squash

    user, question, primary, added = case_parts(user_id, qid, primary_id, added_id)
    calls: list[dict[str, Any]] = []
    write = M.client("COVER_LETTER_WRITE", model, None, "쓰기", calls, False)
    judge = M.client("COVER_LETTER_REVIEW", model, None, "판단", calls, False)
    drafted, _ = await CoverLetterDrafter(write, judge).draft(DraftsRequest.model_validate(draft_request(user, question, primary)))
    current = drafted.drafts[0].text
    old = split_sentences(current)

    started = time.perf_counter()
    result = await CoverLetterAppender(write, judge).append(
        AppendCardRequest.model_validate(append_request(user, question, primary, added, current)))
    after = result.draft.text
    left = {squash(s) for s in split_sentences(after)}
    kept = [s for s in old if squash(s) in left]
    added_count = len(left - {squash(s) for s in old})
    return {
        "user": user_id, "question_id": qid, "primary": primary_id, "added": added_id,
        "kept_ratio": len(kept) / len(old) if old else 1.0, "added_sentences": added_count,
        "over_limit": result.draft.over_by > 0, "reverted": any(s.kind == "REVERTED" for s in result.steps),
        "reasons": [s.reason for s in result.steps if s.kind == "REVERTED"],
        "ms": round((time.perf_counter() - started) * 1000), "won": round(sum(c.get("won", 0) for c in calls), 2),
        "calls": len(calls), "text_before": current, "text_after": after,
    }


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="자소서 새 카드 덧붙이기 측정. 쓰는 법은 파일 맨 위 설명에 있다.")
    parser.add_argument("--model", default="gpt-5.6-luna")
    parser.add_argument("--label", default="first", help="결과 파일과 요약 이름")
    parser.add_argument("--cap-won", type=float, default=100.0, help="이 측정이 더 쓸 수 있는 돈 상한")
    parser.add_argument("--dry-run", action="store_true", help="LLM 을 부르지 않고 요청 검증만 본다")
    args = parser.parse_args()
    if args.dry_run:
        dry_run()
        return

    os.environ["GITORY_EVAL_TAG"] = TAG
    M.start_llm()
    llm_harness.CAP_WON = round(llm_harness.spent() + args.cap_won, 1)

    rows = []
    for case in CASES:
        row = asyncio.run(run_case(*case, args.model))
        rows.append(row)
        print(f"{row['user']} Q{row['question_id']}: 남은 문장 {row['kept_ratio']:.0%} · 더한 문장 {row['added_sentences']} · "
              f"상한 초과 {row['over_limit']} · 되돌림 {row['reverted']} {row['reasons']} · {row['ms'] / 1000:.1f}초 {row['won']}원", flush=True)

    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{args.label}.json").write_text(json.dumps({"model": args.model, "rows": rows}, ensure_ascii=False, indent=1),
                                        encoding="utf-8")
    total = sum(r["won"] for r in rows)
    metrics = {
        "kept_ratio": round(sum(r["kept_ratio"] for r in rows) / len(rows), 3),
        "added_sentences": round(sum(r["added_sentences"] for r in rows) / len(rows), 2),
        "over_limit": sum(1 for r in rows if r["over_limit"]),
        "reverted": sum(1 for r in rows if r["reverted"]),
    }
    path = llm_harness.record_summary("cover_letter_append", args.label, metrics, sample="가상 사용자 5문항", model=args.model,
                                      calls=sum(r["calls"] for r in rows), spend_won=total)
    print(f"요약 {metrics} · 쓴 돈 {total:.1f}원 · {path.name}")


if __name__ == "__main__":
    main()

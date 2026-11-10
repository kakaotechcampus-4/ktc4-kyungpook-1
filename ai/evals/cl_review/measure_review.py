"""자소서 전체 검토(감독)를 모델마다 견준다 — 심은 문제를 몇 개 찾는지, 원래 초안에서 몇 문장을 거는지.

초안은 10-29 측정의 w-luna 실행(Luna 가 쓰고 Luna 감독이 한 번 본 최종본)이고, 감독 입력은 운영 코드
(cover_letter_drafter._review)와 같은 모양으로 만든다. 프롬프트·출력 검사·토큰 상한도 운영 코드 것을 그대로 쓴다.
planted.json 의 anchor·find 는 그때 쓰인 w-luna 초안의 문장이라, 새로 쓴 초안에서는 맞지 않을 수 있다(--dry-run 이 확인한다).
결과 원본은 ai/evals/results/cl_review/ 에만 남는다. 심은 문장의 정답은 planted.json, 원래 초안의 진짜 문제 판정은 adjudication.json 이다.

    cd ai
    python evals/cover_letter/measure.py run new --tag w-luna
    python evals/cl_review/measure_review.py run gpt-5.6-luna [--reps 2]
    python evals/cl_review/measure_review.py run openai/gpt-6.1-sol --reps 2     # 모델마다 한 번씩, 모델별 주소는 GITORY_EVAL_ENDPOINTS
    python evals/cl_review/measure_review.py report
    python evals/cl_review/measure_review.py --dry-run     # LLM 을 부르지 않고 심은 문장이 초안에 있는지 본다
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import statistics
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
_AI_ROOT = HERE.parents[1]
# `python ai/evals/cl_review/measure_review.py` 로 돌려도 ai/ 패키지를 import 할 수 있게 한다.
sys.path.insert(0, str(_AI_ROOT))

CL = HERE.parent / "cover_letter"
DRAFTS = HERE.parent / "results" / "cover_letter" / "runs" / "w-luna.json"
OUT = HERE.parent / "results" / "cl_review" / "runs"
VARIANTS = ("original", "planted", "subtle")


def load_inputs():
    users = {u["id"]: u for u in json.load(open(CL / "users.json", encoding="utf-8"))["users"]}
    data = json.load(open(HERE / "planted.json", encoding="utf-8"))
    return users, None, {"planted": data["plants"], "subtle": data["subtle"]}


def load():
    users = {u["id"]: u for u in json.load(open(CL / "users.json", encoding="utf-8"))["users"]}
    if not DRAFTS.exists():
        sys.exit(f"{DRAFTS.name} 이 먼저 있어야 합니다(measure.py run new --tag w-luna)")
    run = json.load(open(DRAFTS, encoding="utf-8"))
    data = json.load(open(HERE / "planted.json", encoding="utf-8"))
    return users, run, {"planted": data["plants"], "subtle": data["subtle"]}


def sources_of(user: dict, q: dict) -> list[str]:
    """cover_letter_drafter._question_sources 와 같은 순서: 중심·보조 카드의 제목·출처·문장, 그다음 답."""
    cards = {c["card_id"]: c for c in user["cards"]}
    texts: list[str] = []
    for card_id in (q["primary"], q["supporting"]):
        if card_id:
            card = cards[card_id]
            texts += [card["title"], *([card["source_name"]] if card.get("source_name") else [])]
            texts += list(card["statements"].values())
    texts += [a["text"] for a in q["answers"]]
    return texts


def with_plant(text: str, plant: dict) -> str:
    index = text.index(plant["anchor"]) + len(plant["anchor"])
    return f"{text[:index]} {plant['sentence']}{text[index:]}"


def build(user: dict, row: dict, plants: dict, variant: str, split, clip, limit: int):
    """한 사용자의 감독 입력과, 심은(또는 바꾼) 문장의 (문항, 번호)를 돌려준다."""
    questions = {q["question_id"]: q for q in user["questions"]}
    payload_questions, sentences_by_q, planted = [], {}, []
    for q in row["questions"]:
        text = q["text"]
        plant = next((p for p in plants.get(variant, []) if p["user"] == user["id"] and p["question_id"] == q["question_id"]), None)
        if plant and variant == "planted":
            text = with_plant(text, plant)
        elif plant and variant == "subtle":
            assert text.count(plant["find"]) == 1, plant["find"]
            text = text.replace(plant["find"], plant["replace"])
        sentences = split(text)
        if plant:
            target = plant["sentence"] if variant == "planted" else plant["replace"]
            seq = next(i for i, sentence in enumerate(sentences, start=1) if target in sentence or sentence in target and len(sentence) > 10)
            planted.append({"question_id": q["question_id"], "sentence_seq": seq, "code": plant.get("code", "UNSUPPORTED_CLAIM")})
        sentences_by_q[q["question_id"]] = sentences
        payload_questions.append({
            "question_id": q["question_id"],
            "text": clip(questions[q["question_id"]]["text"], limit),
            "theme": q["theme"],
            "sentences": [{"seq": i, "text": s} for i, s in enumerate(sentences, start=1)],
            "sources": [clip(t, limit) for t in sources_of(user, q)],
        })
    payload = {
        "job_role": clip(user.get("job_role"), limit),
        "memo": clip(user.get("memo"), limit),
        "profile": [{"kind": p["kind"], "text": clip(p["text"], limit)} for p in user.get("profile") or []],
        "questions": payload_questions,
    }
    return payload, sentences_by_q, planted


async def run(model: str, reps: int) -> None:
    from services.cover_letter_check import has_blank, split_sentences
    from services.cover_letter_drafter import REVIEW_PROMPT, CoverLetterDrafter, ReviewOutput
    from services.cover_letter_llm import CoverLetterLLMUnavailable, call_until_valid, clip
    from services.llm_executors import COVER_LETTER_JUDGE_EXECUTOR
    from services.llm_settings import EnvironmentLLMClient

    from evals.common import llm_harness
    llm_harness.load_env()
    if not (os.environ.get("GITORY_LLM_BASE_URL") and os.environ.get("GITORY_LLM_API_KEY")):
        sys.exit("GITORY_LLM_BASE_URL·GITORY_LLM_API_KEY 가 환경변수나 키 파일에 없습니다")
    short = model.split("/")[-1]
    os.environ["GITORY_COVER_LETTER_REVIEW_MODEL"] = model
    os.environ["GITORY_EVAL_TAG"] = f"cl-review-{short}"
    llm_harness.install()
    client = EnvironmentLLMClient("COVER_LETTER_REVIEW")
    drafter = CoverLetterDrafter(client, client)
    limits = drafter._limits
    users, run_data, plants = load()
    gate = asyncio.Semaphore(4)
    loop = asyncio.get_running_loop()

    async def one(variant: str, rep: int, row: dict) -> dict:
        user = users[row["user"]]
        payload, sentences, planted = build(user, row, plants, variant, split_sentences, clip, limits.max_material_chars)
        async with gate:
            started = time.perf_counter()
            try:
                out = await call_until_valid(
                    client, REVIEW_PROMPT, payload, ReviewOutput, limits.review_max_completion_tokens,
                    lambda o: drafter._review_problem(o, sentences), loop.time() + 300, limits, "전체 검토",
                    executor=COVER_LETTER_JUDGE_EXECUTOR,
                )
                error = None
            except CoverLetterLLMUnavailable as failure:
                out, error = None, str(failure)[:200]
            elapsed = int((time.perf_counter() - started) * 1000)
        findings = []
        for found in (out.findings if out else []):
            sentence = sentences[found.question_id][found.sentence_seq - 1]
            if has_blank(sentence):  # 운영 코드도 빈칸 줄은 돌려보내지 않는다
                continue
            findings.append({"question_id": found.question_id, "sentence_seq": found.sentence_seq,
                             "code": found.code, "reason": found.reason, "sentence": sentence})
        return {"model": model, "variant": variant, "rep": rep, "user": row["user"], "ok": out is not None,
                "error": error, "elapsed_ms": elapsed, "planted": planted, "findings": findings}

    jobs = [one(v, r, row) for r in range(1, reps + 1) for v in VARIANTS for row in run_data["users"]]
    rows = await asyncio.gather(*jobs)
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"{short}.json"
    path.write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
    print("저장", path, "· 실패", sum(not r["ok"] for r in rows), "/", len(rows))


def ledger() -> list[dict]:
    """비용 장부(evals/results/spend.jsonl). 모델마다 태그 cl-review-<모델> 로 적힌다."""
    from evals.common import llm_harness
    if not llm_harness.LEDGER.exists():
        return []
    return [json.loads(line) for line in open(llm_harness.LEDGER, encoding="utf-8")]


def dry_run() -> None:
    """심은 문장 자리(anchor·find)가 w-luna 초안에 있는지, 사용자·문항이 맞는지 본다. 초안이 없으면 입력 파일만 센다."""
    users, run_data, plants = load_inputs()
    count = sum(len(v) for v in plants.values())
    print(f"사용자 {len(users)}명 · 심은 문장 {len(plants['planted'])}개 · 미묘하게 바꿀 문장 {len(plants['subtle'])}개 · 합 {count}")
    if not DRAFTS.exists():
        print(f"초안 {DRAFTS.name} 이 아직 없어 심을 자리는 확인하지 못했습니다")
        return
    drafts = {row["user"]: {q["question_id"]: q["text"] for q in row["questions"]} for row in json.load(open(DRAFTS, encoding="utf-8"))["users"]}
    for variant, key in (("planted", "anchor"), ("subtle", "find")):
        missing = [p for p in plants[variant] if p[key] not in drafts.get(p["user"], {}).get(p["question_id"], "")]
        print(f"{variant}: 초안에서 자리를 찾음 {len(plants[variant]) - len(missing)}/{len(plants[variant])}")


def report() -> None:
    spend = ledger()
    labels_path = HERE / "adjudication.json"
    labels = json.load(open(labels_path, encoding="utf-8")) if labels_path.exists() else {}
    flagged: dict[str, set] = {}
    print("| 모델 | 티 나게 심은 문제 찾음 | 미묘하게 바꾼 문장 찾음(근거 없음으로) | 원래 초안에서 건 문장 | 그중 진짜 문제 | 실패 | 호출당 원 | 중앙 지연 |")
    print("|---|---|---|---|---|---|---|---|")
    for path in sorted(OUT.glob("*.json")):
        rows = json.load(open(path, encoding="utf-8"))
        model = rows[0]["model"]
        reps = sorted({r["rep"] for r in rows})
        found, right, orig, real = [], [], [], []
        for rep in reps:
            for variant, sink, strict in (("planted", found, False), ("subtle", right, True)):
                hit = total = 0
                for r in (r for r in rows if r["rep"] == rep and r["variant"] == variant):
                    for p in r["planted"]:
                        total += 1
                        match = [f for f in r["findings"] if (f["question_id"], f["sentence_seq"]) == (p["question_id"], p["sentence_seq"])]
                        hit += any(f["code"] == p["code"] for f in match) if strict else bool(match)
                if total:
                    sink.append(f"{hit}/{total}")
            original = [f for r in rows if r["rep"] == rep and r["variant"] == "original" for f in r["findings"]]
            orig.append(str(len(original)))
            for f in original:
                flagged.setdefault(f["sentence"], set()).add(path.stem)
            judged = [labels.get(f["sentence"]) for f in original]
            real.append(f"{sum(j == 'REAL' for j in judged)}/{sum(j is not None for j in judged)}" if any(j is not None for j in judged) else "-")
        calls = [s for s in spend if s.get("tag") == f"cl-review-{path.stem}"]
        per_call = sum(s["won"] for s in calls) / max(1, len(calls))
        latency = statistics.median(r["elapsed_ms"] for r in rows) / 1000
        print(f"| {model} | {' · '.join(found)} | {' · '.join(right)} | {' · '.join(orig)} | {' · '.join(real)} | "
              f"{sum(not r['ok'] for r in rows)}/{len(rows)} | {per_call:.1f} | {latency:.0f}s |")
    pending = sorted(s for s in flagged if s not in labels)
    if pending:
        target = OUT.parent / "to-adjudicate.json"
        target.write_text(json.dumps(pending, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"\n판정할 문장 {len(pending)}개 → {target.name} (모델 이름 없이)")


def main() -> None:
    parser = argparse.ArgumentParser(description="자소서 전체 검토 모델 비교. 쓰는 법은 파일 맨 위 설명에 있다.")
    parser.add_argument("step", nargs="?", choices=["run", "report"])
    parser.add_argument("model", nargs="?")
    parser.add_argument("--reps", type=int, default=2)
    parser.add_argument("--dry-run", action="store_true", help="LLM 을 부르지 않고 심은 문장 자리만 본다")
    args = parser.parse_args()
    if args.dry_run:
        dry_run()
    elif args.step is None:
        parser.error("run <모델>, report, --dry-run 중 하나가 필요합니다")
    elif args.step == "run":
        if not args.model:
            parser.error("run 뒤에 모델 이름이 필요합니다")
        asyncio.run(run(args.model, args.reps))
    else:
        report()


if __name__ == "__main__":
    sys.exit(main())

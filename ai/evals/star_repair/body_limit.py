"""#509 PR 본문 상한 1,000자 ↔ 3,000자 첫 초안 비교. 본문이 1,000자를 넘는 PR 만, 같은 diff 근거로 상한만 바꿔 만든다.

1,000자 쪽은 운영 코드 그대로가 아니라 지금 코드에서 상한과 프롬프트 설명만 되돌린 것이라, 차이는 #509 만의 효과다.
measure.py 의 1차 50개 표본과 diff 근거(results/star_repair/diff)가 먼저 있어야 한다.

    cd ai
    python evals/star_repair/body_limit.py run --dry-run   # LLM 없이 대상 PR 수만 센다
    python evals/star_repair/body_limit.py run             # 1,000·3,000 첫 초안을 만들어 body-limit/<상한>/ 에 쌓는다(있으면 건너뜀)
    python evals/star_repair/body_limit.py report          # 칸 수·확인 필요·1,000자 뒤 내용을 쓴 칸·토큰·비용을 견준다
"""

from __future__ import annotations

import asyncio
import json
import argparse
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import measure  # noqa: E402

OUT = measure.DEFAULT_OUT / "body-limit"
LIMITS = (1_000, 3_000)
SLOTS = ("S", "T", "A", "R")


def norm(text: str | None) -> str:
    return " ".join((text or "").split())


def long_body_prs() -> list[dict]:
    return [p for p in measure.selected("first") if len(norm(p["body"])) > 1_000]


async def make(pr: dict, limit: int, gate: asyncio.Semaphore) -> None:
    from services import star_generator
    from services.star_generator import StarGenerationLimits, StarGenerator

    out = OUT / str(limit) / f"{measure.key_of(pr)}.json"
    if out.exists():
        return
    evidence = json.loads((measure.DIFF_DIR / f"{measure.key_of(pr)}.json").read_text(encoding="utf-8"))["evidence"]
    async with gate:
        writer = measure.Recording(measure.client_for("gpt-5.6-luna", "STAR"), "gpt-5.6-luna")
        generator = StarGenerator(writer, limits=StarGenerationLimits(max_pull_request_body_chars=limit))
        request = measure.star_request(pr, evidence)
        started = time.perf_counter()
        try:
            response = await generator.generate(request)
        except Exception as exc:
            print(f"[실패] {limit} {measure.key_of(pr)}: {type(exc).__name__}: {str(exc)[:300]} calls={[c.get('error') or c.get('completion_tokens') for c in writer.calls]}")
            return
        out.write_text(json.dumps({
            "payload": generator.build_payload(request, request.evidence),
            "response": response.model_dump(),
            "calls": writer.calls,
            "ms": round((time.perf_counter() - started) * 1000),
        }, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"[초안] {limit} {measure.key_of(pr)}")


async def run_all() -> None:
    from services import star_generator

    gate = asyncio.Semaphore(4)
    for limit in LIMITS:
        (OUT / str(limit)).mkdir(parents=True, exist_ok=True)
        # 프롬프트는 모듈 전역을 부를 때 읽으므로, 상한마다 설명 숫자를 맞춘 뒤 그 상한의 초안을 다 만든다
        star_generator.SYSTEM_PROMPT = star_generator.SYSTEM_PROMPT.replace(
            "앞 3,000자" if limit == 1_000 else "앞 1,000자", f"앞 {limit:,}자")
        assert f"앞 {limit:,}자" in star_generator.SYSTEM_PROMPT
        await asyncio.gather(*(make(pr, limit, gate) for pr in long_body_prs()))


def shingles(text: str, size: int = 6) -> set[str]:
    compact = re.sub(r"\s+", "", text)
    return {compact[i:i + size] for i in range(len(compact) - size + 1)}


def numbers(text: str) -> set[str]:
    return set(re.findall(r"\d[\d,.]*\d|\d", text))


def known_text(pr: dict, payload: dict) -> str:
    """1,000자 앞 본문 말고 모델이 원래 볼 수 있는 글 — 제목과 근거."""
    parts = [pr["title"]]
    for item in payload["evidence"]:
        parts += [item.get("message") or "", item.get("summary") or "", " ".join(item.get("technical_points") or []),
                  " ".join(item.get("changed_paths") or [])]
    return " ".join(parts)


def score(pr: dict, data: dict) -> dict:
    body = norm(pr["body"])
    head, tail = body[:1_000], body[1_000:3_000]
    known = head + " " + known_text(pr, data["payload"])
    tail_only = shingles(tail) - shingles(known)
    tail_numbers = numbers(tail) - numbers(known)
    filled = review = tail_slots = number_slots = 0
    for slot in SLOTS:
        cell = data["response"]["star"].get(slot) or {}
        text = norm(cell.get("text"))
        if not text:
            continue
        filled += 1
        review += cell.get("status") == "NEEDS_REVIEW"
        tail_slots += bool(shingles(text) & tail_only)
        number_slots += bool(numbers(text) & tail_numbers)
    call = data["calls"][-1]
    return {"filled": filled, "review": review, "tail": tail_slots, "tail_number": number_slots,
            "prompt_tokens": call.get("prompt_tokens") or 0, "won": call.get("won") or 0, "ms": data["ms"]}


def report() -> None:
    prs = long_body_prs()
    runs = {f"{limit:,}자": OUT / str(limit) for limit in LIMITS}
    runs["3,000자 (③-3 측정 때)"] = measure.DRAFT_DIR
    print(f"본문이 1,000자를 넘는 PR {len(prs)}장, 칸 {len(prs) * 4}개")
    for name, folder in runs.items():
        rows = []
        for pr in prs:
            path = folder / f"{measure.key_of(pr)}.json"
            if path.exists():
                rows.append(score(pr, json.loads(path.read_text(encoding="utf-8"))))
        if not rows:
            continue
        total = {k: sum(r[k] for r in rows) for k in rows[0]}
        n = len(rows)
        print(f"{name:>22} | 초안 {n} | 채운 칸 {total['filled']} | 확인 필요 {total['review']} | "
              f"1,000자 뒤 글을 쓴 칸 {total['tail']} (그중 숫자 {total['tail_number']}) | "
              f"입력 {total['prompt_tokens'] / n:,.0f}토큰 | {total['won'] / n:.2f}원 | {total['ms'] / n / 1000:.1f}초")


def main() -> None:
    global OUT
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("step", choices=["run", "report"])
    parser.add_argument("--output", type=Path, default=measure.DEFAULT_OUT, help="measure.py 결과 폴더")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    measure.use_set(args.output)
    OUT = args.output / "body-limit"
    if args.dry_run:
        targets = long_body_prs()
        cached = sum((measure.DIFF_DIR / f"{measure.key_of(p)}.json").exists() for p in targets)
        print(f"[dry-run] 본문이 1,000자를 넘는 PR {len(targets)}개 · diff 근거가 있는 PR {cached} · 상한 {LIMITS}")
        return
    if args.step == "report":
        report()
        return
    measure.llm_harness.install()
    asyncio.run(run_all())


if __name__ == "__main__":
    main()

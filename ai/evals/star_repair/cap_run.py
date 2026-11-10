"""#555 카드 초안 출력 상한 4,000 으로 1차 50장을 두 번 만들어 실패 수와 토큰 사용량을 잰다(결과는 cap/<회차>/).

2,000 을 넘긴 호출은 예전 상한이면 실패했을 호출이다.
measure.py 의 1차 50개 표본과 diff 근거(results/star_repair/diff)가 먼저 있어야 한다.

    cd ai
    python evals/star_repair/cap_run.py run --dry-run
    python evals/star_repair/cap_run.py run
    python evals/star_repair/cap_run.py report
"""

from __future__ import annotations

import argparse
import asyncio
import json
import statistics
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import measure  # noqa: E402

OUT = measure.DEFAULT_OUT / "cap"
ROUNDS = 2


async def make(pr, round_no, gate):
    from services.star_generator import StarGenerator
    out = OUT / str(round_no) / f"{measure.key_of(pr)}.json"
    if out.exists():
        return
    diff = measure.DIFF_DIR / f"{measure.key_of(pr)}.json"
    if not diff.exists():
        return
    evidence = json.loads(diff.read_text(encoding="utf-8"))["evidence"]
    async with gate:
        writer = measure.Recording(measure.client_for("gpt-5.6-luna", "STAR"), "gpt-5.6-luna")
        started = time.perf_counter()
        try:
            await StarGenerator(writer).generate(measure.star_request(pr, evidence))
            error = None
        except Exception as exc:
            error = f"{type(exc).__name__}: {exc}"[:200]
        out.write_text(json.dumps({"error": error, "calls": writer.calls,
                                   "ms": round((time.perf_counter() - started) * 1000)}, ensure_ascii=False, indent=1),
                       encoding="utf-8")
        print(f"[{'실패' if error else '초안'}] {round_no} {measure.key_of(pr)}", flush=True)


async def run_all():
    gate = asyncio.Semaphore(4)
    for round_no in range(1, ROUNDS + 1):
        (OUT / str(round_no)).mkdir(parents=True, exist_ok=True)
        await asyncio.gather(*(make(pr, round_no, gate) for pr in measure.selected("first")))


def report():
    sys.stdout.reconfigure(encoding="utf-8")
    runs, failures, tokens, ms, won = 0, [], [], [], 0.0
    for f in sorted(OUT.glob("*/*.json")):
        d = json.loads(f.read_text(encoding="utf-8"))
        runs += 1
        if d["error"]:
            failures.append((f.parent.name, f.stem, d["error"]))
        tokens += [c["completion_tokens"] for c in d["calls"] if c.get("completion_tokens")]
        won += sum(c.get("won") or 0 for c in d["calls"])
        ms.append(d["ms"])
    tokens.sort()
    n = len(tokens)
    print(f"초안 {runs}번 · 실패 {len(failures)} {failures}")
    print(f"출력 토큰 중앙값 {statistics.median(tokens)} · p95 {tokens[int(n * .95)]} · 최대 {tokens[-1]} · 2,000 넘김 {sum(t > 2000 for t in tokens)}")
    print(f"카드당 {won / runs:.2f}원 · {statistics.median(ms) / 1000:.1f}초(중앙값)")


def main():
    global OUT
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("step", choices=["run", "report"])
    parser.add_argument("--output", type=Path, default=measure.DEFAULT_OUT, help="measure.py 결과 폴더")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    measure.use_set(args.output)
    OUT = args.output / "cap"
    if args.dry_run:
        prs = measure.selected("first")
        cached = sum((measure.DIFF_DIR / f"{measure.key_of(p)}.json").exists() for p in prs)
        print(f"[dry-run] 1차 표본 PR {len(prs)}개 · diff 근거가 있는 PR {cached} · {ROUNDS}번씩")
        return
    if args.step == "report":
        report()
        return
    measure.llm_harness.install()
    asyncio.run(run_all())


if __name__ == "__main__":
    main()

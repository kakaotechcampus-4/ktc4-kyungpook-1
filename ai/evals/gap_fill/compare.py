"""모자란 조건 채우기 첫 판단(찾아볼지)을 가린 정답과 견준다.

정답 파일은 [{condition_id, gold: SEARCH|SKIP, why}] 이고 export.py 로 뽑은 같은 조건 id 에 맞춰 만든다. LLM 을 부르지 않는다.
놓침 = AI 가 SKIP 했는데 정답이 SEARCH(지원자가 가진 경험을 놓칠 수 있어 더 나쁨), 헛걸음 = AI 가 SEARCH 했는데 정답이 SKIP.

    cd ai
    python evals/gap_fill/compare.py evals/results/gap_fill/run1.jsonl <정답.json>
"""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("results", type=Path, help="run.py 가 쓴 결과 jsonl")
    parser.add_argument("gold", type=Path, help="정답 json")
    args = parser.parse_args()
    runs = {row["condition_id"]: row for row in map(json.loads, args.results.read_text(encoding="utf-8").splitlines())}
    gold = {row["condition_id"]: row for row in json.loads(args.gold.read_text(encoding="utf-8"))}
    shared = [cid for cid in gold if cid in runs]
    agree = sum(runs[c]["decision"] == gold[c]["gold"] for c in shared)
    missed = [c for c in shared if runs[c]["decision"] == "SKIP" and gold[c]["gold"] == "SEARCH"]
    wasted = [c for c in shared if runs[c]["decision"] == "SEARCH" and gold[c]["gold"] == "SKIP"]
    print(f"조건 {len(shared)} · 일치 {agree}/{len(shared)} ({agree / len(shared):.0%})")
    print(f"정답 SEARCH {sum(gold[c]['gold'] == 'SEARCH' for c in shared)} · SKIP {sum(gold[c]['gold'] == 'SKIP' for c in shared)}")
    print(f"AI SEARCH {sum(runs[c]['decision'] == 'SEARCH' for c in shared)} · SKIP {sum(runs[c]['decision'] == 'SKIP' for c in shared)}")
    print(f"놓침(AI SKIP·정답 SEARCH) {len(missed)} · 헛걸음(AI SEARCH·정답 SKIP) {len(wasted)}")
    by_category = Counter()
    hit = Counter()
    for c in shared:
        by_category[runs[c]["category"]] += 1
        hit[runs[c]["category"]] += runs[c]["decision"] == gold[c]["gold"]
    print("분야별 일치:", ", ".join(f"{k} {hit[k]}/{v}" for k, v in by_category.items()))
    for label, ids in (("놓침", missed), ("헛걸음", wasted)):
        for c in ids:
            print(f"[{label}] {runs[c]['text'][:70]} | AI: {runs[c]['reason']} | 정답: {gold[c]['why']}")


if __name__ == "__main__":
    main()

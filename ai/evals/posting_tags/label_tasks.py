"""정답 만들기 과제를 만든다 — AI 가 붙인 이름표·일반 조건 종류·학력 값은 빼고 공고 본문과 조건 원문만 준다.

과제를 N 묶음(tasks_1.json …)으로 나눠 ai/evals/results/posting_tags/tasks/ 에 쓴다(공고 본문이 들어 있어 git 이 무시하는 곳).
이미 만들어 둔 정답은 gold/ 에 있어 새 정답을 만들 때만 쓴다. LLM 을 부르지 않는다.

    cd ai
    python evals/posting_tags/label_tasks.py --parts 4
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

RESULTS_DIR = Path(__file__).resolve().parents[1] / "results" / "posting_tags"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--postings", type=Path, default=RESULTS_DIR / "postings.jsonl")
    parser.add_argument("--results", type=Path, default=RESULTS_DIR / "results.jsonl")
    parser.add_argument("--output", type=Path, default=RESULTS_DIR / "tasks", help="과제 json 을 쓸 폴더")
    parser.add_argument("--parts", type=int, default=4, help="과제를 나눌 묶음 수")
    args = parser.parse_args()
    postings_path, results_path, out_dir, parts = args.postings, args.results, args.output, args.parts
    postings = {(p["company"], p["id"]): p for p in map(json.loads, postings_path.read_text(encoding="utf-8").splitlines())}
    tasks = []
    for row in map(json.loads, results_path.read_text(encoding="utf-8").splitlines()):
        if "result" not in row:
            continue
        posting = postings[(row["company"], row["id"])]
        criteria = [
            {"i": i, "type": c["criterion_type"], "quote": c["source_quote"]}
            for i, c in enumerate(row["result"]["criteria"])
            if c["criterion_type"] != "RESPONSIBILITY"
        ]
        tasks.append({"key": f"{row['company']}/{row['id']}", "title": posting["title"], "text": posting["text"],
                      "criteria": criteria})
    out_dir.mkdir(parents=True, exist_ok=True)
    for n in range(parts):
        chunk = tasks[n::parts]
        (out_dir / f"tasks_{n + 1}.json").write_text(json.dumps(chunk, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"tasks_{n + 1}.json: 공고 {len(chunk)}건, 조건 {sum(len(t['criteria']) for t in chunk)}개")


if __name__ == "__main__":
    main()

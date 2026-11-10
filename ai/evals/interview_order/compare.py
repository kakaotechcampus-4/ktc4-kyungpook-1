"""칸 고르기 결과를 정답과 견준다.

    cd ai
    python evals/interview_order/compare.py evals/results/interview_order/run1.jsonl
"""

import argparse
import json

parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
parser.add_argument("results", help="run.py 가 쓴 .jsonl")
rows = [json.loads(line) for line in open(parser.parse_args().results, encoding="utf-8")]
thin = [r for r in rows if r["id"].split("-")[1] == "thin"]
rich = [r for r in rows if r["id"].split("-")[1] == "rich"]
ok = lambda r: r["chosen"] == r["gold"]
print(f"전체 {sum(map(ok, rows))}/{len(rows)} · 얇은 답 다시 묻기 {sum(map(ok, thin))}/{len(thin)} · 충분한 답 넘어가기 {sum(map(ok, rich))}/{len(rich)}")
for r in rows:
    if not ok(r):
        print(f"[틀림] {r['id']} 정답 {r['gold']} · 고른 칸 {r['chosen']} ({r['action']}) · 답: {r['answer'][:40]} · 이유: {r['reason']}")

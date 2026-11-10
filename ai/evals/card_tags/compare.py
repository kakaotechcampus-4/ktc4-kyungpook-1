"""카드 이름표를 정답과 견준다. 판정 파일이 있으면 갈린 이름표는 판정을 정답으로 쓴다.

    cd ai
    python evals/card_tags/compare.py <결과.jsonl> evals/card_tags/gold [evals/card_tags/adjudicated.json] [갈린곳_저장.json]

정답은 gold/ 의 직접 단 이름표, 판정 파일은 {"<key>#<TAG>": true|false} — 그 이름표가 이 카드에 맞는지.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("results", type=Path, help="run.py 가 쓴 .jsonl")
    parser.add_argument("gold", type=Path, help="gold_*.json 이 든 폴더")
    parser.add_argument("adjudicated", type=Path, nargs="?", help="갈린 이름표 판정 파일")
    parser.add_argument("dis_out", type=Path, nargs="?", help="갈린 곳을 저장할 파일")
    args = parser.parse_args()
    rows = [json.loads(l) for l in args.results.read_text(encoding="utf-8").splitlines() if l.strip()]
    gold = {}
    for f in sorted(args.gold.glob("gold_*.json")):
        for g in json.loads(f.read_text(encoding="utf-8")):
            gold[g["key"]] = set(g["techs"])
    adj = json.loads(args.adjudicated.read_text(encoding="utf-8")) if args.adjudicated and args.adjudicated.exists() else {}
    ai_total = gold_total = wrong = missing = cards = 0
    dis = []
    for r in rows:
        if "techs" not in r or r["key"] not in gold:
            continue
        cards += 1
        ai, right = set(r["techs"]), set(gold[r["key"]])
        for tag in ai ^ right:
            dis.append({"id": f"{r['key']}#{tag}", "key": r["key"], "tag": tag, "in_ai": tag in ai, "in_gold": tag in right})
        # 판정은 어느 결과에서 갈렸든 같은 카드의 정답에 똑같이 적용해, 두 결과가 같은 정답과 견주어지게 한다
        for tid, belongs in adj.items():
            key, tag = tid.rsplit("#", 1)
            if key == r["key"]:
                right = (right | {tag}) if belongs else (right - {tag})
        ai_total += len(ai); gold_total += len(right)
        wrong += len(ai - right); missing += len(right - ai)
    pct = lambda a, b: f"{a}/{b} ({100 * a / b:.1f}%)" if b else f"{a}/0"
    print(json.dumps({"카드": cards, "실패": sum("error" in r for r in rows), "이름표 수(평균)": round(ai_total / max(cards, 1), 2),
                      "잘못 붙음": pct(wrong, ai_total), "빠짐": pct(missing, gold_total), "갈린 곳": len(dis)},
                     ensure_ascii=False, indent=1))
    if args.dis_out:
        args.dis_out.write_text(json.dumps(dis, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()

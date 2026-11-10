"""매칭 2단계 조건 판단 결과를 10-18 정답(gold.json)과 견준다. 판정 파일이 있으면 갈린 짝은 판정을 정답으로 쓴다.

임베딩과 같은 셈: 정밀도 = 맞힌 짝 / 채움이라 한 짝, 재현율 = 맞힌 짝 / 문장마다 min(정답 수, 10) 의 합.
판정 파일은 {"<case_id>|<posting_key>|<index>": true|false} 이고 이 폴더의 adjudicated.json 이 그것이다.
LLM 을 부르지 않는다.

    cd ai
    python evals/match_judge/compare.py evals/results/match_judge/run1.jsonl evals/match_judge/adjudicated.json
    python evals/match_judge/compare.py evals/results/match_judge/run1.jsonl --disagreements evals/results/match_judge/dis1.json
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent


def score(pred: set, gold: set, cases: list[str], gold_by_case: dict) -> dict:
    hits = len(pred & gold)
    cap = sum(min(len(gold_by_case.get(c, set())), 10) for c in cases)
    return {"채움이라 한 짝": len(pred), "맞힘": hits,
            "정밀도": round(hits / len(pred), 3) if pred else None,
            "재현율(10개 상한, 임베딩과 같은 셈)": round(hits / cap, 3) if cap else None,
            "재현율(상한 없음)": round(hits / len(gold), 3) if gold else None}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("results", type=Path, help="run.py 가 쓴 결과 jsonl")
    parser.add_argument("adjudicated", type=Path, nargs="?", help="갈린 짝의 판정 json(선택)")
    parser.add_argument("--disagreements", type=Path, help="정답과 갈린 짝을 저장할 json")
    args = parser.parse_args()
    rows = [json.loads(l) for l in args.results.read_text(encoding="utf-8").splitlines() if l.strip()]
    g = json.loads((HERE / "gold.json").read_text(encoding="utf-8"))
    adj = json.loads(args.adjudicated.read_text(encoding="utf-8")) if args.adjudicated and args.adjudicated.exists() else {}
    types = {(c["posting_key"], c["index"]): c["type"] for c in g["conditions"]}
    judged = set()
    for r in rows:
        if "error" in r:
            continue
        judged |= {(card, r["posting_key"], i) for card in r["cards"] for i in r["conditions"]}
    pred = {(m["card_id"], r["posting_key"], m["index"]) for r in rows if "error" not in r for m in r["met"]}
    gold = {(c["case_id"], k, i) for c in g["cases"] for k, i in c["relevant"]} & judged
    raw_gold = set(gold)
    for key, belongs in adj.items():
        case, posting, index = key.split("|")
        pair = (case, posting, int(index))
        gold = (gold | {pair}) if belongs else (gold - {pair})
    cases = [c["case_id"] for c in g["cases"]]
    empty = [c["case_id"] for c in g["cases"] if not c["relevant"]]
    out = {"호출 실패": sum("error" in r for r in rows), "판단한 짝": len(judged)}
    for name, keep in (("모든 조건", lambda p: True), ("필수·우대만", lambda p: types[(p[1], p[2])] in ("REQUIRED", "PREFERRED"))):
        p = {x for x in pred if keep(x)}
        gd = {x for x in gold if keep(x)}
        by_case: dict = {}
        for x in gd:
            by_case.setdefault(x[0], set()).add(x)
        out[name] = score(p, gd, cases, by_case)
    out["정답 없는 문장에 채움을 준 수"] = sum(any(x[0] == c for x in pred) for c in empty)
    print(json.dumps(out, ensure_ascii=False, indent=1))
    if args.disagreements:
        dis = [{"id": f"{x[0]}|{x[1]}|{x[2]}", "ai": x in pred, "gold": x in raw_gold} for x in sorted(pred ^ raw_gold)]
        args.disagreements.parent.mkdir(parents=True, exist_ok=True)
        args.disagreements.write_text(json.dumps(dis, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()

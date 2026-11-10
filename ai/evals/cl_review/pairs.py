"""두 모델이 같은 초안을 따로 검토했다고 보고, 지적을 합치는 규칙마다 결과를 센다(새 호출 없음).

규칙: either(둘 중 하나라도) · both(둘 다) · mixed(근거 없음은 하나라도, 상관없음·되풀이는 둘 다).
같은 회차(rep)끼리 짝짓는다. 원래 초안의 진짜 문제는 adjudication.json(모델 이름을 가리고 판정)을 쓴다.
measure_review.py run 으로 모델 둘 이상의 결과가 ai/evals/results/cl_review/runs/ 에 있어야 한다.

    cd ai
    python evals/cl_review/pairs.py
"""

from __future__ import annotations

import itertools
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import measure_review as m  # noqa: E402

labels = json.load(open(HERE / "adjudication.json", encoding="utf-8"))
REAL_TOTAL = sum(v == "REAL" for v in labels.values())
spend = m.ledger()
runs = {p.stem: json.load(open(p, encoding="utf-8")) for p in sorted(m.OUT.glob("*.json"))}


def per_call(name: str) -> float:
    calls = [s for s in spend if s.get("tag") == f"cl-review-{name}"]
    return sum(s["won"] for s in calls) / max(1, len(calls))


def flags(name: str, variant: str, rep: int) -> dict[tuple, set]:
    """(사용자, 문항, 문장 번호) → 건 이유들."""
    out: dict[tuple, set] = {}
    for r in runs[name]:
        if r["variant"] == variant and r["rep"] == rep:
            for f in r["findings"]:
                out.setdefault((r["user"], f["question_id"], f["sentence_seq"]), set()).add(f["code"])
    return out


def merge(a: dict, b: dict, rule: str) -> dict[tuple, set]:
    keys = set(a) | set(b)
    out = {}
    for k in keys:
        ca, cb = a.get(k, set()), b.get(k, set())
        if rule == "either":
            codes = ca | cb
        elif rule == "both":
            codes = (ca | cb) if ca and cb else set()
        else:  # mixed
            codes = ({"UNSUPPORTED_CLAIM"} & (ca | cb)) | (((ca | cb) - {"UNSUPPORTED_CLAIM"}) if ca and cb else set())
        if codes:
            out[k] = codes
    return out


def targets(name: str, variant: str, rep: int) -> list[dict]:
    return [dict(p, user=r["user"]) for r in runs[name] if r["variant"] == variant and r["rep"] == rep for p in r["planted"]]


def sentence_of(name: str, rep: int, key: tuple) -> str | None:
    for r in runs[name]:
        if r["variant"] == "original" and r["rep"] == rep and r["user"] == key[0]:
            for f in r["findings"]:
                if (f["question_id"], f["sentence_seq"]) == key[1:]:
                    return f["sentence"]
    return None


def score(names: tuple[str, ...], rule: str) -> dict:
    res = {"planted": [], "subtle": [], "orig": [], "real": []}
    for rep in (1, 2):
        merged = {}
        for v in ("planted", "subtle", "original"):
            fs = [flags(n, v, rep) for n in names]
            merged[v] = fs[0] if len(fs) == 1 else merge(fs[0], fs[1], rule)
        tp = targets(names[0], "planted", rep)
        res["planted"].append(sum(bool(merged["planted"].get((p["user"], p["question_id"], p["sentence_seq"]))) for p in tp) / len(tp))
        ts = targets(names[0], "subtle", rep)
        res["subtle"].append(sum("UNSUPPORTED_CLAIM" in merged["subtle"].get((p["user"], p["question_id"], p["sentence_seq"]), set()) for p in ts) / len(ts))
        sentences = {k: next(s for s in (sentence_of(n, rep, k) for n in names) if s) for k in merged["original"]}
        res["orig"].append(len(sentences))
        res["real"].append(sum(labels.get(s) == "REAL" for s in sentences.values()))
    avg = lambda xs: sum(xs) / len(xs)
    return {"names": " + ".join(names), "rule": rule, "planted": avg(res["planted"]), "subtle": avg(res["subtle"]),
            "flagged": avg(res["orig"]), "real": avg(res["real"]), "noise": avg(res["orig"]) - avg(res["real"]),
            "won": sum(per_call(n) for n in names)}


rows = [score((n,), "single") for n in runs]
rows += [score(pair, rule) for pair in itertools.combinations(runs, 2) for rule in ("either", "both", "mixed")]
print(f"원래 초안의 진짜 문제(모델을 가리고 판정한 합집합) {REAL_TOTAL}개 기준. 두 회차 평균.")
print("| 감독 | 규칙 | 티 나게 심음 | 미묘하게 바꿈 | 원래 초안 진짜 문제 잡음 | 헛짚음 | 검토 1번 원 |")
print("|---|---|---|---|---|---|---|")
for r in sorted(rows, key=lambda r: (-r["subtle"], -r["real"], r["noise"], r["won"])):
    print(f"| {r['names']} | {r['rule']} | {r['planted']:.0%} | {r['subtle']:.0%} | {r['real']:.1f}/{REAL_TOTAL} | {r['noise']:.1f} | {r['won']:.1f} |")

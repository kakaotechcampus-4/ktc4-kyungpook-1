"""사람 판정과 채점기 판정을 견준다 (평가 기준 2단계).

    cd ai
    python evals/cover_letter/criteria_compare.py human.txt haiku terra

human.txt 는 채점 페이지의 '결과 복사' 글을 그대로 붙인 파일이다(글1: K1=예 K2=아니오 ... | 메모). 저장소에는 없다.
채점기는 criteria_judge.py 가 남긴 results/cover_letter/runs/criteria-<label>.json 에서 읽고, 세 번 판정 중 다수결로 정하고, 애매는 사람 쪽 '아니오'로 본다.
"""

from __future__ import annotations

import json
import re
import sys
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
RUNS = HERE.parents[0] / "results" / "cover_letter" / "runs"
KS = ["K1", "K2", "K3", "K4", "K5", "K6", "K7"]


def human(path: str) -> dict[tuple[int, str], bool]:
    out = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        m = re.match(r"\s*글\s*(\d+)\s*:\s*(.*)", line)
        if not m:
            continue
        for k, v in re.findall(r"(K\d)=(\S+)", m.group(2).split("|")[0]):
            if v in ("예", "아니오", "애매"):
                out[(int(m.group(1)), k)] = v == "예"
    return out


def judge(label: str) -> dict[tuple[int, str], bool]:
    votes: dict[tuple[int, str], list[bool]] = {}
    for r in json.load(open(RUNS / f"criteria-{label}.json", encoding="utf-8"))["rows"]:
        if "final" in r:
            votes.setdefault((r["essay"], r["k"]), []).append(r["final"] == "PASS")
    return {key: Counter(v).most_common(1)[0][0] for key, v in votes.items()}


def kappa(pairs: list[tuple[bool, bool]]) -> float:
    n = len(pairs)
    if not n:
        return float("nan")
    po = sum(a == b for a, b in pairs) / n
    pa, pb = sum(a for a, _ in pairs) / n, sum(b for _, b in pairs) / n
    pe = pa * pb + (1 - pa) * (1 - pb)
    return 1.0 if pe == 1 else (po - pe) / (1 - pe)


def main() -> None:
    if len(sys.argv) < 3 or sys.argv[1] in ("-h", "--help"):
        print(__doc__)
        return
    h = human(sys.argv[1])
    print(f"사람 판정 {len(h)}칸, 통과 {sum(h.values())}칸")
    for label in sys.argv[2:]:
        j = judge(label)
        keys = [key for key in h if key in j]
        pairs = [(h[key], j[key]) for key in keys]
        agree = sum(a == b for a, b in pairs)
        print(f"\n[{label}] 일치 {agree}/{len(pairs)} · κ {kappa(pairs):.2f} · 채점기 통과 {sum(j[key] for key in keys)}칸")
        for k in KS:
            kp = [(h[key], j[key]) for key in keys if key[1] == k]
            miss = [f"글{key[0]}(사람 {'통과' if h[key] else '탈락'})" for key in keys if key[1] == k and h[key] != j[key]]
            print(f"  {k}: {sum(a == b for a, b in kp)}/{len(kp)}" + (f"  어긋남 {', '.join(miss)}" if miss else ""))


if __name__ == "__main__":
    main()

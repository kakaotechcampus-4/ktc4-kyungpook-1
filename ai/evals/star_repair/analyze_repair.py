"""첫 초안과 고쳐 쓴 최종본을 칸마다 견준다: 확인 필요 이유 전후, 바뀐 칸, 늘어난 호출·비용·시간.

repair 결과 이름은 results/star_repair/repair/ 안의 파일 이름이다(none · none_materials 등).
바뀐 칸은 grading/changed-<이름>.json 으로 남겨 다시 채점한다.

    cd ai
    python evals/star_repair/analyze_repair.py none
"""

import argparse
import json
from collections import Counter
from pathlib import Path

DEFAULT_OUT = Path(__file__).resolve().parents[1] / "results" / "star_repair"
parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
parser.add_argument("name", help="repair 결과 이름(none 등)")
parser.add_argument("--output", type=Path, default=DEFAULT_OUT, help="measure.py 결과 폴더")
args = parser.parse_args()
HERE, name = args.output, args.name
repaired = json.loads((HERE / "repair" / f"{name}.json").read_text(encoding="utf-8"))


def reason_kind(field: dict) -> str:
    reason = field.get("review_reason") or ""
    if "프로젝트 안에서만" in reason:
        return "CODE_NAME"
    if "수치" in reason:
        return "UNSOURCED_NUMBER"
    if "뜻이 다를" in reason:
        return "REVERSED"
    return reason[:20] or "기타"


before, after, changed = Counter(), Counter(), []
statuses = Counter()
won = ms = rewrites = cards_rewritten = 0
for key, result in repaired.items():
    draft = json.loads((HERE / "drafts" / f"{key}.json").read_text(encoding="utf-8"))
    first, final = draft["response"]["star"], result["response"]["star"]
    for slot in ("S", "T", "A", "R"):
        a, b = first[slot], final[slot]
        if a.get("status") == "NEEDS_REVIEW":
            before[reason_kind(a)] += 1
        if b.get("status") == "NEEDS_REVIEW":
            after[reason_kind(b)] += 1
        statuses[(a.get("status"), b.get("status"))] += 1
        if (a.get("text") or "") != (b.get("text") or ""):
            changed.append({"key": key, "slot": slot, "before": a.get("text"), "after": b.get("text"),
                            "before_status": a.get("status"), "after_status": b.get("status"),
                            "before_reason": a.get("review_reason")})
    calls = result["rewrites"] + result["reviews"]
    won += sum(c.get("won", 0) for c in calls)
    ms += result["ms"]
    rewrites += len(result["rewrites"])
    cards_rewritten += bool(result["rewrites"])

print(f"[{name}] 카드 {len(repaired)} · 다시 쓴 카드 {cards_rewritten} · 다시 쓰기 호출 {rewrites} · "
      f"늘어난 비용 {won:.1f}원 · 고쳐 쓰기에 든 시간 합 {ms / 1000:.0f}초(카드당 평균 {ms / max(1, len(repaired)) / 1000:.1f}초)")
print(f"  확인 필요 칸: 전 {dict(before)} → 후 {dict(after)}")
print(f"  칸 상태 바뀜: {[(k, v) for k, v in statuses.items() if k[0] != k[1]]}")
print(f"  글이 바뀐 칸 {len(changed)}개")
(HERE / "grading").mkdir(exist_ok=True)
(HERE / "grading" / f"changed-{name}.json").write_text(json.dumps(changed, ensure_ascii=False, indent=1),
                                                      encoding="utf-8")

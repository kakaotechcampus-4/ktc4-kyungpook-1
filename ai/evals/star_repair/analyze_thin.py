"""자료 열기(#478) 측정 분석. 본문 짧은 PR 표본에서 같은 첫 초안을 코드 검사로만 고쳐 쓴 것(none)과
자료를 열 수 있게 고쳐 쓴 것(none_materials)을 칸마다 견준다.

숫자를 찍고, 두 결과가 다른 칸을 results/star_repair/thin/grading/items.json 으로 남겨 채점한다.

    cd ai
    python evals/star_repair/analyze_thin.py
"""

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

THIN = Path(__file__).resolve().parents[1] / "results" / "star_repair" / "thin"
SLOTS = ("S", "T", "A", "R")
sys.stdout.reconfigure(encoding="utf-8")


def load(name: str) -> dict:
    return json.loads((THIN / "repair" / f"{name}.json").read_text(encoding="utf-8"))


def norm(text):
    return " ".join((text or "").split())


def summary(label: str, stars: list[dict], results: list[dict] | None = None) -> None:
    counts = Counter()
    for star in stars:
        for slot in SLOTS:
            cell = star[slot]
            if not norm(cell.get("text")):
                counts["빈 칸"] += 1
            elif cell.get("status") == "NEEDS_REVIEW":
                counts["확인 필요"] += 1
            else:
                counts["그대로 쓸 칸"] += 1
    line = f"{label:>14} | 카드 {len(stars)} · 칸 {len(stars) * 4} | " + " · ".join(
        f"{k} {counts[k]}" for k in ("그대로 쓸 칸", "확인 필요", "빈 칸"))
    if results:
        won = sum(c.get("won") or 0 for r in results for c in r["rewrites"])
        ms = sum(r["ms"] for r in results)
        opens = sum(r.get("tool_calls") or 0 for r in results)
        line += f" | 고쳐 쓰기 카드당 +{won / len(results):.2f}원 · +{ms / len(results) / 1000:.1f}초 · 자료 연 횟수 {opens}"
    print(line)


def main() -> None:
    global THIN
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--output", type=Path, default=THIN, help="본문 짧은 표본(thin)의 결과 폴더")
    THIN = parser.parse_args().output
    prs = {f"{p['repo']}__{p['number']}": p
           for p in json.loads((THIN / "prs.json").read_text(encoding="utf-8"))["others"]}
    plain, opened = load("none"), load("none_materials")
    keys = [k for k in prs if k in plain and k in opened]
    drafts = {k: json.loads((THIN / "drafts" / f"{k}.json").read_text(encoding="utf-8")) for k in keys}
    summary("첫 초안", [drafts[k]["response"]["star"] for k in keys])
    summary("코드 검사만", [plain[k]["response"]["star"] for k in keys], [plain[k] for k in keys])
    summary("자료 열기", [opened[k]["response"]["star"] for k in keys], [opened[k] for k in keys])

    kinds, actions = Counter(), Counter()
    items = []
    for k in keys:
        ids = {i for call in opened[k]["rewrites"] for i in call.get("opened_ids") or []}
        kinds.update("ISSUE" if i.startswith("issue-") else i for i in ids)
        for call in opened[k]["rewrites"]:
            for choice in (call.get("output") or {}).get("slots") or []:
                if isinstance(choice, dict) and "action" in choice:
                    actions[choice["action"]] += 1
        before, after = plain[k]["response"]["star"], opened[k]["response"]["star"]
        first = drafts[k]["response"]["star"]
        for slot in SLOTS:
            if norm(before[slot].get("text")) == norm(after[slot].get("text")):
                continue
            pr = prs[k]
            evidence = drafts[k]["payload"]["evidence"]
            items.append({
                "id": f"{k}:{slot}",
                "repo": pr["repo"], "number": pr["number"], "title": pr["title"], "author": pr["author"],
                "pull_request_body": norm(pr["body"]) or None,
                "evidence": [{"sha": e["sha"][:7], "message": e.get("message"), "summary": e.get("summary"),
                              "technical_points": e.get("technical_points")} for e in evidence],
                "materials": json.loads((THIN / "materials" / f"{k}.json").read_text(encoding="utf-8")),
                "opened_ids": sorted(ids),
                "slot": slot,
                "first_draft": norm(first[slot].get("text")) or None,
                "without_materials": norm(before[slot].get("text")) or None,
                "with_materials": norm(after[slot].get("text")) or None,
                "with_materials_status": after[slot].get("status"),
            })
    print(f"연 자료 종류(카드 기준): {dict(kinds)} · 고르기 행동: {dict(actions)}")
    print(f"두 결과가 다른 칸 {len(items)}개 → thin/grading/items.json")
    (THIN / "grading").mkdir(parents=True, exist_ok=True)
    (THIN / "grading" / "items.json").write_text(json.dumps(items, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()

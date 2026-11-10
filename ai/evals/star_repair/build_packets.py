"""채점용 묶음: PR 마다 원문(PR 본문·커밋 메시지·답변)과 diff 요약, 첫 초안의 글이 있는 칸만 한 파일에 모은다.

다른 팀 PR 원문이 들어가므로 results/star_repair/grading/packets/ 에만 쓴다. 채점 기준은 grading/RUBRIC.md 이다.

    cd ai
    python evals/star_repair/build_packets.py
"""

import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / "results" / "star_repair",
                    help="measure.py 결과 폴더")
BASE = parser.parse_args().output
DRAFTS = BASE / "drafts"
OUT = BASE / "grading" / "packets"
OUT.mkdir(parents=True, exist_ok=True)
PRS = {f"{p['repo']}__{p['number']}": p for group in ("ours", "others")
       for p in json.loads((BASE / "prs.json").read_text(encoding="utf-8"))[group]}

keys = []
for path in sorted(DRAFTS.glob("*.json")):
    if path.name.endswith(".error.json"):
        continue
    key = path.stem
    draft = json.loads(path.read_text(encoding="utf-8"))
    payload, star = draft["payload"], draft["response"]["star"]
    slots = {slot: field["text"] for slot, field in star.items() if field.get("text")}
    if not slots:
        continue
    lines = [f"# {key}", "", f"- 제목: {payload['title']}", f"- 카드 주인(PR 작성자): {PRS[key]['author']}", "",
             "## 원문 1 — PR 본문 (모델이 받은 앞부분)", "", payload.get("pull_request_body") or "(없음)", "",
             "## 원문 2 — 커밋 메시지 (작성자)", ""]
    for item in payload["evidence"]:
        lines.append(f"- [{item['sha'][:7]}] ({item.get('author_login')}) {item['message']}")
    lines += ["", "## 원문 3 — 확인 답변", ""] + ([f"- {a}" for a in payload["confirmed_answers"]] or ["(없음)"])
    lines += ["", "## 참고 — AI 가 만든 diff 요약 (사람이 쓴 글 아님, 틀릴 수 있음)", ""]
    for item in payload["evidence"]:
        lines.append(f"- [{item['sha'][:7]}] {item.get('change_kind')} {item.get('churn')} · {item.get('summary')}")
        for point in item.get("technical_points", []):
            lines.append(f"    - {point}")
        lines.append(f"    - 바뀐 파일: {', '.join(item.get('changed_paths', [])[:12])}")
    lines += ["", "## 채점할 칸 (첫 초안)", ""]
    for slot in ("S", "T", "A", "R"):
        if slot in slots:
            lines += [f"### {slot}", "", slots[slot], ""]
    (OUT / f"{key}.md").write_text("\n".join(lines), encoding="utf-8", newline="\n")
    keys.append((key, list(slots)))
print(len(keys), "packets ·", sum(len(s) for _, s in keys), "slots")
(BASE / "grading" / "keys.json").write_text(json.dumps([k for k, _ in keys], indent=1), encoding="utf-8")

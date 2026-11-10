"""자료 열기(#478) 측정용 본문 짧은 PR 표본을 고른다. 결과는 thin/prs.json(measure.py --set thin 이 읽음).

기준(2026-10-29 합의): select_prs.py 와 같되 템플릿 제목·주석을 뺀 작성자 본문이 100자 미만(빈 본문 포함)이고,
Spring 이 보낼 자료(연결 이슈·남의 리뷰·카드 주인 답 댓글)가 하나라도 있는 PR · 리포당 최대 5개 · 20개.
자료 없는 짧은 PR 수도 세어, 짧은 PR 중 자료 열기가 쓰일 수 있는 비율을 남긴다.
결과는 다른 팀 글이 들어 있어 ai/evals/results/star_repair/thin/ 에만 쓴다.
이미 고른 표본의 원문만 받으려면 select_prs.py --from-refs evals/star_repair/thin_refs.json --output evals/results/star_repair/thin 을 쓴다.

    cd ai
    python evals/star_repair/select_thin.py
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import random
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import select_prs as base  # noqa: E402
import measure  # noqa: E402

_gh_once = base.gh


def _gh_retry(path: str) -> object:
    """와이파이가 잠깐 끊기면 gh 가 실패해 표본 고르기가 처음부터 다시 돌아서, 세 번까지 다시 부른다."""
    for attempt in range(3):
        try:
            return _gh_once(path)
        except subprocess.CalledProcessError:
            if attempt == 2:
                raise
            time.sleep(10)


def _gh_json_retry(path: str) -> object:
    """measure.gh_json 은 실패를 '자료 없음' 으로 보므로, 404 같은 진짜 없음이 아닌 실패(끊김)만 다시 부른다."""
    for attempt in range(3):
        out = subprocess.run(["gh", "api", "--paginate", "--slurp", path], capture_output=True, text=True,
                             encoding="utf-8")
        if out.returncode == 0 or "HTTP 4" in out.stderr:
            break
        if attempt == 2:
            raise RuntimeError(f"gh api 실패: {path}")
        time.sleep(10)
    if out.returncode != 0 or not out.stdout.strip():
        return None
    pages = json.loads(out.stdout)
    if pages and isinstance(pages[0], list):
        return [item for page in pages for item in page]
    return pages[0] if pages else None


base.gh = _gh_retry
measure.gh_json = _gh_json_retry
SEED = 20261029
PER_REPO = 5
TOTAL = 20
MAX_BODY_CHARS = 100
DEFAULT_THIN = measure.DEFAULT_OUT / "thin"
THIN = DEFAULT_THIN


def listed(repo: dict) -> list[dict]:
    prs = base.gh_pages(f"repos/{base.ORG}/{repo['name']}/pulls?state=closed")
    return [
        pr for pr in prs
        if pr.get("merged_at")
        and not base.is_bot(pr["user"]["login"])
        and not (pr["head"]["ref"] == "develop" and pr["base"]["ref"] == "main")
        and len(base.body_text(pr["body"])) < MAX_BODY_CHARS
    ]


def has_materials(pr: dict) -> bool:
    with contextlib.redirect_stdout(io.StringIO()):
        measure.gather_materials([pr])
    materials = json.loads((measure.MATERIAL_DIR / f"{measure.key_of(pr)}.json").read_text(encoding="utf-8"))
    return any(materials.values())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--output", type=Path, default=DEFAULT_THIN, help="thin/prs.json 과 materials/ 를 쓸 폴더")
    args = parser.parse_args()
    global THIN
    THIN = args.output
    measure.use_set(THIN)
    THIN.mkdir(parents=True, exist_ok=True)
    rng = random.Random(SEED)
    repos = sorted(
        (r for r in base.gh_pages(f"orgs/{base.ORG}/repos?type=public") if r["name"].startswith("ktc4-")),
        key=lambda r: r["name"],
    )
    pool: list[dict] = []
    checked = without = 0
    for repo in repos:
        prs = sorted(listed(repo), key=lambda pr: pr["number"])
        rng.shuffle(prs)
        kept = []
        for pr in prs:
            if len(kept) == PER_REPO:
                break
            picked = base.candidate(repo, pr)
            if not picked:
                continue
            checked += 1
            if has_materials(picked):
                kept.append(picked)
            else:
                without += 1
        print(f"{repo['name']}: 짧은 본문 {len(prs)} · 고름 {len(kept)}", file=sys.stderr)
        pool.extend(kept)

    rng.shuffle(pool)
    chosen = pool[:TOTAL]
    (THIN / "prs.json").write_text(json.dumps({
        "seed": SEED,
        "criteria": __doc__,
        "ours": [],
        "others": chosen,
        "first_batch": [f"{p['repo']}#{p['number']}" for p in chosen],
        "checked": {"thin_usable": checked, "without_materials": without, "pool": len(pool)},
    }, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"기준 통과 짧은 PR {checked} 중 자료 없음 {without} · 자료 있음 {len(pool)} -> {len(chosen)}", file=sys.stderr)


if __name__ == "__main__":
    main()

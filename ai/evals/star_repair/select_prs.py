"""STAR 고쳐 쓰기 측정에 쓸 PR 표본을 고르거나, 이미 고른 표본의 원문(본문·커밋)을 받아 둔다.

기준(2026-10-28 합의): 머지된 PR · 템플릿 제목·주석을 뺀 작성자 본문 100자 이상 ·
제품이 카드 후보에서 빼는 PR(문서·저장소 설정만 또는 의존성 목록만 바꿈, CommitGrouper 규칙) 제외 ·
작성자 자기 커밋 1~20개 · 바뀐 줄 2,000 이하 · 봇·develop→main 제외 · 리포당 최대 5개.
우리 16개 중 기준을 넘는 것 + 다른 팀을 합쳐 100개, 고정 seed 로 섞어 둔다. 1차 50개 = 우리 + 다른 팀 앞쪽.
읽기는 gh api 로만 한다. 결과에는 다른 팀 글이 들어 있으므로 ai/evals/results/ (git 이 무시) 에만 쓴다.

    cd ai
    python evals/star_repair/select_prs.py --from-refs evals/star_repair/prs_refs.json   # 고른 표본의 원문만 받음
    python evals/star_repair/select_prs.py                                               # 기준대로 새로 고름
"""

from __future__ import annotations

import argparse
import json
import random
import re
import subprocess
import sys
from pathlib import Path

_AI_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_AI_ROOT))

from services.commit_grouper import CommitGrouper  # noqa: E402

ORG = "kakaotechcampus-4"
OURS = "ktc4-kyungpook-1"
OUR_PRS = [72, 83, 50, 98, 230, 257, 278, 241, 134, 136, 244, 296, 294, 263, 291, 242]
SEED = 20261028
PER_REPO = 5
TOTAL = 100
FIRST_BATCH = 50
MIN_BODY_CHARS = 100
MAX_OWN_COMMITS = 20
MAX_CHANGED_LINES = 2_000
DEFAULT_OUT = _AI_ROOT / "evals" / "results" / "star_repair"


def unusable(paths: list[str]) -> bool:
    """제품의 CommitGrouper.is_unusable 과 같은 판정을 PR 의 바뀐 파일 목록에 한다."""
    return all(CommitGrouper._is_documentation(p) for p in paths) or all(
        CommitGrouper._is_dependency_manifest(p) for p in paths
    )


def gh(path: str) -> object:
    run = subprocess.run(["gh", "api", path], capture_output=True, text=True, encoding="utf-8", check=True)
    return json.loads(run.stdout)


def gh_pages(path: str) -> list:
    items: list = []
    page = 1
    while True:
        sep = "&" if "?" in path else "?"
        chunk = gh(f"{path}{sep}per_page=100&page={page}")
        items.extend(chunk)
        if len(chunk) < 100:
            return items
        page += 1


def body_text(body: str | None) -> str:
    """템플릿 주석·제목 줄·빈 체크박스를 빼고 공백을 합친 본문."""
    text = re.sub(r"<!--.*?-->", "", body or "", flags=re.S)
    lines = [line for line in text.splitlines()
             if not line.lstrip().startswith("#") and not re.match(r"^\s*[-*]\s*\[ \]\s*$", line)]
    return " ".join(" ".join(lines).split())


def is_bot(login: str) -> bool:
    return login.endswith("[bot]") or login.lower() in {"dependabot", "github-actions"}


def candidate(repo: dict, pr: dict) -> dict | None:
    """기준을 넘으면 측정 입력을 돌려준다. 넘지 못하면 None."""
    author = pr["user"]["login"]
    detail = gh(f"repos/{ORG}/{repo['name']}/pulls/{pr['number']}")
    if detail["additions"] + detail["deletions"] > MAX_CHANGED_LINES:
        return None
    files = gh_pages(f"repos/{ORG}/{repo['name']}/pulls/{pr['number']}/files")
    if not files or unusable([item["filename"] for item in files]):
        return None
    commits = gh_pages(f"repos/{ORG}/{repo['name']}/pulls/{pr['number']}/commits")
    own = [c for c in commits
           if (c.get("author") or {}).get("login") == author and len(c.get("parents", [])) == 1]
    if not 1 <= len(own) <= MAX_OWN_COMMITS:
        return None
    return {
        "repo": repo["name"],
        "github_repo_id": repo["id"],
        "default_branch": repo["default_branch"],
        "number": pr["number"],
        "title": pr["title"],
        "author": author,
        "body": pr["body"] or "",
        "additions": detail["additions"],
        "deletions": detail["deletions"],
        "commits": [{"sha": c["sha"], "message": c["commit"]["message"]} for c in own],
    }


def listed(repo: dict) -> list[dict]:
    """목록만으로 거를 수 있는 기준(머지·본문·봇·develop→main)을 먼저 적용한다."""
    prs = gh_pages(f"repos/{ORG}/{repo['name']}/pulls?state=closed")
    return [
        pr for pr in prs
        if pr.get("merged_at")
        and not is_bot(pr["user"]["login"])
        and not (pr["head"]["ref"] == "develop" and pr["base"]["ref"] == "main")
        and len(body_text(pr["body"])) >= MIN_BODY_CHARS
    ]


def org_repos() -> list[dict]:
    return sorted(
        (r for r in gh_pages(f"orgs/{ORG}/repos?type=public") if r["name"].startswith("ktc4-")),
        key=lambda r: r["name"],
    )


def fetch_from_refs(refs_path: Path, out: Path) -> None:
    """저장소에 남긴 (리포, PR 번호) 목록으로 같은 PR 의 원문을 받는다. 기준 검사는 다시 하지 않는다."""
    refs = json.loads(refs_path.read_text(encoding="utf-8"))
    repos = {r["name"]: r for r in org_repos()}
    ours, others = [], []
    for group, target in (("ours", ours), ("others", others)):
        for ref in refs[group]:
            if ref.get("rejected"):
                target.append(dict(ref))
                continue
            pr = gh(f"repos/{ORG}/{ref['repo']}/pulls/{ref['number']}")
            target.append(candidate(repos[ref["repo"]], pr) or {**ref, "rejected": True})
    out.mkdir(parents=True, exist_ok=True)
    (out / "prs.json").write_text(json.dumps({
        "seed": refs["seed"], "ours": ours, "others": others, "first_batch": refs["first_batch"],
    }, ensure_ascii=False, indent=1), encoding="utf-8")
    dropped = [f"{p['repo']}#{p['number']}" for p in ours + others if p.get("rejected") and not p.get("title")]
    print(f"원문 받음: 우리 {len(ours)} · 다른 팀 {len(others)} → {out / 'prs.json'} (기준을 못 넘어 빠진 PR {dropped})")


def select_new(out: Path) -> None:
    rng = random.Random(SEED)
    repos = org_repos()
    ours_repo = next(r for r in repos if r["name"] == OURS)
    ours = []
    for number in OUR_PRS:
        pr = gh(f"repos/{ORG}/{OURS}/pulls/{number}")
        ours.append(candidate(ours_repo, pr) or {"repo": OURS, "number": number, "rejected": True})

    pool: list[dict] = []
    for repo in repos:
        if repo["name"] == OURS:
            continue
        prs = sorted(listed(repo), key=lambda pr: pr["number"])
        rng.shuffle(prs)
        kept = []
        for pr in prs:
            if len(kept) == PER_REPO:
                break
            picked = candidate(repo, pr)
            if picked:
                kept.append(picked)
        print(f"{repo['name']}: 목록 통과 {len(prs)} · 고름 {len(kept)}", file=sys.stderr)
        pool.extend(kept)

    rng.shuffle(pool)
    usable_ours = [p for p in ours if not p.get("rejected")]
    others = pool[:TOTAL - len(usable_ours)]
    first = [f"{OURS}#{p['number']}" for p in usable_ours]
    first += [f"{p['repo']}#{p['number']}" for p in others[:FIRST_BATCH - len(usable_ours)]]
    out.mkdir(parents=True, exist_ok=True)
    (out / "prs.json").write_text(json.dumps({
        "seed": SEED,
        "criteria": __doc__,
        "ours": ours,
        "others": others,
        "first_batch": first,
    }, ensure_ascii=False, indent=1), encoding="utf-8")
    rejected = [p["number"] for p in ours if p.get("rejected")]
    print(f"ours {len(usable_ours)} (out: {rejected}) / others pool {len(pool)} -> {len(others)} / first {len(first)}",
          file=sys.stderr)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--output", type=Path, default=DEFAULT_OUT, help="prs.json 을 쓸 폴더")
    parser.add_argument("--from-refs", type=Path, help="prs_refs.json 처럼 (리포, 번호) 만 든 목록. 주면 기준대로 새로 고르지 않는다")
    args = parser.parse_args()
    if args.from_refs:
        fetch_from_refs(args.from_refs, args.output)
    else:
        select_new(args.output)


if __name__ == "__main__":
    main()

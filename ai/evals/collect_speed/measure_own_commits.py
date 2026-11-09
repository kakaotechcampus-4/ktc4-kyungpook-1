"""저장소 첫 수집을 재고, 내 커밋과 그 파일을 JSON 으로 남겨 바꾸기 전후 수집 결과가 같은지 견준다.

같은 인자로 코드 바꾸기 전과 후에 한 번씩 돌려 두 JSON 의 own(커밋마다 파일·줄 수)을 비교한다. 결과 원본에는 커밋 번호와 파일 경로가 들어 있어
저장소에 두지 않고 ai/evals/results/collect_speed/ 에 쓴다. GitHub 토큰은 `gh auth token` 으로 읽는다. LLM 은 부르지 않는다.

    cd ai
    python evals/collect_speed/measure_own_commits.py --login <내 GitHub 아이디> --output before.json
    python evals/collect_speed/measure_own_commits.py --login <내 GitHub 아이디> --output after.json
    python evals/collect_speed/measure_own_commits.py --login x --dry-run    # GitHub 를 부르지 않고 수집 요청만 검증한다
"""

import argparse
import asyncio
import json
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path

_AI_ROOT = Path(__file__).resolve().parents[2]
# `python ai/evals/collect_speed/measure_own_commits.py` 로 돌려도 ai/ 패키지를 import 할 수 있게 한다.
sys.path.insert(0, str(_AI_ROOT))

from schemas.collection import RepositoryCollectionRequest  # noqa: E402
from services.github_collector import GithubCollector  # noqa: E402

RESULTS = _AI_ROOT / "evals" / "results" / "collect_speed"


def build_request(args: argparse.Namespace) -> RepositoryCollectionRequest:
    owner, name = args.repo.split("/")
    return RepositoryCollectionRequest.model_validate({
        "user_repository_id": 1,
        "repository": {
            "github_repo_id": args.repo_id,
            "owner_login": owner,
            "name": name,
            "default_branch": args.branch,
        },
        "actor": {"github_login": args.login, "known_emails": []},
        "branches": [],
    })


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="저장소 첫 수집 결과 남기기. 쓰는 법은 파일 맨 위 설명에 있다.")
    parser.add_argument("--login", required=True, help="수집에서 '내 커밋'으로 볼 GitHub 아이디")
    parser.add_argument("--output", type=Path, default=RESULTS / "own-commits.json", help="결과 JSON 경로")
    parser.add_argument("--repo", default="kakaotechcampus-4/ktc4-kyungpook-1", help="owner/name")
    parser.add_argument("--repo-id", type=int, default=1340154160, help="그 저장소의 GitHub 숫자 번호")
    parser.add_argument("--branch", default="develop")
    parser.add_argument("--dry-run", action="store_true", help="GitHub 를 부르지 않고 수집 요청만 검증한다")
    args = parser.parse_args()

    request = build_request(args)
    if args.dry_run:
        print(f"수집 요청 검증 끝: {args.repo} · 브랜치 {args.branch} · 결과는 {args.output}")
        return

    token = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True).stdout.strip()
    started = time.perf_counter()
    execution = asyncio.run(GithubCollector().collect(request, token))
    elapsed = time.perf_counter() - started
    result = execution.result
    own = {
        c.sha: {"files": sorted((f.path, f.additions, f.deletions) for f in c.files), "additions": c.additions,
                "deletions": c.deletions, "pr": c.pull_request_number, "issues": c.issue_numbers}
        for c in result.commits
        if c.exclusion_reason is None
    }
    reasons = Counter(str(c.exclusion_reason) for c in result.commits)
    no_login = sum(1 for c in result.commits if c.author_login is None)
    summary = {
        "seconds": round(elapsed, 1), "api_calls": execution.api_calls, "commits_returned": len(result.commits),
        "reasons": dict(reasons), "no_login": no_login, "own": len(own),
        "confirmations": len(result.needs_confirmation), "pull_requests": len(result.pull_requests),
        "issues": len(result.issues), "partial": str(result.partial_reason),
    }
    print(json.dumps(summary, ensure_ascii=False))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as out:
        json.dump({"summary": summary, "own": own, "confirmations": sorted(x.sha for x in result.needs_confirmation)},
                  out, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()

"""카드 초안 측정 세트(PR 50개)의 diff 근거로 첫 초안만 만들어 기술 이름표를 남긴다.

고쳐 쓰기를 켜지 않아(StarGenerator 기본값) 첫 초안 호출 한 번만 부른다. 이름표는 첫 초안에서 정해진다.
표본과 diff 근거는 star_repair 의 select_prs.py --from-refs 와 measure.py drafts 가 results/star_repair/ 에 만든 것을 읽는다.
결과 이름표는 results/card_tags/<이름>.jsonl 에 쓰고, compare.py 로 gold/ 정답과 견준다.

    cd ai
    python evals/card_tags/run.py --dry-run
    python evals/card_tags/run.py --name baseline
    python evals/card_tags/compare.py evals/results/card_tags/baseline.jsonl evals/card_tags/gold evals/card_tags/adjudicated.json
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from pathlib import Path

_AI_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_AI_ROOT))

from evals.common import llm_harness  # noqa: E402
from schemas.analysis import StarAnalysisRequest  # noqa: E402

SET = _AI_ROOT / "evals" / "results" / "star_repair"
DEFAULT_OUT = _AI_ROOT / "evals" / "results" / "card_tags"


def selected() -> list[dict]:
    if not (SET / "prs.json").exists():
        sys.exit(f"{SET / 'prs.json'} 가 없습니다. 먼저 star_repair/select_prs.py --from-refs 로 표본 원문을 받으세요")
    data = json.loads((SET / "prs.json").read_text(encoding="utf-8"))
    every = [p for p in data["ours"] if not p.get("rejected")] + data["others"]
    first = set(data["first_batch"])
    return [p for p in every if f"{p['repo']}#{p['number']}" in first]


def request_of(pr: dict) -> StarAnalysisRequest | None:
    key = f"{pr['repo']}__{pr['number']}"
    diff = SET / "diff" / f"{key}.json"
    if not diff.exists():
        return None
    return StarAnalysisRequest.model_validate({
        "candidate_id": key, "target_login": pr["author"], "title": pr["title"], "source_type": "PR",
        "source_ref": f"PR #{pr['number']}", "pull_request_body": pr["body"],
        "evidence": json.loads(diff.read_text(encoding="utf-8"))["evidence"], "confirmed_answers": [],
    })


async def one(pr: dict, client) -> dict:
    from services.star_generator import StarGenerator
    key = f"{pr['repo']}__{pr['number']}"
    request = request_of(pr)
    if request is None:
        return {"key": key, "error": "NO_DIFF"}
    try:
        response = await StarGenerator(client).generate(request)
    except Exception as e:
        return {"key": key, "error": type(e).__name__}
    return {"key": key, "areas": response.areas, "techs": response.techs}


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--name", default="run", help="결과 파일 이름(<이름>.jsonl)")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    prs = selected()
    if args.dry_run:
        ready = sum(request_of(pr) is not None for pr in prs)
        print(f"[dry-run] 1차 표본 PR {len(prs)}개 · diff 근거가 있어 요청을 만들 수 있는 PR {ready}")
        return
    llm_harness.install()
    from services.llm_settings import EnvironmentLLMClient
    args.output.mkdir(parents=True, exist_ok=True)
    out = args.output / f"{args.name}.jsonl"
    client = EnvironmentLLMClient("STAR")
    rows = []
    for i in range(0, len(prs), 4):
        rows += await asyncio.gather(*(one(pr, client) for pr in prs[i:i + 4]))
    out.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n", encoding="utf-8")
    print(f"초안 {sum('techs' in r for r in rows)} · 실패 {sum('error' in r for r in rows)}")


if __name__ == "__main__":
    asyncio.run(main())

"""STAR 초안을 실제 모델로 한 번 만들며, 칸 글·단계가 언제 어떤 모양으로 흘러나오는지 찍는다.

직접 지은 결제 중복 승인 사례 하나(커밋 2개)로 운영 생성기를 부르고, 진행 이벤트마다 걸린 시간과 내용을 보여 준다.
결과는 화면에만 찍고 파일로 남기지 않는다.

    cd ai
    python evals/star_live/run.py --dry-run   # LLM 없이 요청 검증만
    python evals/star_live/run.py
"""

from __future__ import annotations

import argparse
import asyncio
import sys
import time
from pathlib import Path

_AI_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_AI_ROOT))

from evals.common import llm_harness  # noqa: E402
from schemas.analysis import StarAnalysisRequest  # noqa: E402

SHA_A = "3f1c9a2b7d4e5f60718293a4b5c6d7e8f9012345"
SHA_B = "9a8b7c6d5e4f30211234567890abcdef12345678"
REQUEST = {
    "candidate_id": "live-check",
    "target_login": "octo",
    "title": "결제 승인 중복 요청 막기",
    "source_type": "PR",
    "source_ref": "PR #31",
    "evidence": [
        {
            "sha": SHA_A,
            "message": "fix: 결제 승인 API 에 멱등 키 검사 추가",
            "author_login": "octo",
            "churn": "+64/-8",
            "summary": "결제 승인 요청에 Idempotency-Key 헤더를 받아, 같은 키로 이미 승인된 주문이면 PG 를 다시 부르지 않고 저장된 승인 결과를 돌려준다. 키와 주문 id 에 유니크 제약을 두었다.",
            "summary_source": "DIFF",
        },
        {
            "sha": SHA_B,
            "message": "test: 같은 키로 두 번 승인하면 PG 를 한 번만 부르는지 확인",
            "author_login": "octo",
            "churn": "+41/-0",
            "summary": "동시에 같은 키로 승인 요청 두 개를 보내 PG 호출이 한 번뿐이고 두 응답이 같은 승인 번호인지 확인하는 테스트를 더했다.",
            "summary_source": "DIFF",
        },
    ],
    "pull_request_body": "사용자가 결제 버튼을 두 번 누르면 승인이 두 번 나가는 문제가 있어 멱등 키를 도입했습니다.",
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    request = StarAnalysisRequest.model_validate(REQUEST)
    if args.dry_run:
        print(f"[dry-run] 근거 {len(request.evidence)}개 · 요청 검증 통과")
        return

    llm_harness.install()
    from services.analysis_pipeline import AnalysisPipeline
    from services.progress import listening

    events = []
    started = time.monotonic()

    def on_progress(step, done, total, item):
        events.append((time.monotonic() - started, item))

    with listening(on_progress):
        asyncio.run(AnalysisPipeline().star_generator.generate(request))

    for at, item in events:
        if item["kind"] == "SLOT":
            print(f"{at:6.2f}s SLOT r{item['round']} {item['slot']} done={item['done']!s:5} {item['text'][:70]!r}")
        else:
            step = item["step"]
            print(f"{at:6.2f}s STEP r{step['round']} {step.get('slot')} {step['kind']}")
    print(f"총 {time.monotonic() - started:.1f}s, 칸 글 줄 {sum(1 for _, i in events if i['kind'] == 'SLOT')}개, "
          f"단계 줄 {sum(1 for _, i in events if i['kind'] == 'STEP')}개")


if __name__ == "__main__":
    main()

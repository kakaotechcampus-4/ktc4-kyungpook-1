"""공고 조건 추출 결과에서 일반 조건 분포를 센다.

실제 LLM 호출·공고 수집과 분리해, 공개 공고를 추출한 결과 JSON을 입력으로 받는다.
예::

    cd ai
    python -m scripts.count_general_criteria --input results/job-posting-extractions.json
"""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any

from schemas.matching import GeneralConditionType, JobCriterionType, JobPostingDetailExtractionResult


def count_general_criteria(
    postings: list[JobPostingDetailExtractionResult],
) -> dict[str, Any]:
    """필수·우대 일반 조건과 일반 조건을 빼면 필수 조건이 없는 공고 수를 센다."""
    counts: Counter[str] = Counter()
    eligible_postings = 0
    required_without_general = 0

    for posting in postings:
        # 본문이 없는 공고와 unchanged 응답은 이번 추출 분포 대상이 아니다.
        if not posting.criteria:
            continue
        eligible_postings += 1
        required_criteria = [
            criterion
            for criterion in posting.criteria
            if criterion.criterion_type is JobCriterionType.REQUIRED
        ]
        technical_required = [
            criterion for criterion in required_criteria if criterion.general_type is None
        ]
        if required_criteria and not technical_required:
            required_without_general += 1
        for criterion in posting.criteria:
            if (
                criterion.criterion_type in (JobCriterionType.REQUIRED, JobCriterionType.PREFERRED)
                and criterion.general_type is not None
            ):
                counts[criterion.general_type.value] += 1

    total_general = sum(counts.values())
    criterion_total = sum(
        1
        for posting in postings
        for criterion in posting.criteria
        if criterion.criterion_type in (JobCriterionType.REQUIRED, JobCriterionType.PREFERRED)
    )
    return {
        "postings_with_criteria": eligible_postings,
        "general_criteria": {
            kind.value: counts[kind.value] for kind in GeneralConditionType
        },
        "general_criteria_total": total_general,
        "general_criteria_ratio": (
            round(total_general / criterion_total, 4) if criterion_total else 0.0
        ),
        "postings_without_technical_required": required_without_general,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="AI 공고 조건 추출 응답 배열 JSON")
    args = parser.parse_args()
    payload = json.loads(args.input.read_text(encoding="utf-8"))
    if not isinstance(payload, list):
        raise ValueError("입력 JSON은 공고 조건 추출 응답 배열이어야 합니다.")
    postings = [JobPostingDetailExtractionResult.model_validate(item) for item in payload]
    print(json.dumps(count_general_criteria(postings), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

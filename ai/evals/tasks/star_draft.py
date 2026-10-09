"""STAR 초안(StarGenerator) 프롬프트를 모델 비교 실행기에 연결한다.

프롬프트·출력 스키마는 운영 코드(services/star_generator.py)와 같은 것을 쓴다.
"""

from __future__ import annotations

from typing import Any

from evals.tasks.base import EvalTask, dump, invented_metrics
from services.star_generator import SYSTEM_PROMPT, StarDraftOutput, StarField


def build_payload(case: dict[str, Any]) -> dict[str, Any]:
    return {
        "title": case["title"],
        "source_ref": case.get("source_ref"),
        "evidence": case["evidence"],
        "confirmed_answers": case.get("confirmed_answers", []),
    }


def check(case: dict[str, Any], output: StarDraftOutput) -> list[str]:
    violations = []
    known_shas = {item["sha"] for item in case["evidence"]}
    has_answers = bool(case.get("confirmed_answers"))
    source_text = dump(build_payload(case))
    for slot in ("S", "T", "A", "R"):
        field: StarField = getattr(output, slot)
        if unknown := [sha for sha in field.evidence_shas if sha not in known_shas]:
            violations.append(f"{slot}:UNKNOWN_SHA:{','.join(sha[:7] for sha in unknown)}")
        if field.status == "EMPTY":
            if field.text:
                violations.append(f"{slot}:EMPTY_WITH_TEXT")
            if not field.insufficient_reason:
                violations.append(f"{slot}:EMPTY_WITHOUT_REASON")
            continue
        if not field.text:
            violations.append(f"{slot}:{field.status}_WITHOUT_TEXT")
            continue
        if len(field.text) > 200:
            violations.append(f"{slot}:TEXT_TOO_LONG")
        if not field.evidence_shas and not has_answers:
            violations.append(f"{slot}:FILLED_WITHOUT_EVIDENCE")
        if metrics := invented_metrics(field.text, source_text):
            violations.append(f"{slot}:INVENTED_METRIC:{metrics}")
    return violations


TASK = EvalTask(
    name="star_draft",
    system_prompt=SYSTEM_PROMPT,
    output_model=StarDraftOutput,
    default_reasoning_effort="medium",
    max_completion_tokens=4000,
    build_payload=build_payload,
    check=check,
)

"""diff 분석(DiffAnalyzer) 프롬프트를 모델 비교 실행기에 연결한다.

프롬프트·출력 스키마는 운영 코드(services/diff_analyzer.py)와 같은 것을 쓴다.
"""

from __future__ import annotations

from typing import Any

from evals.tasks.base import EvalTask, dump, find_secrets, invented_metrics
from services.diff_analyzer import SYSTEM_PROMPT, DiffSummaryOutput


def build_payload(case: dict[str, Any]) -> dict[str, Any]:
    return {"pr_number": case["pr_number"], "title": case["title"], "commits": case["commits"]}


def check(case: dict[str, Any], output: DiffSummaryOutput) -> list[str]:
    violations = []
    expected = [commit["sha"] for commit in case["commits"]]
    actual = [commit.sha for commit in output.commits]
    if missing := sorted(set(expected) - set(actual)):
        violations.append(f"MISSING_SHA:{','.join(sha[:7] for sha in missing)}")
    if unknown := sorted(set(actual) - set(expected)):
        violations.append(f"UNKNOWN_SHA:{','.join(sha[:7] for sha in unknown)}")
    if len(actual) != len(set(actual)):
        violations.append("DUPLICATE_SHA")
    for commit in output.commits:
        if len(commit.summary) > 200:
            violations.append(f"SUMMARY_TOO_LONG:{commit.sha[:7]}")
        if len(commit.technical_points) > 3:
            violations.append(f"TOO_MANY_POINTS:{commit.sha[:7]}")
    output_text = dump(output)
    if secrets := find_secrets(output_text):
        violations.append(f"SECRET_LEAK:{secrets}")
    if metrics := invented_metrics(output_text, dump(case["commits"])):
        violations.append(f"INVENTED_METRIC:{metrics}")
    return violations


TASK = EvalTask(
    name="diff_summary",
    system_prompt=SYSTEM_PROMPT,
    output_model=DiffSummaryOutput,
    default_reasoning_effort="high",
    # Elice 게이트웨이는 비스트리밍 응답의 출력 한도를 6000토큰으로 제한한다.
    max_completion_tokens=6000,
    build_payload=build_payload,
    check=check,
)


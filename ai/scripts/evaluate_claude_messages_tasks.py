"""Claude 네이티브 Messages API로 STAR·질문 생성 eval을 재현한다.

실제 URL·키는 환경변수에만 둔다. ``claude_messages_endpoints.example.json``을
복사해 로컬 설정 파일을 만든 뒤 아래처럼 실행한다.

    python scripts/evaluate_claude_messages_tasks.py --task star_draft \
      --case-ids PR46 PR11 PR35 PR15 PR12 PR21 PR58 PR50 PR17 PR52 \
      --output evals/results/latest/claude_star_draft.json
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional

import httpx
from pydantic import BaseModel, ValidationError

_AI_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(_AI_ROOT))

from evals.tasks import TASKS  # noqa: E402
from evals.tasks.base import EvalTask  # noqa: E402
from scripts.evaluate_answer_sufficiency_models import _estimated_cost, _load_json  # noqa: E402


@dataclass(frozen=True)
class ClaudeMessagesConfig:
    """네이티브 Messages API 호출에 필요한 공개 설정만 담는다."""

    name: str
    model: str
    messages_url_env: str
    api_key_env: str
    anthropic_version: str = "2023-06-01"
    request_extra: Optional[dict[str, Any]] = None
    input_cost_per_million: Optional[float] = None
    output_cost_per_million: Optional[float] = None


class ClaudeMessagesCallError(RuntimeError):
    """네이티브 Messages API 결과를 구조화 출력으로 쓸 수 없을 때 발생한다."""


def _tool_schema(task: EvalTask) -> dict[str, Any]:
    return {
        "name": "emit_structured_output",
        "description": "응답을 지정된 JSON 스키마에 맞춰 반환한다.",
        "input_schema": task.output_model.model_json_schema(),
    }


def _call(
    client: httpx.Client,
    config: ClaudeMessagesConfig,
    task: EvalTask,
    case: dict[str, Any],
) -> tuple[BaseModel, Optional[int], Optional[int], int]:
    """강제 tool 호출의 tool_use.input만 Pydantic 출력 모델로 검증한다."""

    started_at = time.perf_counter()
    body: dict[str, Any] = {
        "model": config.model,
        "max_tokens": task.max_completion_tokens,
        "system": task.system_prompt,
        "messages": [{"role": "user", "content": json.dumps(task.build_payload(case), ensure_ascii=False)}],
        "tools": [_tool_schema(task)],
        "tool_choice": {"type": "tool", "name": "emit_structured_output"},
    }
    if config.request_extra:
        body.update(config.request_extra)

    try:
        response = client.post(
            os.environ[config.messages_url_env].rstrip("/"),
            headers={
                "x-api-key": os.environ[config.api_key_env],
                "anthropic-version": config.anthropic_version,
                "content-type": "application/json",
            },
            json=body,
        )
        response.raise_for_status()
        payload = response.json()
    except (KeyError, httpx.HTTPError, ValueError) as exc:
        raise ClaudeMessagesCallError("Claude Messages API 호출에 실패했습니다.") from exc

    tool_use = next(
        (item for item in payload.get("content", []) if item.get("type") == "tool_use"),
        None,
    )
    if not isinstance(tool_use, dict) or not isinstance(tool_use.get("input"), dict):
        raise ClaudeMessagesCallError("Claude가 tool_use 구조화 출력을 반환하지 않았습니다.")
    try:
        output = task.output_model.model_validate(tool_use["input"])
    except ValidationError as exc:
        raise ClaudeMessagesCallError("Claude가 스키마에 맞지 않는 출력을 반환했습니다.") from exc

    usage = payload.get("usage") or {}
    return (
        output,
        usage.get("input_tokens"),
        usage.get("output_tokens"),
        round((time.perf_counter() - started_at) * 1000),
    )


def _evaluate(config: ClaudeMessagesConfig, task: EvalTask, cases: list[dict[str, Any]]) -> dict[str, Any]:
    summary = {"model": config.name, "model_id": config.model, "transport": "anthropic_messages"}
    if not os.getenv(config.messages_url_env) or not os.getenv(config.api_key_env):
        return {**summary, "status": "SKIPPED", "reason": "Messages URL 또는 API 키가 없습니다."}

    results: list[dict[str, Any]] = []
    with httpx.Client(timeout=120.0) as client:
        for case in cases:
            base = {"case_id": case["id"]}
            try:
                output, prompt_tokens, completion_tokens, latency_ms = _call(client, config, task, case)
                violations = task.check(case, output)
                results.append({
                    **base,
                    "passed": not violations,
                    "violations": violations,
                    "latency_ms": latency_ms,
                    "prompt_tokens": prompt_tokens,
                    "completion_tokens": completion_tokens,
                    "estimated_cost": _estimated_cost(prompt_tokens, completion_tokens, config),
                    "output": output.model_dump(),
                })
            except ClaudeMessagesCallError as exc:
                results.append({**base, "passed": False, "error": str(exc)})

    completed = [item for item in results if "error" not in item]
    return {
        **summary,
        "status": "COMPLETED",
        "case_count": len(cases),
        "errors": len(results) - len(completed),
        "pass_rate": round(sum(item["passed"] for item in results) / len(results), 4),
        "average_latency_ms": round(statistics.mean(item["latency_ms"] for item in completed)) if completed else None,
        "total_estimated_cost": round(sum(item.get("estimated_cost") or 0 for item in completed), 2),
        "cases": results,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Claude Messages API 작업별 모델 비교")
    parser.add_argument("--task", required=True, choices=sorted(TASKS))
    parser.add_argument("--cases", type=Path, default=None)
    parser.add_argument("--models", type=Path, default=_AI_ROOT / "evals" / "claude_messages_endpoints.json")
    parser.add_argument("--model-names", nargs="*", default=None)
    parser.add_argument("--case-ids", nargs="*", default=None)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    task = TASKS[args.task]
    cases = _load_json(args.cases or _AI_ROOT / "evals" / "fixtures" / "generated" / f"{task.name}.json")
    if args.case_ids:
        wanted = set(args.case_ids)
        cases = [case for case in cases if case["id"] in wanted]
    if not cases:
        parser.error("실행할 사례가 없습니다. --case-ids 또는 --cases를 확인하세요.")
    for case in cases:
        task.build_payload(case)

    configs = [ClaudeMessagesConfig(**item) for item in _load_json(args.models)]
    if args.model_names:
        configs = [config for config in configs if config.name in set(args.model_names)]
    report = {"task": task.name, "case_count": len(cases), "models": [_evaluate(config, task, cases) for config in configs]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for model in report["models"]:
        print(f"{model['model']}: {model['status']} 통과 {model.get('pass_rate', 0):.0%}, 오류 {model.get('errors', 0)}")


if __name__ == "__main__":
    main()

"""diff 분석·STAR 초안·질문 생성 작업을 여러 모델로 같은 입력에 실행해 비교한다.

자동 검사(하드 조건) 통과율·오류·지연·비용을 기록하고, 판정 모델 채점에 쓸
원본 출력을 함께 저장한다. 모델 설정과 키는 B-2 비교기와 같은 파일·환경변수를 쓴다.

    python scripts/build_repo_eval_fixtures.py
    python scripts/evaluate_llm_tasks.py --task diff_summary --limit 3 \\
        --output evals/results/tasks/diff_summary.json
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import threading
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from pathlib import Path
from typing import Any, Optional

_AI_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(_AI_ROOT))

from evals.tasks import TASKS  # noqa: E402
from evals.tasks.base import EvalTask  # noqa: E402
from scripts.evaluate_answer_sufficiency_models import (  # noqa: E402
    ModelConfig,
    _estimated_cost,
    _load_json,
)
from services.llm_structured_client import (  # noqa: E402
    LLMSettings,
    LLMStructuredCallError,
    StructuredLLMClient,
)

_EFFORTS = ("none", "low", "medium", "high")


class Budget:
    """모든 모델 호출의 누적 추정 비용을 세고, 상한을 넘으면 새 호출을 막는다."""

    def __init__(self, limit: Optional[float]) -> None:
        self.limit = limit
        self.spent = 0.0
        self._lock = threading.Lock()

    def exhausted(self) -> bool:
        with self._lock:
            return self.limit is not None and self.spent >= self.limit

    def add(self, cost: Optional[float]) -> None:
        with self._lock:
            self.spent += cost or 0.0


def _violation_type(violation: str) -> str:
    """"S:UNKNOWN_SHA:abc1234"처럼 세부값이 붙은 위반을 유형별로 묶는다."""
    parts = violation.split(":")
    return ":".join(parts[:2]) if parts[0] in {"S", "T", "A", "R"} else parts[0]


def _run_case(
    client: StructuredLLMClient,
    task: EvalTask,
    config: ModelConfig,
    case: dict[str, Any],
    run: int,
    budget: Budget,
) -> dict[str, Any]:
    base = {"case_id": case["id"], "run": run}
    if budget.exhausted():
        return {**base, "passed": False, "error": "BUDGET_EXCEEDED"}
    try:
        result = client.call(
            task.system_prompt, task.build_payload(case), task.output_model, task.max_completion_tokens
        )
    except LLMStructuredCallError as exc:
        return {**base, "passed": False, "error": f"{exc} ({exc.__cause__})" if exc.__cause__ else str(exc)}
    violations = task.check(case, result.output)
    cost = _estimated_cost(result.prompt_tokens, result.completion_tokens, config)
    budget.add(cost)
    return {
        **base,
        "passed": not violations,
        "violations": violations,
        "latency_ms": result.latency_ms,
        "prompt_tokens": result.prompt_tokens,
        "completion_tokens": result.completion_tokens,
        "estimated_cost": cost,
        "output": result.output.model_dump(),
    }


def _evaluate_model(
    task: EvalTask,
    config: ModelConfig,
    cases: list[dict[str, Any]],
    repeats: int,
    workers: int,
    budget: Budget,
) -> dict[str, Any]:
    summary: dict[str, Any] = {
        "model": config.name, "model_id": config.model, "reasoning_effort": config.reasoning_effort,
    }
    base_url = os.getenv(config.base_url_env, "").strip()
    api_key = os.getenv(config.api_key_env, "").strip()
    if not base_url or not api_key:
        return {**summary, "status": "SKIPPED", "reason": "endpoint 또는 API 키가 없습니다."}

    client = StructuredLLMClient(LLMSettings(
        base_url=base_url, api_key=api_key, model=config.model, reasoning_effort=config.reasoning_effort,
    ))
    jobs = [(case, run) for run in range(1, repeats + 1) for case in cases]
    with ThreadPoolExecutor(max_workers=workers) as executor:
        results = list(executor.map(lambda job: _run_case(client, task, config, *job, budget), jobs))

    completed = [item for item in results if "error" not in item]
    violation_types = Counter(
        _violation_type(violation) for item in completed for violation in item["violations"]
    )

    def mean(key: str) -> Optional[float]:
        values = [item[key] for item in completed if item.get(key) is not None]
        return round(statistics.mean(values)) if values else None

    return {
        **summary,
        "status": "COMPLETED",
        "repeats": repeats,
        "total_calls": len(results),
        "errors": len(results) - len(completed),
        "pass_rate": round(sum(item["passed"] for item in results) / len(results), 4),
        "violation_types": dict(violation_types.most_common()),
        "average_latency_ms": mean("latency_ms"),
        "average_prompt_tokens": mean("prompt_tokens"),
        "average_completion_tokens": mean("completion_tokens"),
        "total_estimated_cost": round(sum(item.get("estimated_cost") or 0 for item in completed), 2),
        "cases": results,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="LLM 작업별 모델 비교")
    parser.add_argument("--task", required=True, choices=sorted(TASKS))
    parser.add_argument("--cases", type=Path, default=None, help="기본값: evals/fixtures/generated/<task>.json")
    parser.add_argument("--models", type=Path, default=_AI_ROOT / "evals" / "model_endpoints.json")
    parser.add_argument("--model-names", nargs="*", default=None, help="설정 파일에서 이 이름의 모델만 실행")
    parser.add_argument("--reasoning-effort", choices=_EFFORTS, default=None, help="기본값: 작업별 기본 강도")
    parser.add_argument("--case-ids", nargs="*", default=None)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--workers", type=int, default=1, help="모델 하나 안에서 동시에 보낼 요청 수")
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--budget", type=float, default=None, help="누적 추정 비용 상한(원). 넘으면 새 호출을 멈춘다")
    args = parser.parse_args()

    task = TASKS[args.task]
    cases = _load_json(args.cases or _AI_ROOT / "evals" / "fixtures" / "generated" / f"{task.name}.json")
    if args.case_ids:
        cases = [case for case in cases if case["id"] in set(args.case_ids)]
    if args.limit:
        cases = cases[: args.limit]
    if not cases:
        parser.error("실행할 사례가 없습니다. --case-ids·--limit을 확인하세요.")
    # 비용이 드는 호출 전에 모든 사례가 입력으로 변환되는지 먼저 확인한다.
    for case in cases:
        task.build_payload(case)

    effort = args.reasoning_effort or task.default_reasoning_effort
    configs = [replace(ModelConfig(**item), reasoning_effort=effort) for item in _load_json(args.models)]
    if args.model_names:
        configs = [config for config in configs if config.name in set(args.model_names)]

    budget = Budget(args.budget)
    with ThreadPoolExecutor(max_workers=max(len(configs), 1)) as executor:
        models = list(executor.map(
            lambda config: _evaluate_model(task, config, cases, args.repeats, args.workers, budget), configs
        ))
    report = {
        "task": task.name, "case_count": len(cases), "repeats": args.repeats,
        "estimated_total_cost": round(budget.spent, 2), "models": models,
    }
    serialized = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized + "\n", encoding="utf-8")
    for model in models:
        if model["status"] != "COMPLETED":
            print(f"{model['model']}: {model['status']}")
            continue
        print(
            f"{model['model']} [{model['reasoning_effort']}] 통과 {model['pass_rate']:.0%} "
            f"오류 {model['errors']} 위반 {model['violation_types']} "
            f"지연 {model['average_latency_ms']}ms 입력 {model['average_prompt_tokens']} "
            f"출력 {model['average_completion_tokens']} 비용 ₩{model['total_estimated_cost']}"
        )
    print(f"이번 실행 누적 추정 비용 ₩{budget.spent:.1f}" + (f" / 상한 ₩{args.budget:.0f}" if args.budget else ""))


if __name__ == "__main__":
    main()


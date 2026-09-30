"""동일한 11개 사례로 B-2 답변 충분성 모델을 비교한다.

실행 전 ``ai/evals/model_endpoints.example.json``을 복사해
``ai/evals/model_endpoints.json``을 만든다. 실제 API 키는 JSON이 아닌 환경변수에만 둔다.
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any, Optional

_AI_ROOT = Path(__file__).resolve().parents[1]
# `python scripts/...`로 실행해도 ai/ 패키지를 import할 수 있게 한다.
sys.path.insert(0, str(_AI_ROOT))

from schemas.interview import InterviewAnswerRequest
from services.llm_answer_sufficiency_evaluator import (
    LLMAnswerEvaluationError,
    LLMAnswerEvaluatorSettings,
    OpenAIAnswerSufficiencyEvaluator,
)


@dataclass(frozen=True)
class ModelConfig:
    name: str
    model: str
    base_url_env: str
    api_key_env: str
    reasoning_effort: str = "medium"
    input_cost_per_million: Optional[float] = None
    output_cost_per_million: Optional[float] = None


def _request_from_case(case: dict[str, Any]) -> InterviewAnswerRequest:
    """평가 사례를 B-2가 받는 실제 요청 DTO로 만든다."""
    return InterviewAnswerRequest.model_validate(
        {
            "card_id": 1,
            "source_type": "DIRECT_CARD",
            "candidate": None,
            "current_turn": {
                "target": {"star_slot": case["star_slot"], "statement_seq": 1},
                "question_type": case["question_type"],
                "question_text": case["question_text"],
            },
            "answer_text": case["answer_text"],
            "answer_source": "TYPED",
            "other_missing_slots": [],
            "existing_turn_count": 1,
            "max_turns": 2,
        }
    )


def _load_json(path: Path) -> list[dict[str, Any]]:
    with path.open(encoding="utf-8") as file:
        value = json.load(file)
    if not isinstance(value, list):
        raise ValueError(f"{path}는 JSON 배열이어야 합니다.")
    return value


def _estimated_cost(
    prompt_tokens: Optional[int],
    completion_tokens: Optional[int],
    config: ModelConfig,
) -> Optional[float]:
    if None in (
        prompt_tokens,
        completion_tokens,
        config.input_cost_per_million,
        config.output_cost_per_million,
    ):
        return None
    return (
        prompt_tokens * config.input_cost_per_million
        + completion_tokens * config.output_cost_per_million
    ) / 1_000_000


def _evaluate_model(
    config: ModelConfig,
    cases: list[dict[str, Any]],
    repeats: int = 1,
) -> dict[str, Any]:
    base_url = os.getenv(config.base_url_env, "").strip()
    api_key = os.getenv(config.api_key_env, "").strip()
    if not base_url or not api_key:
        return {"model": config.name, "reasoning_effort": config.reasoning_effort, "status": "SKIPPED", "reason": "endpoint 또는 API 키가 없습니다."}

    evaluator = OpenAIAnswerSufficiencyEvaluator(
        LLMAnswerEvaluatorSettings(
            base_url=base_url,
            api_key=api_key,
            model=config.model,
            reasoning_effort=config.reasoning_effort,
        )
    )
    results = []
    for run in range(1, repeats + 1):
        for case in cases:
            base = {
                "case_id": case["id"], "run": run,
                "difficulty": case.get("difficulty", "easy"),
                "expected_outcome": case["expected_outcome"],
            }
            started_at = time.perf_counter()
            try:
                evaluation = evaluator.evaluate(_request_from_case(case))
                actual = evaluation.decision.outcome
                results.append({
                    **base,
                    "actual_outcome": actual, "correct": actual == case["expected_outcome"],
                    "latency_ms": round((time.perf_counter() - started_at) * 1000),
                    "reason": evaluation.decision.reason,
                    "prompt_tokens": evaluation.prompt_tokens,
                    "completion_tokens": evaluation.completion_tokens,
                    "estimated_cost": _estimated_cost(evaluation.prompt_tokens, evaluation.completion_tokens, config),
                })
            except LLMAnswerEvaluationError as exc:
                results.append({
                    **base, "actual_outcome": None, "correct": False,
                    "latency_ms": round((time.perf_counter() - started_at) * 1000),
                    "error": f"{exc} ({exc.__cause__})" if exc.__cause__ else str(exc),
                })

    completed = [result for result in results if result["actual_outcome"] is not None]
    accuracy_by_difficulty = {}
    for difficulty in sorted({item["difficulty"] for item in results}):
        subset = [item for item in results if item["difficulty"] == difficulty]
        accuracy_by_difficulty[difficulty] = round(sum(item["correct"] for item in subset) / len(subset), 4)
    # 같은 사례를 반복했을 때 판정이 바뀐(또는 오류가 섞인) 사례. 기본 temperature라 비결정적이다.
    unstable_cases = sorted(
        case["id"] for case in cases
        if len({item["actual_outcome"] for item in results if item["case_id"] == case["id"]}) > 1
    )
    return {
        "model": config.name,
        "model_id": config.model,
        "reasoning_effort": config.reasoning_effort,
        "status": "COMPLETED",
        "repeats": repeats,
        "completed_cases": len(completed),
        "total_cases": len(results),
        "accuracy": round(sum(item["correct"] for item in results) / len(results), 4),
        "accuracy_by_difficulty": accuracy_by_difficulty,
        "unstable_cases": unstable_cases,
        "average_latency_ms": round(statistics.mean(item["latency_ms"] for item in completed)) if completed else None,
        "total_prompt_tokens": sum(item.get("prompt_tokens") or 0 for item in completed),
        "total_completion_tokens": sum(item.get("completion_tokens") or 0 for item in completed),
        "total_estimated_cost": round(sum(item.get("estimated_cost") or 0 for item in completed), 2),
        "cases": results,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="B-2 답변 충분성 모델 비교")
    parser.add_argument("--cases", type=Path, default=_AI_ROOT / "evals" / "answer_sufficiency_cases.json")
    parser.add_argument("--models", type=Path, default=_AI_ROOT / "evals" / "model_endpoints.json")
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument(
        "--reasoning-effort",
        choices=["none", "low", "medium", "high"],
        default=None,
        help="설정 파일의 reasoning_effort를 모든 모델에 대해 덮어쓴다.",
    )
    parser.add_argument("--repeats", type=int, default=1, help="판정 안정성을 보기 위해 사례별 반복 횟수")
    args = parser.parse_args()

    cases = _load_json(args.cases)
    # 비용이 드는 호출 전에 모든 사례가 실제 B-2 요청 DTO로 변환되는지 먼저 확인한다.
    for case in cases:
        _request_from_case(case)
    configs = [ModelConfig(**item) for item in _load_json(args.models)]
    if args.reasoning_effort:
        configs = [replace(config, reasoning_effort=args.reasoning_effort) for config in configs]
    invalid = [config.name for config in configs if config.reasoning_effort not in ("none", "low", "medium", "high")]
    if invalid:
        raise ValueError(f"reasoning_effort는 none | low | medium | high 중 하나여야 합니다: {invalid}")
    # 모델끼리는 병렬, 한 모델 안에서는 순차 호출해 모델별 지연 시간이 서로 섞이지 않게 한다.
    with ThreadPoolExecutor(max_workers=max(len(configs), 1)) as executor:
        models = list(executor.map(lambda config: _evaluate_model(config, cases, args.repeats), configs))
    report = {"case_count": len(cases), "repeats": args.repeats, "models": models}
    serialized = json.dumps(report, ensure_ascii=False, indent=2)
    print(serialized)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()


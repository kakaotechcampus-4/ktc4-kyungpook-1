"""evaluate_llm_tasks.py 결과를 판정 모델로 채점한다.

모델 이름을 숨기고 순서를 섞어 채점한 뒤, 모델별 포인트 언급률·근거 없는 주장 수·
질문 기준 통과율을 집계한다. 비용을 줄이기 위해 기본적으로 run 1만 채점한다.

    python scripts/judge_llm_task_outputs.py --results evals/results/tasks/diff_summary.json \\
        --judge "GPT-5.6 Sol" --budget 3000 --output evals/results/tasks/diff_summary.judged.json
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

_AI_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(_AI_ROOT))

from evals.tasks import TASKS  # noqa: E402
from evals.tasks.judge import (  # noqa: E402
    COVERAGE_PROMPT,
    QUESTION_CRITERIA,
    QUESTION_PROMPT,
    CoverageJudgement,
    QuestionJudgement,
    coverage_payload,
    question_payload,
)
from scripts.evaluate_answer_sufficiency_models import ModelConfig, _estimated_cost, _load_json  # noqa: E402
from scripts.evaluate_llm_tasks import Budget  # noqa: E402
from services.llm_structured_client import (  # noqa: E402
    LLMSettings,
    LLMStructuredCallError,
    StructuredLLMClient,
)


def _load_cases(task_name: str, path: Path | None) -> dict[str, dict[str, Any]]:
    cases = _load_json(path or _AI_ROOT / "evals" / "fixtures" / "generated" / f"{task_name}.json")
    return {case["id"]: case for case in cases}


def _judge_one(
    client: StructuredLLMClient,
    config: ModelConfig,
    task_name: str,
    case: dict[str, Any],
    gold: dict[str, Any] | None,
    item: dict[str, Any],
    budget: Budget,
) -> dict[str, Any]:
    if budget.exhausted():
        return {"error": "BUDGET_EXCEEDED"}
    task = TASKS[task_name]
    if task_name == "question_gen":
        prompt, schema = QUESTION_PROMPT, QuestionJudgement
        payload = question_payload(task.build_payload(case), item["output"]["question_text"])
    else:
        prompt, schema = COVERAGE_PROMPT, CoverageJudgement
        payload = coverage_payload(task.build_payload(case), gold["must_mention"], item["output"])
    try:
        result = client.call(prompt, payload, schema, max_completion_tokens=4000)
    except LLMStructuredCallError as exc:
        return {"error": f"{exc} ({exc.__cause__})" if exc.__cause__ else str(exc)}
    cost = _estimated_cost(result.prompt_tokens, result.completion_tokens, config)
    budget.add(cost)
    return {"judgement": result.output.model_dump(), "judge_cost": cost}


def _summarize(task_name: str, judged: list[dict[str, Any]]) -> dict[str, Any]:
    by_model: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for item in judged:
        by_model[item["model"]].append(item)
    summary = {}
    for model, items in by_model.items():
        ok = [item for item in items if "judgement" in item]
        row: dict[str, Any] = {"judged": len(ok), "judge_errors": len(items) - len(ok)}
        if task_name == "question_gen":
            for criterion in QUESTION_CRITERIA:
                row[criterion] = round(sum(item["judgement"][criterion] for item in ok) / len(ok), 3) if ok else None
            row["all_criteria_pass"] = (
                round(sum(all(item["judgement"][c] for c in QUESTION_CRITERIA) for item in ok) / len(ok), 3)
                if ok else None
            )
        else:
            points = [point for item in ok for point in item["judgement"]["points"]]
            row["coverage"] = round(sum(point["covered"] for point in points) / len(points), 3) if points else None
            row["unsupported_claims"] = sum(len(item["judgement"]["unsupported_claims"]) for item in ok)
            row["outputs_with_unsupported"] = sum(bool(item["judgement"]["unsupported_claims"]) for item in ok)
        summary[model] = row
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description="LLM 작업 출력 채점")
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--cases", type=Path, default=None)
    parser.add_argument("--gold", type=Path, default=_AI_ROOT / "evals" / "fixtures" / "task_gold.json")
    parser.add_argument("--models", type=Path, default=_AI_ROOT / "evals" / "model_endpoints.json")
    parser.add_argument("--judge", default="GPT-5.6 Sol")
    parser.add_argument("--judge-effort", default="medium", choices=["none", "low", "medium", "high"])
    parser.add_argument("--runs", type=int, nargs="*", default=[1], help="채점할 반복 회차")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--budget", type=float, default=None)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    report = json.loads(args.results.read_text(encoding="utf-8"))
    task_name = report["task"]
    cases = _load_cases(task_name, args.cases)
    gold = {pr["id"]: pr for pr in json.loads(args.gold.read_text(encoding="utf-8"))["prs"]}

    config = next(ModelConfig(**item) for item in _load_json(args.models) if item["name"] == args.judge)
    client = StructuredLLMClient(LLMSettings(
        base_url=os.environ[config.base_url_env], api_key=os.environ[config.api_key_env],
        model=config.model, reasoning_effort=args.judge_effort,
    ))

    items = []
    for model in report["models"]:
        label = f"{model['model']} [{model['reasoning_effort']}]"
        for item in model.get("cases", []):
            if item["run"] in args.runs and "output" in item:
                if task_name != "question_gen" and item["case_id"] not in gold:
                    continue
                items.append({"model": label, "case_id": item["case_id"], "run": item["run"], "output": item["output"]})
    # 채점 순서에서 모델 정체가 드러나지 않도록 섞는다. 판정 모델에는 출력만 보낸다.
    random.Random(args.seed).shuffle(items)

    budget = Budget(args.budget)
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        results = list(executor.map(
            lambda item: _judge_one(client, config, task_name, cases[item["case_id"]], gold.get(item["case_id"]), item, budget),
            items,
        ))
    judged = [{**item, **result} for item, result in zip(items, results)]
    summary = _summarize(task_name, judged)
    output = {
        "task": task_name, "judge": f"{args.judge} [{args.judge_effort}]",
        "judge_cost": round(budget.spent, 2), "summary": summary, "items": judged,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"채점 비용 ₩{budget.spent:.1f}")


if __name__ == "__main__":
    main()


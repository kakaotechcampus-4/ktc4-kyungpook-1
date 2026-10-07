"""저장소 한 건에서 카드 한 장을 완성하는 LLM 비용을 실측한다.

현재 제품 흐름은 GitHub 원본 수집·후보 압축까지 규칙 기반이고, 사용자가 후보를
확정한 뒤 diff 요약 1회, STAR 초안 1회, 질문 최대 3회, 답변 판정 최대 3회에
LLM을 사용한다. PR 후보와 PR이 없는 COMMIT_CLUSTER 후보를 같은 커밋으로 각각
실행해 PR 메타데이터 유무에 따른 비용도 비교한다.

    cd ai
    set -a; source .env; set +a
    python scripts/build_repo_eval_fixtures.py
    python scripts/measure_repository_model_cost.py \
      --all-models \
      --case-id PR58 \
      --output evals/results/repository-cost.json
"""

from __future__ import annotations

import argparse
import copy
import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

import httpx
from pydantic import BaseModel, ValidationError

_AI_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(_AI_ROOT))

from evals.tasks import TASKS  # noqa: E402
from scripts.evaluate_answer_sufficiency_models import (  # noqa: E402
    ModelConfig,
    _estimated_cost,
    _load_json,
    _request_from_case,
)
from services.llm_answer_sufficiency_evaluator import (  # noqa: E402
    AnswerSufficiencyDecision,
    AnswerSufficiencyEvaluation,
    LLMAnswerEvaluatorSettings,
    OpenAIAnswerSufficiencyEvaluator,
    _SYSTEM_PROMPT as ANSWER_SUFFICIENCY_SYSTEM_PROMPT,
)
from services.llm_structured_client import (  # noqa: E402
    LLMSettings,
    StructuredCallResult,
    StructuredLLMClient,
)

_B2_CASE_IDS = (
    "R_MEASURED_IMPROVEMENT",
    "T_UNCLEAR_RESPONSIBILITY",
    "A_CONCRETE_ACTION_AND_REASON",
)


class NativeClaudeStructuredClient:
    """Claude 계열을 네이티브 Messages API의 강제 tool 호출로 실행한다."""

    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        config: ModelConfig,
        http_client: httpx.Client | None = None,
    ) -> None:
        self._url = base_url.rstrip("/")
        if not self._url.endswith("/messages"):
            self._url += "/messages"
        self._api_key = api_key
        self._config = config
        self._client = http_client or httpx.Client(timeout=120.0)

    def call(
        self,
        system_prompt: str,
        payload: dict[str, Any],
        output_model: type[BaseModel],
        max_completion_tokens: int,
    ) -> StructuredCallResult:
        started_at = time.perf_counter()
        body = {
            "model": self._config.model,
            "max_tokens": max_completion_tokens,
            "system": system_prompt,
            "messages": [
                {
                    "role": "user",
                    "content": json.dumps(payload, ensure_ascii=False),
                }
            ],
            "tools": [
                {
                    "name": "emit_structured_output",
                    "description": "응답을 지정된 JSON 스키마에 맞춰 반환한다.",
                    "input_schema": output_model.model_json_schema(),
                }
            ],
            "tool_choice": {"type": "tool", "name": "emit_structured_output"},
        }
        response = self._client.post(
            self._url,
            headers={
                "Authorization": f"Bearer {self._api_key}",
                "x-api-key": self._api_key,
                "anthropic-version": "2023-06-01",
                "content-type": "application/json",
            },
            json=body,
        )
        try:
            response.raise_for_status()
        except httpx.HTTPStatusError as exc:
            detail = response.text[:500].replace("\n", " ")
            raise RuntimeError(
                f"Claude Messages API HTTP {response.status_code}: {detail}"
            ) from exc
        response_payload = response.json()
        tool_use = next(
            (
                item
                for item in response_payload.get("content", [])
                if item.get("type") == "tool_use"
            ),
            None,
        )
        if not isinstance(tool_use, dict) or not isinstance(
            tool_use.get("input"), dict
        ):
            raise RuntimeError("Claude가 tool_use 구조화 출력을 반환하지 않았습니다.")
        tool_input = tool_use["input"]
        if (
            set(tool_input) == {"arguments"}
            and isinstance(tool_input["arguments"], dict)
        ):
            tool_input = tool_input["arguments"]
        try:
            output = output_model.model_validate(tool_input)
        except ValidationError as exc:
            details = json.dumps(
                exc.errors(include_input=False),
                ensure_ascii=False,
            )
            raise RuntimeError(
                "Claude가 스키마에 맞지 않는 출력을 반환했습니다: " + details
            ) from exc
        usage = response_payload.get("usage") or {}
        prompt_tokens = sum(
            value
            for key in (
                "input_tokens",
                "cache_creation_input_tokens",
                "cache_read_input_tokens",
            )
            if isinstance((value := usage.get(key)), int)
        )
        return StructuredCallResult(
            output=output,
            prompt_tokens=prompt_tokens,
            completion_tokens=usage.get("output_tokens"),
            latency_ms=round((time.perf_counter() - started_at) * 1000),
        )


class StructuredAnswerSufficiencyEvaluator:
    """공통 구조화 클라이언트로 B-2 판정을 실행하는 평가 전용 어댑터."""

    def __init__(self, client: NativeClaudeStructuredClient) -> None:
        self._client = client

    def evaluate(self, request) -> AnswerSufficiencyEvaluation:
        result = self._client.call(
            ANSWER_SUFFICIENCY_SYSTEM_PROMPT,
            {
                "star_slot": request.current_turn.target.star_slot,
                "question_type": request.current_turn.question_type,
                "question_text": request.current_turn.question_text,
                "answer_text": request.answer_text,
            },
            AnswerSufficiencyDecision,
            1_024,
        )
        return AnswerSufficiencyEvaluation(
            decision=result.output,
            prompt_tokens=result.prompt_tokens,
            completion_tokens=result.completion_tokens,
        )


def _case_by_id(cases: list[dict[str, Any]], case_id: str) -> dict[str, Any]:
    try:
        return next(case for case in cases if case["id"] == case_id)
    except StopIteration as exc:
        raise ValueError(f"평가 사례 {case_id!r}를 찾지 못했습니다.") from exc


def _record(
    *,
    task: str,
    prompt_tokens: int | None,
    completion_tokens: int | None,
    latency_ms: int,
    config: ModelConfig,
) -> dict[str, Any]:
    return {
        "task": task,
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion_tokens,
        "latency_ms": latency_ms,
        "estimated_cost": _estimated_cost(
            prompt_tokens,
            completion_tokens,
            config,
        ),
    }


def _run_scenario(
    *,
    name: str,
    diff_case: dict[str, Any],
    star_case: dict[str, Any],
    question_cases: list[dict[str, Any]],
    b2_cases: list[dict[str, Any]],
    client,
    evaluator,
    config: ModelConfig,
) -> dict[str, Any]:
    calls: list[dict[str, Any]] = []

    diff_task = TASKS["diff_summary"]
    diff = client.call(
        diff_task.system_prompt,
        diff_task.build_payload(diff_case),
        diff_task.output_model,
        diff_task.max_completion_tokens,
    )
    calls.append(
        _record(
            task="diff_summary",
            prompt_tokens=diff.prompt_tokens,
            completion_tokens=diff.completion_tokens,
            latency_ms=diff.latency_ms,
            config=config,
        )
    )

    summaries = {item.sha: item.summary for item in diff.output.commits}
    star_input = copy.deepcopy(star_case)
    for evidence in star_input["evidence"]:
        evidence["summary"] = summaries.get(evidence["sha"], evidence["summary"])
    star_task = TASKS["star_draft"]
    star = client.call(
        star_task.system_prompt,
        star_task.build_payload(star_input),
        star_task.output_model,
        star_task.max_completion_tokens,
    )
    calls.append(
        _record(
            task="star_draft",
            prompt_tokens=star.prompt_tokens,
            completion_tokens=star.completion_tokens,
            latency_ms=star.latency_ms,
            config=config,
        )
    )

    question_task = TASKS["question_gen"]
    for question_case in question_cases:
        result = client.call(
            question_task.system_prompt,
            question_task.build_payload(question_case),
            question_task.output_model,
            question_task.max_completion_tokens,
        )
        calls.append(
            _record(
                task="question_gen",
                prompt_tokens=result.prompt_tokens,
                completion_tokens=result.completion_tokens,
                latency_ms=result.latency_ms,
                config=config,
            )
        )

    for b2_case in b2_cases:
        started_at = time.perf_counter()
        result = evaluator.evaluate(_request_from_case(b2_case))
        calls.append(
            _record(
                task="answer_sufficiency",
                prompt_tokens=result.prompt_tokens,
                completion_tokens=result.completion_tokens,
                latency_ms=round((time.perf_counter() - started_at) * 1000),
                config=config,
            )
        )

    return {
        "scenario": name,
        "source_type": diff_case["source_type"],
        "call_count": len(calls),
        "prompt_tokens": sum(item["prompt_tokens"] or 0 for item in calls),
        "completion_tokens": sum(item["completion_tokens"] or 0 for item in calls),
        "estimated_cost": round(
            sum(item["estimated_cost"] or 0 for item in calls),
            2,
        ),
        "calls": calls,
    }


def _measure_model(
    *,
    config: ModelConfig,
    pr_diff: dict[str, Any],
    pr_star: dict[str, Any],
    pr_questions: list[dict[str, Any]],
    cluster_diff: dict[str, Any],
    cluster_star: dict[str, Any],
    cluster_questions: list[dict[str, Any]],
    b2_cases: list[dict[str, Any]],
) -> dict[str, Any]:
    base = {
        "model": config.name,
        "model_id": config.model,
        "reasoning_effort": (
            "provider_default"
            if config.model.startswith("claude-")
            else config.reasoning_effort
        ),
    }
    base_url = os.getenv(config.base_url_env, "").strip()
    api_key = os.getenv(config.api_key_env, "").strip()
    if not base_url or not api_key:
        return {
            **base,
            "status": "SKIPPED",
            "reason": f"{config.base_url_env} 또는 {config.api_key_env}가 없습니다.",
        }

    try:
        if config.model.startswith("claude-"):
            client = NativeClaudeStructuredClient(
                base_url=base_url,
                api_key=api_key,
                config=config,
            )
            evaluator = StructuredAnswerSufficiencyEvaluator(client)
            transport = "anthropic_messages"
        else:
            client = StructuredLLMClient(
                LLMSettings(
                    base_url=base_url,
                    api_key=api_key,
                    model=config.model,
                    reasoning_effort=config.reasoning_effort,  # type: ignore[arg-type]
                )
            )
            evaluator = OpenAIAnswerSufficiencyEvaluator(
                LLMAnswerEvaluatorSettings(
                    base_url=base_url,
                    api_key=api_key,
                    model=config.model,
                    reasoning_effort=config.reasoning_effort,
                )
            )
            transport = "openai_compatible"

        scenarios = [
            _run_scenario(
                name="PR이 있는 저장소에서 후보 1건으로 카드 1장 생성",
                diff_case=pr_diff,
                star_case=pr_star,
                question_cases=pr_questions,
                b2_cases=b2_cases,
                client=client,
                evaluator=evaluator,
                config=config,
            ),
            _run_scenario(
                name="PR이 없는 저장소에서 commit cluster 1건으로 카드 1장 생성",
                diff_case=cluster_diff,
                star_case=cluster_star,
                question_cases=cluster_questions,
                b2_cases=b2_cases,
                client=client,
                evaluator=evaluator,
                config=config,
            ),
        ]
    except (httpx.HTTPError, RuntimeError, ValueError) as exc:
        return {
            **base,
            "status": "FAILED",
            "transport": (
                "anthropic_messages"
                if config.model.startswith("claude-")
                else "openai_compatible"
            ),
            "error": f"{type(exc).__name__}: {exc}",
        }

    return {
        **base,
        "status": "COMPLETED",
        "transport": transport,
        "scenarios": scenarios,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="저장소 1건의 모델 비용 실측")
    parser.add_argument("--model-name", action="append", dest="model_names")
    parser.add_argument(
        "--all-models",
        action="store_true",
        help="설정 파일에 있는 모든 모델을 같은 시나리오로 실행한다.",
    )
    parser.add_argument(
        "--workers",
        type=int,
        default=1,
        help="서로 다른 모델을 동시에 실행할 수(기본 1).",
    )
    parser.add_argument("--case-id", default="PR58")
    parser.add_argument(
        "--models",
        type=Path,
        default=_AI_ROOT / "evals" / "model_endpoints.json",
    )
    parser.add_argument(
        "--fixtures",
        type=Path,
        default=_AI_ROOT / "evals" / "fixtures" / "generated",
    )
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args()

    configs = [ModelConfig(**row) for row in _load_json(args.models)]
    if args.all_models and args.model_names:
        parser.error("--all-models와 --model-name은 함께 쓸 수 없습니다.")
    if args.workers < 1:
        parser.error("--workers는 1 이상이어야 합니다.")
    wanted = None if args.all_models else set(args.model_names or ["GPT-5.6 Luna"])
    selected_configs = [
        config for config in configs if wanted is None or config.name in wanted
    ]
    if wanted is not None and {config.name for config in selected_configs} != wanted:
        missing = sorted(wanted - {config.name for config in selected_configs})
        parser.error("모델 설정에서 찾지 못했습니다: " + ", ".join(missing))

    diff_cases = _load_json(args.fixtures / "diff_summary.json")
    star_cases = _load_json(args.fixtures / "star_draft.json")
    all_question_cases = _load_json(args.fixtures / "question_gen.json")
    all_b2_cases = _load_json(_AI_ROOT / "evals" / "answer_sufficiency_cases.json")

    pr_diff = copy.deepcopy(_case_by_id(diff_cases, args.case_id))
    pr_star = copy.deepcopy(_case_by_id(star_cases, args.case_id))
    pr_questions = [
        copy.deepcopy(case)
        for case in all_question_cases
        if case["id"].startswith(f"{args.case_id}_")
    ][:3]
    if len(pr_questions) != 3:
        parser.error(f"{args.case_id}의 질문 사례 3건을 찾지 못했습니다.")

    cluster_diff = copy.deepcopy(pr_diff)
    cluster_diff.update(
        {
            "id": f"CLUSTER_{args.case_id}",
            "source_type": "COMMIT_CLUSTER",
            "source_ref": None,
            "pr_number": None,
        }
    )
    cluster_star = copy.deepcopy(pr_star)
    cluster_star.update(
        {
            "id": f"CLUSTER_{args.case_id}",
            "source_ref": None,
        }
    )
    cluster_questions = copy.deepcopy(pr_questions)
    for question in cluster_questions:
        question["id"] = f"CLUSTER_{question['id']}"
        evidence = question.get("evidence")
        if evidence:
            evidence["pr_number"] = None

    b2_cases = [_case_by_id(all_b2_cases, case_id) for case_id in _B2_CASE_IDS]
    def measure(config: ModelConfig) -> dict[str, Any]:
        return _measure_model(
            config=config,
            pr_diff=pr_diff,
            pr_star=pr_star,
            pr_questions=pr_questions,
            cluster_diff=cluster_diff,
            cluster_star=cluster_star,
            cluster_questions=cluster_questions,
            b2_cases=b2_cases,
        )

    with ThreadPoolExecutor(max_workers=min(args.workers, len(selected_configs))) as pool:
        model_results = list(pool.map(measure, selected_configs))

    report = {
        "fixture": args.case_id,
        "assumptions": {
            "card_count": 1,
            "question_count": 3,
            "answer_evaluation_count": 3,
            "github_collection_model_calls": 0,
            "note": (
                "COMMIT_CLUSTER는 같은 커밋 입력에서 PR 메타데이터를 제거해 "
                "PR 없는 흐름의 토큰 비용을 비교한 시나리오입니다."
            ),
        },
        "models": model_results,
    }
    serialized = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized + "\n", encoding="utf-8")
        for model in model_results:
            if model["status"] != "COMPLETED":
                print(f"{model['model']}: {model['status']} - {model.get('error') or model.get('reason')}")
                continue
            costs = [scenario["estimated_cost"] for scenario in model["scenarios"]]
            print(f"{model['model']}: PR ₩{costs[0]:.2f}, COMMIT_CLUSTER ₩{costs[1]:.2f}")
    else:
        print(serialized)


if __name__ == "__main__":
    main()

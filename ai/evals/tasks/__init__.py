"""모델 비교에 쓰는 LLM 작업 목록."""

from evals.tasks import diff_summary, question_gen, star_draft
from evals.tasks.base import EvalTask

TASKS: dict[str, EvalTask] = {
    task.name: task
    for task in (diff_summary.TASK, star_draft.TASK, question_gen.TASK)
}


"""자소서 단계(카드 고르기·질문 만들기·쓰기·판단)가 함께 쓰는 LLM 호출.

형식이나 규칙이 틀린 답만 한 번 더 요청하고, 단계 마감 안에 쓸 만한 답을 못 받으면 CoverLetterLLMUnavailable 을 올린다.
그 뒤에 무엇으로 대신할지(코드가 정리·틀 질문)는 각 단계가 정한다.
"""

from __future__ import annotations

import asyncio
import functools
import logging
from concurrent.futures import Executor
from dataclasses import dataclass
from typing import Any, Callable, Optional, Protocol

from services.llm_executors import COVER_LETTER_EXECUTOR, LLMCallSkipped, run_llm_call
from services.llm_settings import LLMConfigError
from services.llm_structured_client import LLMInvalidOutputError, LLMStructuredCallError, StructuredCallResult
from services.secret_redaction import mask_emails, redact_secrets

logger = logging.getLogger(__name__)


class StructuredClient(Protocol):
    def call(
        self,
        system_prompt: str,
        payload: dict[str, Any],
        output_model: type,
        max_completion_tokens: int,
        timeout_seconds: Optional[float] = None,
    ) -> StructuredCallResult: ...


class CoverLetterLLMUnavailable(Exception):
    """마감 안에 규칙에 맞는 답을 받지 못했다."""


@dataclass(frozen=True)
class CoverLetterLimits:
    # Spring 이 단계 Job 하나를 3분까지 두므로 요청·저장 시간을 빼고 그 안에 끝낸다
    pick_deadline_seconds: float = 90.0
    # 문항별 고르기가 마감까지 끌어도 감독이 부를 시간을 남긴다
    supervisor_reserve_seconds: float = 25.0
    ask_deadline_seconds: float = 60.0
    draft_deadline_seconds: float = 150.0
    # 문항별 쓰기가 마감까지 끌어도 전체 검토와 한 번 더 쓰기를 할 시간을 남긴다
    review_reserve_seconds: float = 45.0
    min_call_seconds: float = 5.0
    max_attempts: int = 2
    pick_max_completion_tokens: int = 2_000
    supervisor_max_completion_tokens: int = 1_500
    ask_max_completion_tokens: int = 1_500
    # 추론 토큰도 이 안에서 쓰므로 글자 수 상한(최대 5,000자)보다 넉넉히 둔다
    write_max_completion_tokens: int = 8_000
    decide_max_completion_tokens: int = 1_500
    review_max_completion_tokens: int = 2_500
    max_note_chars: int = 80
    max_question_chars: int = 150
    max_text_chars: int = 500
    max_material_chars: int = 1_000
    # 자소서는 글자 수 상한 가까이 채우므로, 이 비율보다 짧으면 재료를 더 묻고 풀어 쓰거나 모자란 자리를 빈칸으로 알린다
    target_ratio: float = 0.7
    max_considered: int = 3
    max_asks: int = 4
    max_templates: int = 2
    max_template_chars: int = 80
    max_rewrites: int = 2
    max_findings_per_question: int = 3


async def call_until_valid(
    client: StructuredClient,
    prompt: str,
    payload: dict[str, Any],
    output_model: type,
    max_tokens: int,
    problem_of: Callable[[Any], Optional[str]],
    deadline: float,
    limits: CoverLetterLimits,
    what: str,
    executor: Executor = COVER_LETTER_EXECUTOR,
    on_partial: Optional[Callable[[dict[str, Any]], None]] = None,
) -> Any:
    """``deadline``(이벤트 루프 시각)까지 규칙에 맞는 답을 받아 돌려준다. on_partial 은 모델이 쓰는 도중의 출력을 받는다."""
    loop = asyncio.get_running_loop()
    call = client.call if on_partial is None else functools.partial(client.call, on_partial=on_partial)
    for attempt in range(1, limits.max_attempts + 1):
        timeout = deadline - loop.time()
        if timeout < limits.min_call_seconds:
            break
        try:
            result = await asyncio.wait_for(
                run_llm_call(executor, call, prompt, payload, output_model, max_tokens,
                             deadline=deadline, min_seconds=limits.min_call_seconds),
                timeout=timeout + 1,
            )
        except (asyncio.TimeoutError, LLMCallSkipped):
            break
        except LLMInvalidOutputError:
            continue
        except LLMStructuredCallError as exc:
            # 구조화 출력 검증 실패 문구에는 모델 출력 조각이 실릴 수 있어 예외 종류만 남긴다
            cause = type(exc.__cause__).__name__ if exc.__cause__ is not None else "-"
            logger.warning("자소서 %s LLM 호출 실패: exc=%s cause=%s", what, type(exc).__name__, cause)
            break
        except LLMConfigError:
            logger.warning("자소서 %s LLM 설정이 없어 부르지 않았다", what)
            break
        problem = problem_of(result.output)
        if problem is None:
            return result.output
        logger.warning("자소서 %s 답이 규칙에 맞지 않음(%d번째): %s", what, attempt, problem)
    raise CoverLetterLLMUnavailable


def clip(text: Optional[str], limit: int) -> Optional[str]:
    """사용자 글을 LLM 에 보내기 전에 비밀값·이메일을 가리고 길이를 줄인다."""
    if text is None or not text.strip():
        return None
    return mask_emails(redact_secrets(text.strip()))[:limit]

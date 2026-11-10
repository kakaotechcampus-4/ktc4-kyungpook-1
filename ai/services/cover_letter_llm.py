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
    # 지원 이유 판정은 질문 만들기 앞에 한 번 도는 짧은 분류라, 끌어도 질문 만들 시간을 남긴다
    memo_check_deadline_seconds: float = 15.0
    memo_check_max_completion_tokens: int = 1_000
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
    readiness_max_completion_tokens: int = 1_500
    review_max_completion_tokens: int = 2_500
    max_note_chars: int = 80
    max_question_chars: int = 150
    max_text_chars: int = 500
    max_material_chars: int = 1_000
    # 재료가 이 비율보다 짧으면 질문을 더 한다. 초안은 이 길이를 채우려고 늘려 쓰지 않고 재료만큼만 쓴다
    target_ratio: float = 0.7
    max_considered: int = 3
    max_asks: int = 4
    max_rewrites: int = 2
    max_findings_per_question: int = 3
    # 재료에 없는 해석을 지어 쓴 문장은 사용자가 하나씩 확인해야 해서 문항마다 이만큼만 둔다
    max_inferred: int = 2
    # 첨삭은 사용자가 버튼을 누르고 기다리는 동기 요청이라 초안보다 짧게 끊고, 진단이 끌어도 고쳐 쓸 시간을 남긴다
    polish_deadline_seconds: float = 60.0
    polish_rewrite_reserve_seconds: float = 35.0
    # 고쳐 쓰기 바퀴가 끌어도 감독 검토와 한 번 더 고쳐 쓰기를 할 시간을 남긴다
    polish_review_reserve_seconds: float = 15.0
    diagnose_max_completion_tokens: int = 2_500
    max_weak_points: int = 4
    max_good_points: int = 3
    max_point_title_chars: int = 30
    max_point_detail_chars: int = 60
    max_summary_chars: int = 150
    max_other_text_chars: int = 2_000
    # 채팅 한 턴은 사용자가 보내고 기다리는 말이라 짧게 끊는다
    turn_deadline_seconds: float = 20.0
    turn_max_completion_tokens: int = 1_500
    # 문항 하나에 질문을 이만큼 했으면 더 묻지 않고 끝내고, 같은 항목 꼬리 질문은 이만큼 뒤 받은 답을 그대로 받는다
    max_turn_questions: int = 4
    max_follow_ups: int = 2
    max_turn_lines: int = 3
    max_ack_chars: int = 80
    max_example_chars: int = 60
    max_answer_chars: int = 300
    max_thread_turns: int = 30
    # 답으로 문항을 다시 쓰는 것은 쓰기 한 번과 검사·감독 한 번이라 첨삭처럼 끊고, 감독 검토 시간을 남긴다
    fill_deadline_seconds: float = 60.0
    fill_review_reserve_seconds: float = 15.0
    # 빈칸 자리를 쓰면서 이어지는 앞 문장 하나는 다듬어도 되고, 그보다 많이 바꾸면 앞 글로 되돌린다
    max_fill_changed_sentences: int = 1
    # 채팅을 여는 순간 화면에 같이 뜨는 제안이라 채팅 턴처럼 짧게 끊는다
    suggest_deadline_seconds: float = 20.0
    suggest_max_completion_tokens: int = 1_500
    max_suggestions: int = 3
    max_angle_chars: int = 60
    # 다시 쓴 글에 새로 생긴 문제(새 답의 기술 이름이 상한을 넘는 등)를 고쳐 쓸 기회
    fill_fix_rounds: int = 1


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

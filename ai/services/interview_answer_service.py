"""B-2 인터뷰 답변 반영과 다음 질문 판단 서비스.

Spring이 전달한 사용자 답변을 평가해 STAR 문장 생성 여부, 남은 슬롯,
다음 질문 또는 종료를 결정한다. 저장과 인터뷰 턴 순번 부여는 Spring의
책임이며 이 서비스는 DB에 접근하지 않는다.
"""

from __future__ import annotations

from typing import Protocol

from schemas.interview import (
    InterviewAnswerRequest,
    InterviewAnswerResult,
    InterviewTurnRequest,
    MAX_INTERVIEW_TURNS,
    NextInterviewTurn,
    ResultingStatement,
    StatementEvidenceType,
    StatementTarget,
)
from services.interview_agent import InterviewAgent

_ESCAPE_HATCH = "기억나지 않거나 단순 정리였다면 넘어가도 괜찮아요."
_MIN_TYPED_ANSWER_CHARS = 10
_VAGUE_ANSWER_MARKERS = (
    "모르겠",
    "기억 안",
    "기억이 안",
    "잘 모르",
    "없어요",
    "없습니다",
    "그냥",
)


class AnswerSufficiencyEvaluator(Protocol):
    """사용자 답변이 STAR 문장으로 사용할 만큼 충분한지 판단하는 경계."""

    def is_sufficient(self, request: InterviewAnswerRequest) -> bool:
        """답변이 충분하면 True를 반환한다."""


class RuleBasedAnswerSufficiencyEvaluator:
    """LLM 연결 전 사용하는 보수적인 MVP 답변 충분성 판정기.

    AI 선택지는 미리 생성·검토된 구체적인 답변으로 보고 충분하다고 판단한다.
    직접 입력 답변은 회피성 표현이 아니고 의미 있는 문자가 일정 수 이상일 때만
    충분하다고 판단한다. 나중에 이 클래스만 LLM 판정기로 교체할 수 있다.
    """

    def is_sufficient(self, request: InterviewAnswerRequest) -> bool:
        """입력 방식과 최소 정보량을 기준으로 답변 충분성을 판정한다."""
        if request.answer_source == "SELECTED":
            return True

        normalized = request.answer_text.casefold()
        if any(marker in normalized for marker in _VAGUE_ANSWER_MARKERS):
            return False

        meaningful_chars = sum(char.isalnum() for char in request.answer_text)
        return meaningful_chars >= _MIN_TYPED_ANSWER_CHARS


class InterviewAnswerService:
    """사용자 답변을 반영하고 B-2의 다음 상태를 결정한다."""

    def __init__(
        self,
        evaluator: AnswerSufficiencyEvaluator | None = None,
        question_agent: InterviewAgent | None = None,
    ) -> None:
        self._evaluator = evaluator or RuleBasedAnswerSufficiencyEvaluator()
        self._question_agent = question_agent or InterviewAgent()

    def process_answer(
        self,
        turn_id: int,
        request: InterviewAnswerRequest,
    ) -> InterviewAnswerResult:
        """답변 충분성, 남은 슬롯, 질문 상한을 조합해 B-2 결과를 만든다."""
        is_sufficient = self._evaluator.is_sufficient(request)
        current_target = request.current_turn.target
        other_targets = [
            StatementTarget(
                star_slot=slot.star_slot,
                statement_seq=slot.statement_seq,
            )
            for slot in request.other_missing_slots
        ]

        if is_sufficient:
            outcome = "ANSWERED"
            resulting_statement = ResultingStatement(
                star_slot=current_target.star_slot,
                statement_seq=current_target.statement_seq,
                body=request.answer_text,
                evidence_type=self._evidence_type(request),
                source_turn_id=turn_id,
            )
            remaining_slots = other_targets
        else:
            outcome = "INSUFFICIENT"
            resulting_statement = None
            remaining_slots = [current_target, *other_targets]

        can_ask_again = (
            bool(remaining_slots)
            and request.existing_turn_count < MAX_INTERVIEW_TURNS
        )
        if not can_ask_again:
            return InterviewAnswerResult(
                turn_id=turn_id,
                outcome=outcome,
                resulting_statement=resulting_statement,
                remaining_slots=remaining_slots,
                next_action="COMPLETE",
                next_turn=None,
            )

        if is_sufficient:
            next_turn = self._build_next_missing_slot_question(request)
        else:
            next_turn = self._build_followup_question(request)

        return InterviewAnswerResult(
            turn_id=turn_id,
            outcome=outcome,
            resulting_statement=resulting_statement,
            remaining_slots=remaining_slots,
            next_action="ASK_AGAIN",
            next_turn=next_turn,
        )

    @staticmethod
    def _evidence_type(request: InterviewAnswerRequest) -> StatementEvidenceType:
        """사용자 입력 방식을 DB의 STAR 문장 근거 유형으로 변환한다."""
        if request.answer_source == "SELECTED":
            return "USER_SELECTED"
        return "USER_STATED"

    def _build_next_missing_slot_question(
        self,
        request: InterviewAnswerRequest,
    ) -> NextInterviewTurn:
        """현재 답변이 충분하면 다른 부족한 슬롯의 근거 기반 질문을 만든다."""
        turn_result = self._question_agent.build_turn(
            InterviewTurnRequest(
                card_id=request.card_id,
                source_type=request.source_type,
                candidate=request.candidate,
                missing_slots=request.other_missing_slots,
                existing_turn_count=request.existing_turn_count,
            )
        )
        if (
            turn_result.next_action != "ASK_AGAIN"
            or turn_result.target_star_slot is None
            or turn_result.target_statement_seq is None
            or turn_result.question_type is None
            or turn_result.question_text is None
        ):
            raise ValueError("남은 슬롯이 있지만 다음 질문을 생성하지 못했습니다.")

        return NextInterviewTurn(
            target=StatementTarget(
                star_slot=turn_result.target_star_slot,
                statement_seq=turn_result.target_statement_seq,
            ),
            question_type=turn_result.question_type,
            trigger_source="AUTO",
            question_text=turn_result.question_text,
        )

    @staticmethod
    def _build_followup_question(
        request: InterviewAnswerRequest,
    ) -> NextInterviewTurn:
        """현재 답변이 부족하면 같은 슬롯을 한 번 더 구체적으로 묻는다."""
        target = request.current_turn.target
        evidence_context = InterviewAnswerService._evidence_context(request)
        question_text = (
            f"{evidence_context}{target.star_slot} 부분을 채우기에는 답변의 구체적인 "
            "상황이나 결과가 조금 부족해요. 어떤 변화나 판단 기준이 있었는지 "
            f"조금 더 설명해주실 수 있나요? {_ESCAPE_HATCH}"
        )
        return NextInterviewTurn(
            target=target,
            question_type="FOLLOWUP",
            trigger_source="AUTO",
            question_text=question_text,
        )

    @staticmethod
    def _evidence_context(request: InterviewAnswerRequest) -> str:
        """후속 질문에 표시할 PR 또는 커밋 식별자 하나를 선택한다."""
        candidate = request.candidate
        if candidate is None:
            return ""
        if candidate.github_pr_number is not None:
            return f"PR #{candidate.github_pr_number}와 관련해 "
        if candidate.commits:
            return f"커밋 {candidate.commits[0].sha}와 관련해 "
        return ""

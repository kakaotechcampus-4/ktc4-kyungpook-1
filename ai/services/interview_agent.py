"""되묻기(interview-turns) 질문 생성 Agent — B-1.

``next_turn`` 은 고를 수 있는 칸 안에서 LLM 이 다음 칸·질문·예상 답변을 정하고(``services.interview_llm``),
LLM 이 없거나 실패하면 아래 결정적인 틀 질문(``build_turn``)으로 대신한다.
틀 질문은 Spring이 전달한 카드·STAR 문장·커밋·리뷰·이슈 문맥으로 내부 EvidenceHint를 만들어 쓴다.

질문 생성 하드 규칙(명세서 gitory_api_spec_v2 346행):
    - "실패" 등 부정적 결과를 단정하지 않는다.
    - 탈출구("기억나지 않거나 단순 정리였다면 넘어가도 괜찮아요")를 반드시 포함한다.
    - 제공된 sha 또는 pr_number는 반드시 질문 본문에 인용한다.
    - existing_turn_count >= max_turns면 질문을 생성하지 않고
      next_action="COMPLETE".
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal, Optional

from schemas.interview import (
    CandidateContext,
    CommitContext,
    IssueContext,
    InterviewTurnRequest,
    InterviewTurnResult,
    MissingSlot,
    QuestionType,
    ReviewContext,
    StarSlot,
)
from services.interview_llm import EndedQuestions, InterviewLLM, InterviewLLMUnavailable, has_metric

#: 모든 질문에 반드시 들어가는 탈출구 문구.
_ESCAPE_HATCH = "기억나지 않거나 단순 정리였다면 넘어가도 괜찮아요."

InternalEvidenceKind = Literal[
    "REVERT",
    "LINKED_COMMIT",
    "CHANGES_REQUESTED",
    "ISSUE_FEEDBACK",
    "NO_EVIDENCE",
]


@dataclass(frozen=True)
class EvidenceHint:
    """B가 카드 상태와 전달된 근거 문맥에서 만드는 내부 질문 판단 모델.

    Spring은 이 모델을 만들거나 전달하지 않는다. Spring이 리뷰·이슈 수집 전에는
    빈 배열을 보내면 기존 커밋 중심 판단만 그대로 동작한다.
    """

    kind: InternalEvidenceKind
    gap_reason: str
    commit: CommitContext | None = None
    review: ReviewContext | None = None
    issue: IssueContext | None = None


class InterviewAgent:
    """B-1 되묻기 질문을 생성하는 Agent."""

    def __init__(self, llm: Optional[InterviewLLM] = None) -> None:
        self._llm = llm

    async def next_turn(self, request: InterviewTurnRequest) -> tuple[InterviewTurnResult, bool]:
        """고를 수 있는 칸 중 다음에 물을 칸과 질문을 정하고, 틀 질문으로 대신했는지 함께 돌려준다.

        상한·고를 수 있는 칸은 코드가 정하고, 그 안에서 어디를 어떻게 물을지는 LLM 이 정한다.
        LLM 이 없거나 마감 안에 쓸 만한 답을 못 주면 틀 질문으로 대신해 질문 요청이 실패하지 않게 한다.
        """
        allowed = request.choosable_slots()
        if request.existing_turn_count >= request.max_turns or not allowed:
            return InterviewTurnResult(card_id=request.card_id, next_action="COMPLETE"), False
        fixed = request.target_star_slot or self._first_slot(request, allowed)
        if self._llm is None:
            return self._template_turn(request, fixed or allowed[0]), True
        try:
            written = await self._llm.write_question(request, fixed)
        except InterviewLLMUnavailable:
            return self._template_turn(request, fixed or allowed[0]), True
        if isinstance(written, EndedQuestions):
            return InterviewTurnResult(card_id=request.card_id, next_action="COMPLETE", reason=written.reason,
                                       themes=list(written.themes)), False
        slot = self._missing_slot(request, written.star_slot)
        return InterviewTurnResult(
            card_id=request.card_id,
            target_star_slot=slot.star_slot,
            target_statement_seq=slot.statement_seq,
            question_type=self._question_type(request, slot),
            trigger_source="AUTO",
            question_text=written.question,
            next_action="ASK_AGAIN",
            answer_mode=written.answer_mode,
            suggested_answers=written.suggested_answers,
            # 화면이 정한 칸은 사용자가 고른 것이라 판단 이유를 보이지 않는다
            reason=written.reason if request.target_star_slot is None else None,
        ), False

    @staticmethod
    def _first_slot(request: InterviewTurnRequest, allowed: list[StarSlot]) -> Optional[StarSlot]:
        """첫 질문을 정하고, 그 밖은 LLM 이 고르게 둔다.

        경험 카드는 사용자 답이 곧 이야기라 S 가 비어 있으면 S 부터, 기술 카드는 R 에 확인된 수치가 없으면 R 부터 묻는다.
        """
        if request.previous_turns:
            return None
        if request.card_kind == "QUALITATIVE":
            situation = InterviewAgent._missing_slot(request, "S") if "S" in allowed else None
            return "S" if situation is not None and not situation.body else None
        if "R" not in allowed:
            return None
        result = InterviewAgent._missing_slot(request, "R")
        return None if has_metric(result.body) else "R"

    def _template_turn(self, request: InterviewTurnRequest, slot_name: StarSlot) -> InterviewTurnResult:
        slot = self._missing_slot(request, slot_name)
        turn = self.build_turn(request.model_copy(update={"missing_slots": [slot]}))
        measure = slot.star_slot == "R" and not has_metric(slot.body)
        return turn.model_copy(update={"answer_mode": "MEASURE" if measure else "TEXT"})

    @staticmethod
    def _missing_slot(request: InterviewTurnRequest, slot_name: StarSlot) -> MissingSlot:
        return next(slot for slot in request.missing_slots if slot.star_slot == slot_name)

    @staticmethod
    def _question_type(request: InterviewTurnRequest, slot: MissingSlot) -> QuestionType:
        if any(turn.star_slot == slot.star_slot for turn in request.previous_turns):
            return "FOLLOWUP"
        return "EVIDENCE_GAP" if slot.body or slot.linked_commits else "RECALL_AID"

    def build_turn(self, request: InterviewTurnRequest) -> InterviewTurnResult:
        """요청을 받아 다음 되묻기 턴(또는 종료)을 결정한다.

        Args:
            request: `/internal/interview-turns` 요청 모델.

        Returns:
            새 질문 또는 `next_action="COMPLETE"`만 채워진 결과.
        """
        if (
            request.existing_turn_count >= request.max_turns
            or not request.missing_slots
        ):
            return InterviewTurnResult(card_id=request.card_id, next_action="COMPLETE")

        slot = request.missing_slots[0]
        hint = self._build_evidence_hint(slot, request.candidate)

        if hint.kind == "REVERT":
            question_text = self._ask_for_revert(hint)
            question_type = "EVIDENCE_GAP"
        elif hint.kind == "LINKED_COMMIT":
            question_text = self._ask_for_linked_commit(slot, hint)
            question_type = "EVIDENCE_GAP"
        elif hint.kind == "CHANGES_REQUESTED":
            question_text = self._ask_for_changes_requested(slot, hint)
            question_type = "EVIDENCE_GAP"
        elif hint.kind == "ISSUE_FEEDBACK":
            question_text = self._ask_for_issue_feedback(slot, hint)
            question_type = "EVIDENCE_GAP"
        else:
            question_text = self._ask_fallback(slot, request.candidate)
            question_type = "RECALL_AID"

        return InterviewTurnResult(
            card_id=request.card_id,
            target_star_slot=slot.star_slot,
            target_statement_seq=slot.statement_seq,
            question_type=question_type,
            trigger_source="AUTO",
            question_text=question_text,
            next_action="ASK_AGAIN",
        )

    def _build_evidence_hint(
        self, slot: MissingSlot, candidate: CandidateContext | None
    ) -> EvidenceHint:
        """직접 근거를 우선해 질문에 사용할 가장 관련성 높은 정황을 판단한다."""
        for commit in slot.linked_commits:
            if commit.message.casefold().startswith("revert"):
                return EvidenceHint(
                    kind="REVERT",
                    commit=commit,
                    gap_reason=(
                        "되돌린 이유와 실제 결과는 커밋만으로 확인되지 않음"
                    ),
                )

        if slot.linked_commits:
            return EvidenceHint(
                kind="LINKED_COMMIT",
                commit=slot.linked_commits[0],
                gap_reason=(
                    "작업 근거는 있지만 STAR 문장에 필요한 결과 또는 맥락이 부족함"
                ),
            )

        if candidate:
            for review in candidate.reviews:
                if review.state == "CHANGES_REQUESTED":
                    return EvidenceHint(
                        kind="CHANGES_REQUESTED",
                        review=review,
                        gap_reason=(
                            "변경 요청은 확인되지만 반영 과정과 판단 기준이 부족함"
                        ),
                    )

            if candidate.issues:
                return EvidenceHint(
                    kind="ISSUE_FEEDBACK",
                    issue=candidate.issues[0],
                    gap_reason=(
                        "이슈 피드백은 확인되지만 해결 과정과 실제 결과가 부족함"
                    ),
                )

            for commit in candidate.commits:
                if commit.message.casefold().startswith("revert"):
                    return EvidenceHint(
                        kind="REVERT",
                        commit=commit,
                        gap_reason=(
                            "되돌린 이유와 실제 결과는 커밋만으로 확인되지 않음"
                        ),
                    )

        return EvidenceHint(
            kind="NO_EVIDENCE",
            commit=None,
            gap_reason="대상 STAR 문장에 직접 연결된 커밋 근거가 없음",
        )

    def _ask_for_revert(self, hint: EvidenceHint) -> str:
        """revert 케이스: revert 커밋 SHA를 인용해 되돌린 이유를 묻는다."""
        sha = hint.commit.sha if hint.commit else "확인되지 않은 커밋"
        return (
            f"Revert 커밋({sha})을 찾았지만 되돌린 이유는 "
            "전달된 근거만으로 확인하기 어려워요. "
            f"혹시 어떤 상황이었나요? {_ESCAPE_HATCH}"
        )

    def _ask_for_linked_commit(self, slot: MissingSlot, hint: EvidenceHint) -> str:
        """직접 연결 커밋은 있으나 STAR 문장 보강이 필요한 경우를 묻는다."""
        message = hint.commit.message if hint.commit else "해당 작업"
        sha = hint.commit.sha if hint.commit else "확인되지 않은 커밋"
        return (
            f"커밋 {sha}의 ‘{message}’ 작업과 관련해 "
            f"{slot.star_slot} 부분에서 확인된 결과나 "
            f"판단 기준이 있었나요? {_ESCAPE_HATCH}"
        )

    def _ask_for_changes_requested(
        self, slot: MissingSlot, hint: EvidenceHint
    ) -> str:
        """changes requested 케이스: PR·리뷰 식별자와 요약을 인용한다."""
        review = hint.review
        if review is None:
            raise ValueError("CHANGES_REQUESTED 질문에는 review 문맥이 필요합니다.")
        return (
            f"PR #{review.pr_number}의 리뷰 {review.review_id}에서 "
            f"‘{review.summary}’라는 변경 요청이 있었어요. "
            f"이를 반영하면서 {slot.star_slot} 부분에서 어떤 기준으로 "
            f"수정하셨나요? {_ESCAPE_HATCH}"
        )

    def _ask_for_issue_feedback(self, slot: MissingSlot, hint: EvidenceHint) -> str:
        """issue feedback 케이스: 이슈 번호·제목과 제공된 요약을 인용한다."""
        issue = hint.issue
        if issue is None:
            raise ValueError("ISSUE_FEEDBACK 질문에는 issue 문맥이 필요합니다.")
        feedback_context = (
            f"‘{issue.summary}’라는 피드백이 있었어요. "
            if issue.summary
            else "관련 피드백이 있었어요. "
        )
        return (
            f"이슈 #{issue.issue_number} ‘{issue.title}’에서 "
            f"{feedback_context}"
            f"이를 반영한 뒤 {slot.star_slot} 부분에서 확인된 변화가 있었나요? "
            f"{_ESCAPE_HATCH}"
        )

    def _ask_fallback(self, slot: MissingSlot, candidate: CandidateContext | None) -> str:
        """직접 근거가 없을 때 카드 전체 커밋 문맥으로 회상을 유도한다.

        비어 있는 STAR 문장은 ``linked_commits``가 빈 배열인 것이 정상이다.
        이때도 카드 전체 커밋이 있으면 작업명을 질문에 인용한다. 카드 커밋까지
        없을 때만 완전히 일반적인 회상 질문을 생성한다.
        """
        if candidate and candidate.commits:
            commit = candidate.commits[0]
            pr_context = (
                f"PR #{candidate.github_pr_number}의 "
                if candidate.github_pr_number is not None
                else ""
            )
            return (
                f"{pr_context}커밋 {commit.sha}의 ‘{commit.message}’ 작업 이후 "
                f"{slot.star_slot} 부분에서 확인된 결과나 변화가 있었나요? {_ESCAPE_HATCH}"
            )
        if candidate and candidate.github_pr_number is not None:
            return (
                f"PR #{candidate.github_pr_number} 작업에서 {slot.star_slot} 부분과 관련해 "
                f"확인된 결과나 판단 기준이 있었나요? {_ESCAPE_HATCH}"
            )
        return (
            f"{slot.star_slot} 부분에 대한 근거를 아직 찾지 못했어요. "
            f"당시 상황을 간단히 설명해주실 수 있나요? {_ESCAPE_HATCH}"
        )

"""되묻기(interview-turns) 질문 생성 Agent — B-1.

이번 PR 범위에서는 실제 LLM/DB/GitHub API를 호출하지 않는다.
증거 종류(evidence_hint.kind)별로 결정적인(deterministic) 템플릿 질문만
생성한다. LLM 연동은 이후 PR에서 이 클래스의 핸들러 내부만 교체해 붙인다.

질문 생성 하드 규칙(명세서 gitory_api_spec_v2 346행):
    - "실패" 등 부정적 결과를 단정하지 않는다.
    - 탈출구("기억나지 않거나 단순 정리였다면 넘어가도 괜찮아요")를 반드시 포함한다.
    - 제공된 sha 또는 pr_number는 반드시 질문 본문에 인용한다.
    - existing_turn_count >= 2 면 질문을 생성하지 않고 next_action="COMPLETE".
"""

from __future__ import annotations

from schemas.interview import (
    CandidateContext,
    EvidenceHint,
    InterviewTurnRequest,
    InterviewTurnResult,
    MissingSlot,
    QuestionType,
)

#: 카드당 되묻기 질문 최대 횟수(명세서 0-5 전역 제약).
MAX_INTERVIEW_TURNS = 2

#: 모든 질문에 반드시 들어가는 탈출구 문구.
_ESCAPE_HATCH = "기억나지 않거나 단순 정리였다면 넘어가도 괜찮아요."


class InterviewAgent:
    """B-1 되묻기 질문을 생성하는 Agent."""

    def build_turn(self, request: InterviewTurnRequest) -> InterviewTurnResult:
        """요청을 받아 다음 되묻기 턴(또는 종료)을 결정한다.

        Args:
            request: `/internal/interview-turns` 요청 모델.

        Returns:
            새 질문 또는 `next_action="COMPLETE"`만 채워진 결과.
        """
        if request.existing_turn_count >= MAX_INTERVIEW_TURNS or not request.missing_slots:
            return InterviewTurnResult(card_id=request.card_id, next_action="COMPLETE")

        slot = request.missing_slots[0]
        hint = slot.evidence_hint

        if hint is None:
            question_text = self._ask_fallback(slot, request.candidate)
            question_type: QuestionType = "RECALL_AID"
        elif hint.kind == "REVERT":
            question_text = self._ask_for_revert(slot, hint)
            question_type = "EVIDENCE_GAP"
        elif hint.kind == "CHANGES_REQUESTED":
            question_text = self._ask_for_changes_requested(slot, hint)
            question_type = "EVIDENCE_GAP"
        elif hint.kind == "ISSUE_FEEDBACK":
            question_text = self._ask_for_issue_feedback(slot, hint)
            question_type = "EVIDENCE_GAP"
        else:
            # 알 수 없는 kind는 증거 전무와 동일하게 취급한다(백지 질문 금지).
            question_text = self._ask_fallback(slot, request.candidate)
            question_type = "RECALL_AID"

        return InterviewTurnResult(
            card_id=request.card_id,
            target_star_slot=slot.star_slot,
            target_statement_seq=slot.seq,
            question_type=question_type,
            trigger_source="AUTO",
            parent_turn_id=None,
            question_text=question_text,
            next_action="ASK_AGAIN",
        )

    def _ask_for_revert(self, slot: MissingSlot, hint: EvidenceHint) -> str:
        """revert 케이스: revert 커밋 SHA를 인용해 되돌린 이유를 묻는다."""
        sha = hint.sha or "확인되지 않은 커밋"
        return (
            f"Revert 커밋({sha})을 찾았는데 왜 되돌리셨는지는 코드에 없어요. "
            f"혹시 어떤 상황이었나요? {_ESCAPE_HATCH}"
        )

    def _ask_for_changes_requested(self, slot: MissingSlot, hint: EvidenceHint) -> str:
        """changes_requested 케이스: PR 번호를 인용해 반영 내용을 묻는다."""
        pr_ref = f"PR #{hint.pr_number}" if hint.pr_number is not None else "해당 PR"
        return (
            f"{pr_ref}에서 변경 요청(CHANGES_REQUESTED)이 있었는데, 어떤 부분을 "
            f"어떻게 반영하셨는지 궁금해요. {_ESCAPE_HATCH}"
        )

    def _ask_for_issue_feedback(self, slot: MissingSlot, hint: EvidenceHint) -> str:
        """issue_feedback 케이스: PR 번호를 인용해 피드백 대응을 묻는다."""
        pr_ref = f"PR #{hint.pr_number}" if hint.pr_number is not None else "해당 이슈"
        return (
            f"{pr_ref}에 달린 리뷰/이슈 피드백에 어떻게 대응하셨는지 코드만으로는 "
            f"알기 어려워요. 어떤 조치를 하셨는지 알려주실 수 있나요? {_ESCAPE_HATCH}"
        )

    def _ask_fallback(self, slot: MissingSlot, candidate: CandidateContext | None) -> str:
        """직접 근거가 없을 때 카드 전체 커밋 문맥으로 회상을 유도한다.

        비어 있는 STAR 문장은 ``linked_commits``가 빈 배열인 것이 정상이다.
        이때도 카드 전체 커밋이 있으면 작업명을 질문에 인용한다. 카드 커밋까지
        없을 때만 완전히 일반적인 회상 질문을 생성한다.
        """
        if candidate and candidate.commits:
            commit = candidate.commits[0]
            return (
                f"‘{commit.message}’ 작업 이후 {slot.star_slot} 부분에서 확인된 결과나 "
                f"변화가 있었나요? {_ESCAPE_HATCH}"
            )
        return (
            f"{slot.star_slot} 부분에 대한 근거를 아직 찾지 못했어요. "
            f"당시 상황을 간단히 설명해주실 수 있나요? {_ESCAPE_HATCH}"
        )

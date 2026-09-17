"""되묻기(interview-turns) 질문 생성 Agent — B-1.

이번 PR 범위에서는 실제 LLM/DB/GitHub API를 호출하지 않는다.
Spring이 전달한 카드·STAR 문장·커밋 문맥으로 내부 EvidenceHint를 만든 뒤,
결정적인(deterministic) 템플릿 질문을 생성한다. LLM 연동은 이후 PR에서 이
클래스의 핸들러 내부만 교체해 붙인다.

질문 생성 하드 규칙(명세서 gitory_api_spec_v2 346행):
    - "실패" 등 부정적 결과를 단정하지 않는다.
    - 탈출구("기억나지 않거나 단순 정리였다면 넘어가도 괜찮아요")를 반드시 포함한다.
    - 제공된 sha 또는 pr_number는 반드시 질문 본문에 인용한다.
    - existing_turn_count >= max_turns 면 질문을 생성하지 않고 next_action="COMPLETE".
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from schemas.interview import (
    CandidateContext,
    CommitContext,
    InterviewTurnRequest,
    InterviewTurnResult,
    MissingSlot,
    QuestionType,
)

#: 모든 질문에 반드시 들어가는 탈출구 문구.
_ESCAPE_HATCH = "기억나지 않거나 단순 정리였다면 넘어가도 괜찮아요."

InternalEvidenceKind = Literal["REVERT", "LINKED_COMMIT", "NO_EVIDENCE"]


@dataclass(frozen=True)
class EvidenceHint:
    """B가 카드 상태와 커밋 문맥에서 만드는 내부 질문 판단 모델.

    Spring은 이 모델을 만들거나 전달하지 않는다. 리뷰·이슈 원본 정보가 아직
    계약에 없으므로 현재 커밋 중심 MVP에서는 REVERT와 커밋/근거 부재만 판단한다.
    """

    kind: InternalEvidenceKind
    commit: CommitContext | None
    gap_reason: str


class InterviewAgent:
    """B-1 되묻기 질문을 생성하는 Agent."""

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
        else:
            question_text = self._ask_fallback(slot, request.candidate)
            question_type = "RECALL_AID"

        return InterviewTurnResult(
            card_id=request.card_id,
            target_star_slot=slot.star_slot,
            target_statement_seq=slot.statement_seq,
            question_type=question_type,
            trigger_source="AUTO",
            parent_turn_id=None,
            question_text=question_text,
            next_action="ASK_AGAIN",
        )

    def _build_evidence_hint(
        self, slot: MissingSlot, candidate: CandidateContext | None
    ) -> EvidenceHint:
        """직접 근거를 우선해 현재 MVP에서 가능한 질문 정황을 판단한다."""
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

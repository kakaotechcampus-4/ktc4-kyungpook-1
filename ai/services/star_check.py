"""STAR 초안이 입력 근거를 벗어난 곳을 칸마다 찾아, 무엇이 왜 걸렸는지 목록으로 돌려준다.

판정은 모델이 아니라 코드가 하고, 이유는 정해 둔 틀에 걸린 값만 넣어 쓴다. 고쳐 쓰기 루프가 이 목록을 모델에
돌려주고 과정 기록이 사용자에게 보여 주므로, 지어낸 이유가 섞이면 안 된다.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal, Optional

from schemas.analysis import DiffEvidence
from schemas.common import StarSlot
from services.evidence_checks import match_commit_sha, unsupported_metrics

DraftProblemCode = Literal["NO_EVIDENCE", "UNSOURCED_NUMBER", "UNKNOWN_SHA"]

NO_EVIDENCE_REASON = "입력에서 확인되는 근거 커밋이나 사용자 답변이 없어 비웠습니다."
UNSOURCED_NUMBER_REASON = "근거에서 확인되지 않는 수치({metrics})가 있어 사용자 확인이 필요합니다."
UNKNOWN_SHA_REASON = "입력에 없는 커밋 번호({sha})를 근거로 적어 그 번호는 뺐습니다."


@dataclass(frozen=True)
class DraftSlot:
    """모델이 채운 칸 하나의 글과 근거로 적은 커밋 번호."""

    text: str
    evidence_shas: list[str]


@dataclass(frozen=True)
class DraftProblem:
    """검사에 걸린 문제 하나로, value 는 걸린 수치나 커밋 번호다."""

    slot: StarSlot
    code: DraftProblemCode
    text: str
    value: Optional[str]
    reason: str


@dataclass(frozen=True)
class SlotCheck:
    """칸 하나의 검사 결과로, cited 는 입력 커밋에 맞춘 근거다."""

    cited: list[DiffEvidence]
    problems: list[DraftProblem]

    def problem(self, code: DraftProblemCode) -> Optional[DraftProblem]:
        return next((problem for problem in self.problems if problem.code == code), None)


def check_draft(
    slots: dict[StarSlot, DraftSlot],
    evidence_by_sha: dict[str, DiffEvidence],
    answers: list[str],
    pull_request_body: Optional[str],
    extra_sources: Optional[dict[StarSlot, list[str]]] = None,
) -> dict[StarSlot, SlotCheck]:
    """모델이 채운 칸마다 근거를 맞추고 문제를 찾는다. 모델이 스스로 비운 칸은 넘기지 않는다.

    extra_sources 는 칸마다 수치 근거로 더 볼 글로, 그 칸에 쓸 수 있는 자료 중 이번에 연 것만 넣는다.
    """
    extra = extra_sources or {}
    return {
        slot: _check_slot(slot, draft, evidence_by_sha, answers, pull_request_body, extra.get(slot, []))
        for slot, draft in slots.items()
    }


def _check_slot(
    slot: StarSlot,
    draft: DraftSlot,
    evidence_by_sha: dict[str, DiffEvidence],
    answers: list[str],
    pull_request_body: Optional[str],
    extra_sources: list[str],
) -> SlotCheck:
    cited, unknown = _cite(draft.evidence_shas, evidence_by_sha)
    problems = [
        DraftProblem(slot, "UNKNOWN_SHA", draft.text, sha, UNKNOWN_SHA_REASON.format(sha=sha)) for sha in unknown
    ]
    if not cited and not answers:
        # 근거 0개 문장은 화면에 뜨면 안 되므로 빠질 문장이라, 수치는 더 보지 않는다
        problems.append(DraftProblem(slot, "NO_EVIDENCE", draft.text, None, NO_EVIDENCE_REASON))
        return SlotCheck(cited, problems)
    unsupported = unsupported_metrics(draft.text, _metric_source(cited, answers, pull_request_body, extra_sources))
    if unsupported:
        metrics = ", ".join(unsupported)
        problems.append(DraftProblem(
            slot, "UNSOURCED_NUMBER", draft.text, metrics, UNSOURCED_NUMBER_REASON.format(metrics=metrics)
        ))
    return SlotCheck(cited, problems)


def _cite(shas: list[str], evidence_by_sha: dict[str, DiffEvidence]) -> tuple[list[DiffEvidence], list[str]]:
    """모델이 적은 커밋 번호를 입력 커밋에 맞추고, 맞출 수 없는 번호는 따로 모은다.

    저자 일치는 배제 조건이 아니다(ARCHITECTURE.md 기여 귀속).
    """
    cited: list[DiffEvidence] = []
    unknown: list[str] = []
    for sha in shas:
        matched = match_commit_sha(sha, evidence_by_sha, "STAR 초안")
        if matched is None:
            unknown.append(sha)
        elif evidence_by_sha[matched] not in cited:
            cited.append(evidence_by_sha[matched])
    return cited, unknown


def _metric_source(
    cited: list[DiffEvidence], answers: list[str], body: Optional[str], extra_sources: list[str]
) -> str:
    """수치 근거로 볼 글. 커밋 메시지·확인 답변과 카드 주인이 쓴 PR 본문, 그 칸에 쓸 수 있는 연 자료만 본다.

    요약·기술 포인트는 모델이 쓴 글이라 지어낸 수치가 스스로를 받치게 되고,
    churn(+30/-2)은 변경 줄 수라 결과 수치의 근거로 치지 않는다.
    """
    parts = [item.message for item in cited]
    parts.extend(answers)
    if body:
        parts.append(body)
    parts.extend(extra_sources)
    return "\n".join(parts)

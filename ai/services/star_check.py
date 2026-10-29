"""STAR 초안이 입력 근거를 벗어난 곳을 칸마다 찾아, 무엇이 왜 걸렸는지 목록으로 돌려준다.

판정은 모델이 아니라 코드가 하고, 이유는 정해 둔 틀에 걸린 값만 넣어 쓴다. 고쳐 쓰기 루프가 이 목록을 모델에
돌려주고 과정 기록이 사용자에게 보여 주므로, 지어낸 이유가 섞이면 안 된다.
"""

from __future__ import annotations

import re
from collections.abc import Iterable
from dataclasses import dataclass
from typing import Literal, Optional

from schemas.analysis import DiffEvidence
from schemas.common import StarSlot
from services.evidence_checks import match_commit_sha, unsupported_metrics

DraftProblemCode = Literal["NO_EVIDENCE", "UNSOURCED_NUMBER", "UNKNOWN_SHA", "CODE_NAME"]

NO_EVIDENCE_REASON = "입력에서 확인되는 근거 커밋이나 사용자 답변이 없어 비웠습니다."
UNSOURCED_NUMBER_REASON = "근거에서 확인되지 않는 수치({metrics})가 있어 사용자 확인이 필요합니다."
UNKNOWN_SHA_REASON = "입력에 없는 커밋 번호({sha})를 근거로 적어 그 번호는 뺐습니다."
CODE_NAME_REASON = "프로젝트 안에서만 쓰는 이름({names})이 있어 보통 말로 바꿀지 확인이 필요합니다."

#: 코드 이름 모양으로, 소문자 뒤에 대문자가 오거나(MyPageResponse·userId) 밑줄로 이은 이름이다.
_IDENTIFIER_SHAPE = re.compile(r"[a-z][A-Z]|_")
_UPPER_SNAKE = re.compile(r"[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+")
_NAME_TOKEN = re.compile(r"(?<![A-Za-z0-9_.])[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z0-9]+)?(?![A-Za-z0-9_])")
_BACKTICKED = re.compile(r"`([^`\s]+)`")


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
) -> dict[StarSlot, SlotCheck]:
    """모델이 채운 칸마다 근거를 맞추고 문제를 찾는다. 모델이 스스로 비운 칸은 넘기지 않는다."""
    names = project_names(evidence_by_sha.values())
    return {
        slot: _check_slot(slot, draft, evidence_by_sha, answers, pull_request_body, names)
        for slot, draft in slots.items()
    }


def project_names(evidence: Iterable[DiffEvidence]) -> set[str]:
    """이 카드 근거 커밋에서 바뀐 파일 이름과 코드 이름 모양의 파일 이름, 커밋 메시지·요약에 `` 로 감싼 코드 이름이다.

    Spring·PostgreSQL 같은 기술 이름은 바뀐 파일 이름이 아니라서 들어가지 않아, 공고 매칭에 쓰는 낱말을 바꾸게 하지 않는다.
    """
    names: set[str] = set()
    for item in evidence:
        for path in item.changed_paths:
            file_name = path.rsplit("/", 1)[-1]
            stem = file_name.split(".", 1)[0]
            if "." in file_name:
                names.add(file_name)
            if _IDENTIFIER_SHAPE.search(stem):
                names.add(stem)
        for text in (item.message, item.summary, *item.technical_points):
            names.update(name for name in _BACKTICKED.findall(text) if _IDENTIFIER_SHAPE.search(name))
    return names


def code_names(text: str, names: set[str]) -> list[str]:
    """글에 나온 프로젝트 안 이름으로, 근거의 이름과 오류 코드처럼 대문자를 밑줄로 이은 이름이다."""
    found: list[str] = []
    for token in _NAME_TOKEN.findall(text):
        if (token in names or _UPPER_SNAKE.fullmatch(token)) and token not in found:
            found.append(token)
    return found


def _check_slot(
    slot: StarSlot,
    draft: DraftSlot,
    evidence_by_sha: dict[str, DiffEvidence],
    answers: list[str],
    pull_request_body: Optional[str],
    names: set[str],
) -> SlotCheck:
    cited, unknown = _cite(draft.evidence_shas, evidence_by_sha)
    problems = [
        DraftProblem(slot, "UNKNOWN_SHA", draft.text, sha, UNKNOWN_SHA_REASON.format(sha=sha)) for sha in unknown
    ]
    if not cited and not answers:
        # 근거 0개 문장은 화면에 뜨면 안 되므로 빠질 문장이라, 수치는 더 보지 않는다
        problems.append(DraftProblem(slot, "NO_EVIDENCE", draft.text, None, NO_EVIDENCE_REASON))
        return SlotCheck(cited, problems)
    unsupported = unsupported_metrics(draft.text, _metric_source(cited, answers, pull_request_body))
    if unsupported:
        metrics = ", ".join(unsupported)
        problems.append(DraftProblem(
            slot, "UNSOURCED_NUMBER", draft.text, metrics, UNSOURCED_NUMBER_REASON.format(metrics=metrics)
        ))
    found = code_names(draft.text, names)
    if found:
        joined = ", ".join(found)
        problems.append(DraftProblem(slot, "CODE_NAME", draft.text, joined, CODE_NAME_REASON.format(names=joined)))
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


def _metric_source(cited: list[DiffEvidence], answers: list[str], body: Optional[str]) -> str:
    """수치 근거로 볼 글. 커밋 메시지·확인 답변과 카드 주인이 쓴 PR 본문만 본다.

    요약·기술 포인트는 모델이 쓴 글이라 지어낸 수치가 스스로를 받치게 되고,
    churn(+30/-2)은 변경 줄 수라 결과 수치의 근거로 치지 않는다.
    """
    parts = [item.message for item in cited]
    parts.extend(answers)
    if body:
        parts.append(body)
    return "\n".join(parts)

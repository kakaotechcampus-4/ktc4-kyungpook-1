"""경험 묶기의 규칙 부분: 쪼갤 수 없는 작업 단위, 경험 병합, 점수를 만든다."""

from __future__ import annotations

from collections import Counter
from collections.abc import Sequence
from dataclasses import dataclass, field
from math import log1p
from pathlib import PurePosixPath
from typing import Literal

from schemas.analysis import (
    CANDIDATE_REASON_MAX_LENGTH,
    CANDIDATE_TITLE_MAX_LENGTH,
    CommitInput,
    ExperienceCandidate,
    PullRequestContext,
)
from schemas.common import CandidateSourceType

#: LLM이 점수 규칙을 보조하려고 주는 카드감 판단.
CardWorthHint = Literal["HIGH", "LOW"]

#: 출처별 기본 점수. PR은 리뷰·설명이 남아 있어 카드 근거가 가장 풍부하다.
SOURCE_SCORE: dict[str, float] = {"PR": 0.45, "ISSUE": 0.35, "COMMIT_CLUSTER": 0.25}
#: 커밋 수·파일 수·변경량 점수의 최대 기여분과 포화 기준.
COMMIT_WEIGHT, COMMIT_SATURATION = 0.20, 5
FILE_WEIGHT, FILE_SATURATION = 0.10, 10
CHURN_WEIGHT, CHURN_SATURATION = 0.20, 500
#: 변경량을 모를 때 쓰는 중립값(CHURN_WEIGHT의 절반). 조회 여부가 순위를 지배하지 않게 한다.
NEUTRAL_CHURN_SCORE = 0.10
HINT_SCORE: dict[str, float] = {"HIGH": 0.05, "LOW": -0.05}
#: 이 변경량 미만이면 카드로 쓰기엔 내용이 얇다.
MIN_CARD_CHURN = 20

_DOC_OR_CONFIG_SUFFIXES = frozenset({".md", ".rst", ".adoc", ".yml", ".yaml", ".toml", ".ini"})
_DOC_OR_CONFIG_NAMES = frozenset({".editorconfig", ".gitignore", "dockerfile"})


@dataclass
class CommitGroup:
    """커밋 묶음. 쪼갤 수 없는 작업 단위이거나, 단위를 합친 경험 하나다.

    ``source_type``은 대표 출처다. PR이 하나라도 있으면 ``PR``, 없으면 ``COMMIT_CLUSTER``.
    Spring ``candidate.source_type``이 ``PR``·``COMMIT_CLUSTER``만 받으므로 Issue만 있는
    경험도 ``COMMIT_CLUSTER``로 두고 ``issue_numbers``로 연결을 남긴다.
    """

    group_key: str
    source_type: CandidateSourceType
    source_ref: str
    members: list[CommitInput]
    pull_request_numbers: list[int] = field(default_factory=list)
    issue_numbers: list[int] = field(default_factory=list)

    @property
    def pull_request_number(self) -> int | None:
        """대표 PR: 내 커밋이 가장 많이 속한 PR, 같으면 번호가 작은 PR.

        Spring ``candidate.github_pr_number``가 PR 하나만 담으므로 이 값을 쓴다.
        """
        if not self.pull_request_numbers:
            return None
        counts = Counter(commit.pull_request_number for commit in self.members)
        return min(self.pull_request_numbers, key=lambda number: (-counts[number], number))


class CommitGrouper:
    """경험 묶기의 규칙 부분과 점수 계산을 담당한다.

    1. ``work_units``: PR·Issue 연결로 확정되는 묶음과 낱개 커밋을 쪼갤 수 없는 작업 단위로 만든다.
    2. ``GroupingLlm``이 같은 기능 경험에 속하는 단위들을 고른다.
    3. ``merge``: 고른 단위들을 경험 하나로 합치고 대표 PR·출처를 정한다.
    입력 커밋은 ``CommitSelector``가 SHA 중복을 제거한 상태라고 가정한다.
    """

    def work_units(
        self,
        commits: list[CommitInput],
        pull_requests: Sequence[PullRequestContext] = (),
    ) -> list[CommitGroup]:
        """PR 묶음, Issue 묶음, 낱개 커밋을 쪼갤 수 없는 작업 단위로 만든다.

        PR이 Issue보다 우선한다. 여러 Issue를 참조하는 커밋은 처음 참조한 Issue에만 넣는다.
        참조 관계를 따라 전이적으로 합치면 Issue를 여러 개 적은 커밋 하나가 무관한
        작업들을 이어 붙이기 때문이다. 관련 단위를 합치는 판단은 LLM 단계가 맡는다.

        PR 본문이 해결한다고 밝힌 Issue(``linked_issue_numbers``)를 참조하는 커밋은 그 PR
        단위에 넣는다. 이미 내 커밋이 있는 PR에만 합친다. 다른 사람의 PR에 내 커밋을
        끌어다 붙이지 않기 위해서다.
        """
        ordered = self._chronological(commits)
        pr_members: dict[int, list[CommitInput]] = {}
        for commit in ordered:
            if commit.pull_request_number is not None:
                pr_members.setdefault(commit.pull_request_number, []).append(commit)

        resolving_pr: dict[int, int] = {}
        for pull in sorted(pull_requests, key=lambda item: item.number):
            if pull.number not in pr_members:
                continue
            for issue_number in pull.linked_issue_numbers:
                resolving_pr.setdefault(issue_number, pull.number)

        issue_members: dict[int, list[CommitInput]] = {}
        loose: list[CommitInput] = []
        for commit in ordered:
            if commit.pull_request_number is not None:
                continue
            if not commit.issue_numbers:
                loose.append(commit)
            elif commit.issue_numbers[0] in resolving_pr:
                pr_members[resolving_pr[commit.issue_numbers[0]]].append(commit)
            else:
                issue_members.setdefault(commit.issue_numbers[0], []).append(commit)

        units = [
            self.merge([], members=members, pull_request_numbers=[number])
            for number, members in sorted(pr_members.items())
        ]
        units += [
            self.merge([], members=members, issue_numbers=[number])
            for number, members in sorted(issue_members.items())
        ]
        units += [self.merge([], members=[commit]) for commit in loose]
        return units

    def merge(
        self,
        units: Sequence[CommitGroup],
        *,
        members: Sequence[CommitInput] = (),
        pull_request_numbers: Sequence[int] = (),
        issue_numbers: Sequence[int] = (),
    ) -> CommitGroup:
        """작업 단위들을 경험 하나로 합친다. 대표 출처·키·참조는 규칙으로 정한다.

        ``issue_numbers``에는 단위가 묶인 Issue와 커밋 메시지가 참조한 Issue를 모두 남긴다.
        """
        unique: dict[str, CommitInput] = {}
        for commit in [*members, *(member for unit in units for member in unit.members)]:
            unique.setdefault(commit.sha, commit)
        all_members = self._chronological(list(unique.values()))
        if not all_members:
            raise ValueError("빈 묶음은 경험으로 만들 수 없습니다")

        pulls = sorted(
            {*pull_request_numbers, *(n for unit in units for n in unit.pull_request_numbers)}
        )
        issues = sorted(
            {
                *issue_numbers,
                *(n for unit in units for n in unit.issue_numbers),
                *(n for commit in all_members for n in commit.issue_numbers),
            }
        )
        group = CommitGroup(
            group_key="",
            source_type="PR" if pulls else "COMMIT_CLUSTER",
            source_ref="",
            members=all_members,
            pull_request_numbers=pulls,
            issue_numbers=issues,
        )
        representative = group.pull_request_number
        if representative is not None:
            group.group_key = f"pr:{representative}"
            group.source_ref = f"#{representative}"
        else:
            first = all_members[0]
            day = first.authored_at.date().isoformat()
            group.group_key = f"cluster:{day}:{first.sha[:8].lower()}"
            group.source_ref = day
        return group

    def score(
        self, group: CommitGroup, card_worth_hint: CardWorthHint | None = None
    ) -> float:
        """출처·커밋 수·파일 수·변경량으로 0~1 점수를 계산한다.

        출처 점수는 PR이 있으면 PR, 없고 Issue가 있으면 ISSUE, 둘 다 없으면 커밋 묶음 기준이다.
        """
        if not group.members:
            return 0.0
        if group.pull_request_numbers:
            source = "PR"
        elif group.issue_numbers:
            source = "ISSUE"
        else:
            source = "COMMIT_CLUSTER"

        commit_score = self._saturate(len(group.members), COMMIT_SATURATION) * COMMIT_WEIGHT
        file_count = self._file_count(group.members)
        file_score = (
            self._saturate(file_count, FILE_SATURATION) * FILE_WEIGHT
            if file_count is not None
            else 0.0
        )
        churn = self._known_churn(group.members)
        churn_score = (
            min(log1p(churn) / log1p(CHURN_SATURATION), 1.0) * CHURN_WEIGHT
            if churn is not None
            else NEUTRAL_CHURN_SCORE
        )
        result = (
            SOURCE_SCORE[source]
            + commit_score
            + file_score
            + churn_score
            + HINT_SCORE.get(card_worth_hint or "", 0.0)
        )
        return round(min(max(result, 0.0), 1.0), 4)

    def to_candidate(
        self,
        group: CommitGroup,
        title: str,
        reason: str,
        card_worth_hint: CardWorthHint | None = None,
    ) -> ExperienceCandidate:
        """경험과 LLM 제목·설명으로 후보를 만든다. 점수는 규칙으로 계산한다.

        DB 컬럼 길이를 넘는 제목·설명은 요청 전체를 실패시키지 않도록 잘라 담는다.
        """
        if not group.members:
            raise ValueError("빈 커밋 그룹은 후보로 만들 수 없습니다")
        normalized_title = title.strip()
        normalized_reason = reason.strip()
        if not normalized_title or not normalized_reason:
            raise ValueError("후보 제목과 선정 이유는 비어 있을 수 없습니다")

        return ExperienceCandidate(
            group_key=group.group_key,
            source_type=group.source_type,
            source_ref=group.source_ref or None,
            pull_request_number=group.pull_request_number,
            pull_request_numbers=group.pull_request_numbers,
            issue_numbers=group.issue_numbers,
            title=self._truncate(normalized_title, CANDIDATE_TITLE_MAX_LENGTH),
            reason=self._truncate(normalized_reason, CANDIDATE_REASON_MAX_LENGTH),
            score=self.score(group, card_worth_hint),
            low_card_worth=self._is_low_card_worth(group),
            commit_shas=[commit.sha for commit in self._chronological(group.members)],
        )

    @staticmethod
    def _chronological(commits: list[CommitInput]) -> list[CommitInput]:
        return sorted(commits, key=lambda commit: (commit.authored_at, commit.sha))

    @staticmethod
    def _saturate(value: int, saturation: int) -> float:
        return min(value, saturation) / saturation

    @staticmethod
    def _known_churn(members: list[CommitInput]) -> int | None:
        """모든 커밋의 변경량을 알 때만 합계를 반환한다. 일부만 알면 모르는 것으로 본다."""
        if any(commit.additions is None or commit.deletions is None for commit in members):
            return None
        return sum(commit.additions + commit.deletions for commit in members)

    @staticmethod
    def _file_count(members: list[CommitInput]) -> int | None:
        """파일 목록이 있으면 서로 다른 경로 수, 없으면 커밋별 changed_files 합계를 쓴다."""
        paths = {changed.path for commit in members for changed in commit.files}
        if paths:
            return len(paths)
        if all(commit.changed_files is not None for commit in members):
            return sum(commit.changed_files for commit in members)
        return None

    @staticmethod
    def _truncate(text: str, max_length: int) -> str:
        return text if len(text) <= max_length else text[: max_length - 1] + "…"

    @classmethod
    def _is_low_card_worth(cls, group: CommitGroup) -> bool:
        """DB low_card_worth 규칙: 근거 커밋 1건, 문서·설정 전용, 변경량 임계 미만.

        점수·LLM 힌트는 보지 않는다. 같은 입력이면 항상 같은 판정이어야 한다.
        """
        if len(group.members) == 1:
            return True
        paths = [changed.path for commit in group.members for changed in commit.files]
        if paths and all(cls._is_documentation_or_config(path) for path in paths):
            return True
        churn = cls._known_churn(group.members)
        return churn is not None and churn < MIN_CARD_CHURN

    @staticmethod
    def _is_documentation_or_config(path: str) -> bool:
        normalized = path.replace("\\", "/").strip("/").casefold()
        parts = PurePosixPath(normalized).parts
        name = parts[-1] if parts else ""
        return (
            "docs" in parts[:-1]
            or name.startswith("readme")
            or PurePosixPath(name).suffix in _DOC_OR_CONFIG_SUFFIXES
            or name in _DOC_OR_CONFIG_NAMES
        )

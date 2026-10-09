from datetime import datetime, timedelta, timezone

import pytest
from pydantic import ValidationError

from schemas.analysis import ChangedFile, CommitInput, PullRequestContext
from services.commit_grouper import NEUTRAL_CHURN_SCORE, CommitGroup, CommitGrouper


BASE_TIME = datetime(2026, 9, 1, tzinfo=timezone.utc)


def commit(sha: str, minute: int = 0, **overrides: object) -> CommitInput:
    values = {
        "sha": sha,
        "message": "feat: 기능 구현",
        "author_login": "minseo",
        "authored_at": BASE_TIME + timedelta(minutes=minute),
        "parent_count": 1,
    }
    values.update(overrides)
    return CommitInput(**values)


def pr_group(members: list[CommitInput], number: int = 1) -> CommitGroup:
    return CommitGrouper().merge([], members=members, pull_request_numbers=[number])


def cluster_group(members: list[CommitInput]) -> CommitGroup:
    return CommitGrouper().merge([], members=members)


def test_work_units_prioritize_pr_use_first_issue_and_keep_loose_commits_single() -> None:
    pr = commit("a" * 40, pull_request_number=7, issue_numbers=[10])
    issue_bridge = commit("b" * 40, minute=2, issue_numbers=[10, 11])
    issue_11 = commit("c" * 40, minute=1, issue_numbers=[11])
    loose_1 = commit("d" * 40, minute=3)
    loose_2 = commit("e" * 40, minute=4)

    units = CommitGrouper().work_units([loose_2, loose_1, issue_bridge, pr, issue_11])

    assert [(unit.group_key, unit.source_type, unit.source_ref) for unit in units] == [
        ("pr:7", "PR", "#7"),
        ("cluster:2026-09-01:bbbbbbbb", "COMMIT_CLUSTER", "2026-09-01"),
        ("cluster:2026-09-01:cccccccc", "COMMIT_CLUSTER", "2026-09-01"),
        ("cluster:2026-09-01:dddddddd", "COMMIT_CLUSTER", "2026-09-01"),
        ("cluster:2026-09-01:eeeeeeee", "COMMIT_CLUSTER", "2026-09-01"),
    ]
    assert units[0].pull_request_numbers == [7]
    # Issue 단위는 묶인 Issue와 커밋이 참조한 Issue를 모두 남긴다.
    assert units[1].issue_numbers == [10, 11]
    assert units[2].issue_numbers == [11]
    assert [len(unit.members) for unit in units[3:]] == [1, 1]


def test_issue_resolved_by_my_pr_joins_that_pr_unit() -> None:
    in_pr = commit("a" * 40, pull_request_number=17)
    follow_up = commit("b" * 40, minute=1, issue_numbers=[42])
    other_issue = commit("c" * 40, minute=2, issue_numbers=[50])
    pulls = [
        PullRequestContext(number=17, title="세션 조회 개선", linked_issue_numbers=[42]),
        # 내 커밋이 없는 PR이 #50을 해결해도 내 커밋을 그 PR로 옮기지 않는다.
        PullRequestContext(number=18, title="남의 PR", linked_issue_numbers=[50]),
    ]

    units = CommitGrouper().work_units([other_issue, follow_up, in_pr], pulls)

    assert [unit.group_key for unit in units] == ["pr:17", "cluster:2026-09-01:cccccccc"]
    assert [item.sha for item in units[0].members] == [in_pr.sha, follow_up.sha]
    assert units[1].issue_numbers == [50]


def test_issue_chain_does_not_merge_unrelated_issues() -> None:
    chain = [
        commit("a" * 40, issue_numbers=[1, 2]),
        commit("b" * 40, minute=1, issue_numbers=[2, 3]),
        commit("c" * 40, minute=2, issue_numbers=[3, 4]),
    ]

    units = CommitGrouper().work_units(chain)

    assert [len(unit.members) for unit in units] == [1, 1, 1]


def test_merge_combines_units_into_one_experience_with_representative_pr() -> None:
    grouper = CommitGrouper()
    api = [commit("a" * 40, 0, pull_request_number=17), commit("b" * 40, 1, pull_request_number=17)]
    ui = [commit("c" * 40, 2, pull_request_number=21)]
    fix = commit("d" * 40, 3, issue_numbers=[42])
    units = grouper.work_units([*api, *ui, fix])

    experience = grouper.merge(units)

    assert experience.source_type == "PR"
    assert experience.pull_request_numbers == [17, 21]
    # 대표 PR은 내 커밋이 가장 많은 PR이다.
    assert experience.pull_request_number == 17
    assert experience.group_key == "pr:17"
    assert experience.source_ref == "#17"
    assert experience.issue_numbers == [42]
    assert [item.sha for item in experience.members] == [c.sha for c in [*api, *ui, fix]]


def test_merge_without_pr_is_commit_cluster_even_with_issue() -> None:
    experience = cluster_group([commit("a" * 40, issue_numbers=[42]), commit("b" * 40, 1)])

    assert experience.source_type == "COMMIT_CLUSTER"
    assert experience.pull_request_number is None
    assert experience.issue_numbers == [42]
    assert experience.group_key == "cluster:2026-09-01:aaaaaaaa"


def test_score_prefers_pr_then_issue_and_applies_hint() -> None:
    members = [
        commit("a" * 40, additions=100, deletions=20),
        commit("b" * 40, minute=1, additions=10, deletions=2),
    ]
    with_issue = [commit("a" * 40, additions=100, deletions=20, issue_numbers=[3]), members[1]]
    grouper = CommitGrouper()
    pr = pr_group(members)

    assert grouper.score(pr) > grouper.score(cluster_group(with_issue)) > grouper.score(cluster_group(members))
    assert grouper.score(pr, "HIGH") > grouper.score(pr) > grouper.score(pr, "LOW")


def test_score_treats_partially_known_churn_as_unknown() -> None:
    unknown = [commit(f"{i:040x}", minute=i) for i in range(5)]
    partial = [commit(f"{0:040x}", additions=1, deletions=0), *unknown[1:]]
    grouper = CommitGrouper()

    unknown_score = grouper.score(pr_group(unknown))
    partial_score = grouper.score(pr_group(partial))

    assert partial_score == unknown_score
    # PR 0.45 + 커밋 5개 0.20 + 파일 정보 없음 0 + 변경량 중립값
    assert unknown_score == round(0.45 + 0.20 + NEUTRAL_CHURN_SCORE, 4)


def test_score_uses_changed_files_count_when_file_list_is_missing() -> None:
    grouper = CommitGrouper()
    without = pr_group([commit("a" * 40), commit("b" * 40, 1)])
    with_count = pr_group([commit("a" * 40, changed_files=6), commit("b" * 40, 1, changed_files=4)])

    assert grouper.score(with_count) == pytest.approx(grouper.score(without) + 0.10)


def test_low_card_worth_follows_rules_and_ignores_hint() -> None:
    grouper = CommitGrouper()
    single = pr_group([commit("a" * 40)])
    docs = cluster_group(
        [
            commit(
                "b" * 40,
                additions=20,
                deletions=0,
                files=[ChangedFile(path="README.md", additions=20, deletions=0)],
            ),
            commit(
                "c" * 40,
                minute=1,
                additions=20,
                deletions=0,
                files=[ChangedFile(path="docs/api.md", additions=20, deletions=0)],
            ),
        ]
    )
    tiny = pr_group(
        [commit("d" * 40, additions=5, deletions=0), commit("e" * 40, 1, additions=5, deletions=0)], 3
    )
    cluster_without_diff = cluster_group([commit("f" * 40), commit("0" * 40, 1)])

    assert grouper.to_candidate(single, "제목", "근거").low_card_worth is True
    assert grouper.to_candidate(docs, "문서", "문서만 변경").low_card_worth is True
    assert grouper.to_candidate(tiny, "작은 변경", "10줄").low_card_worth is True
    assert grouper.to_candidate(cluster_without_diff, "묶음", "근거").low_card_worth is False
    assert (
        grouper.to_candidate(cluster_without_diff, "묶음", "근거", "LOW").low_card_worth
        is False
    )


def test_to_candidate_carries_experience_references_and_truncates_text() -> None:
    grouper = CommitGrouper()
    experience = grouper.merge(
        grouper.work_units(
            [
                commit("a" * 40, pull_request_number=17),
                commit("b" * 40, 1, pull_request_number=21),
                commit("c" * 40, 2, issue_numbers=[42]),
            ]
        )
    )
    candidate = grouper.to_candidate(experience, " " + "가" * 250 + " ", "나" * 400)

    assert candidate.source_type == "PR"
    assert candidate.pull_request_number == 17
    assert candidate.pull_request_numbers == [17, 21]
    assert candidate.issue_numbers == [42]
    assert candidate.commit_shas == ["a" * 40, "b" * 40, "c" * 40]
    assert len(candidate.title) == 200 and candidate.title.endswith("…")
    assert len(candidate.reason) == 300

    with pytest.raises(ValueError, match="비어"):
        grouper.to_candidate(experience, "  ", "이유")


def test_commit_input_rejects_non_positive_pr_and_issue_numbers() -> None:
    with pytest.raises(ValidationError):
        commit("a" * 40, pull_request_number=0)
    with pytest.raises(ValidationError):
        commit("a" * 40, issue_numbers=[-1])

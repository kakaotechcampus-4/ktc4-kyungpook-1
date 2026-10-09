from datetime import datetime, timezone

import pytest
from pydantic import ValidationError

from schemas.analysis import ChangedFile, CommitInput, ExperienceGroupingRequest
from services.commit_selector import CommitSelector


def commit(sha: str, **overrides: object) -> CommitInput:
    values = {
        "sha": sha,
        "message": "feat: 기능 구현",
        "author_login": "minseo",
        "authored_at": datetime(2026, 9, 1, tzinfo=timezone.utc),
        "parent_count": 1,
    }
    values.update(overrides)
    return CommitInput(**values)


def test_select_excludes_merge_bot_other_author_generated_and_too_large() -> None:
    commits = [
        commit("a" * 40),
        commit("b" * 40, parent_count=2),
        commit("c" * 40, author_login="dependabot[bot]"),
        commit("d" * 40, author_login="someone-else"),
        commit(
            "e" * 40,
            files=[ChangedFile(path="package-lock.json", additions=3, deletions=1)],
        ),
        commit("f" * 40, additions=9_000, deletions=1_001),
    ]

    selected, excluded = CommitSelector().select(commits, "MINSEO")

    assert [item.sha for item in selected] == ["a" * 40]
    assert excluded == ["b" * 40, "c" * 40, "d" * 40, "e" * 40, "f" * 40]


def test_select_keeps_unknown_author_and_docs_changes_and_deduplicates_sha() -> None:
    original = commit(
        "a" * 40,
        author_login=None,
        author_name=None,
        files=[ChangedFile(path="docs/design.md", additions=10, deletions=0)],
    )
    duplicate = commit("A" * 40)

    selected, excluded = CommitSelector().select([original, duplicate], "minseo")

    assert selected == [original]
    assert excluded == []


def test_select_keeps_commits_when_login_is_missing_regardless_of_author_name() -> None:
    mine = commit("a" * 40, author_login=None, author_name="MinSeo")
    different_display_name = commit(
        "b" * 40,
        author_login=None,
        author_name="Taehun",
    )

    selected, excluded = CommitSelector().select(
        [mine, different_display_name],
        "minseo",
    )

    assert selected == [mine, different_display_name]
    assert excluded == []


def test_select_excludes_other_user_when_github_login_is_known() -> None:
    theirs = commit(
        "a" * 40,
        author_login="taehun0208",
        author_name="MinSeo",
    )

    selected, excluded = CommitSelector().select([theirs], "minseo")

    assert selected == []
    assert excluded == [theirs.sha]


def test_select_does_not_exclude_when_diff_metadata_is_missing() -> None:
    item = commit("a" * 40, additions=None, deletions=None, files=[])
    assert CommitSelector().select([item], "minseo") == ([item], [])


def test_select_trusts_explicit_collector_ownership_decision() -> None:
    matched_by_email = commit(
        "a" * 40,
        author_login=None,
        author_name="Display Name",
        is_excluded=False,
    )
    excluded_by_collector = commit(
        "b" * 40,
        author_login="minseo",
        is_excluded=True,
        exclusion_reason="LOCKFILE_ONLY",
    )

    selected, excluded = CommitSelector().select(
        [matched_by_email, excluded_by_collector], "minseo"
    )

    assert selected == [matched_by_email]
    assert excluded == [excluded_by_collector.sha]


def test_select_applies_size_and_generated_rules_even_after_collector_decision() -> None:
    huge = commit("a" * 40, additions=20_000, deletions=0, is_excluded=False)
    vendored = commit(
        "b" * 40,
        files=[ChangedFile(path="node_modules/pkg/index.js", additions=5, deletions=0)],
        is_excluded=False,
    )
    bot = commit("c" * 40, author_login="renovate[bot]", is_excluded=False)

    selected, excluded = CommitSelector().select([huge, vendored, bot], "minseo")

    assert selected == []
    assert excluded == [huge.sha, vendored.sha, bot.sha]


def test_grouping_request_rejects_blank_target_login() -> None:
    with pytest.raises(ValidationError):
        ExperienceGroupingRequest(repository_id="repo-1", target_login="  ", commits=[])

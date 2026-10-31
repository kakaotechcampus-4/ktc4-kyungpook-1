"""분석 진행 줄에 싣는 값: 커밋은 메시지 첫 줄·짧은 sha·뺀 이유를 싣고, 글 값은 비밀값을 가린 뒤 자른다."""

from __future__ import annotations

from datetime import datetime, timezone

from schemas.collection import CollectedCommit, ExclusionReason
from services.progress import commit_item, described_item, pull_request_item

SECRET = "ghp_" + "a" * 30


def test_commit_item_carries_first_line_short_sha_files_and_exclusion() -> None:
    commit = CollectedCommit(
        sha="abc1234567890",
        repository_id=456789,
        author_login="grow22",
        message="Merge branch 'main'\n\n충돌을 풀었다",
        authored_at=datetime(2026, 9, 20, tzinfo=timezone.utc),
        additions=12,
        deletions=3,
        changed_files=2,
        parent_count=2,
        is_excluded=True,
        exclusion_reason=ExclusionReason.MERGE_COMMIT,
    )

    assert commit_item(commit) == {
        "kind": "COMMIT", "sha": "abc1234", "title": "Merge branch 'main'", "files": 2, "excluded": "MERGE_COMMIT",
    }


def test_text_values_hide_secrets_before_cutting_to_line_length() -> None:
    assert pull_request_item(31, "가" * 90 + SECRET + "뒤", 4) == {
        "kind": "PULL_REQUEST", "number": 31, "title": "가" * 90 + "[REDACTED]", "reviews": 4,
    }
    assert described_item("배포 자동화", "나" * 190 + SECRET + "뒤") == {
        "kind": "DESCRIBED", "title": "배포 자동화", "reason": "나" * 190 + "[REDACTED]",
    }

-- git_commit.exclusion_reason 을 AI 수집기가 실제로 보내는 값으로 맞춘다.
-- MERGE -> MERGE_COMMIT 이름 변경, VENDORED · TOO_LARGE 제거, LOCKFILE_ONLY · NOT_OWN 추가.

ALTER TABLE git_commit DROP CONSTRAINT git_commit_exclusion_reason_check;
UPDATE git_commit SET exclusion_reason = 'MERGE_COMMIT' WHERE exclusion_reason = 'MERGE';
ALTER TABLE git_commit ADD CONSTRAINT git_commit_exclusion_reason_check
    CHECK (exclusion_reason IS NULL OR exclusion_reason IN (
        'BOT', 'MERGE_COMMIT', 'LOCKFILE_ONLY', 'NOT_OWN'));

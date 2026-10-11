-- 커밋마다 바뀐 파일을 담을 테이블과 git_commit 의 이슈 번호 칸을 더하고, 이 칸이 NULL 인 커밋은 파일과 이슈 번호를 아직 채우지 않은 것으로 본다.
-- 커밋 행은 사용자끼리 함께 쓰는데 NOT_OWN 은 먼저 분석한 사람 기준이라, 저장된 NOT_OWN 은 제외하지 않은 커밋으로 되돌리고 허용 값에서 뺀다.

CREATE TABLE commit_file (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    commit_id BIGINT      NOT NULL REFERENCES git_commit (id) ON DELETE CASCADE,
    path      TEXT        NOT NULL,
    status    VARCHAR(10) NOT NULL
        CHECK (status IN ('ADDED', 'MODIFIED', 'REMOVED', 'RENAMED', 'COPIED', 'CHANGED', 'UNCHANGED', 'UNKNOWN')),
    additions INT         NOT NULL,
    deletions INT         NOT NULL,
    UNIQUE (commit_id, path)
);

ALTER TABLE git_commit ADD COLUMN issue_numbers INT[];

UPDATE git_commit SET is_excluded = FALSE, exclusion_reason = NULL WHERE exclusion_reason = 'NOT_OWN';
ALTER TABLE git_commit DROP CONSTRAINT git_commit_exclusion_reason_check;
ALTER TABLE git_commit ADD CONSTRAINT git_commit_exclusion_reason_check
    CHECK (exclusion_reason IS NULL OR exclusion_reason IN ('BOT', 'MERGE_COMMIT', 'LOCKFILE_ONLY'));

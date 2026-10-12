-- 후보 상태·종류를 화면 값(NEW·USED·EXCLUDED, MANUAL 추가)에 맞추고, AI 가 묶은 후보의 ref·PR·이슈 번호 목록과 화면에 보일 숫자(meta)·커밋 표시 경로를 담을 칸을 더한다.
-- 저장소 연결마다 마지막으로 묶은 시각과 그 입력의 지문·묶기 버전을 candidate_grouping 에 두어, 입력이 같으면 다시 묶지 않고 아직 못 묶은 저장소를 가린다.

ALTER TABLE candidate DROP CONSTRAINT candidate_status_check;
-- 사용자가 확정했던 후보는 NEW 가 되어도 손댄 후보로 남기려고 손댄 표시를 켠다.
UPDATE candidate SET is_user_modified = TRUE WHERE status = 'CONFIRMED';
UPDATE candidate SET status = 'NEW' WHERE status IN ('PROPOSED', 'CONFIRMED');
ALTER TABLE candidate ALTER COLUMN status SET DEFAULT 'NEW';
ALTER TABLE candidate ADD CONSTRAINT candidate_status_check
    CHECK (status IN ('NEW', 'USED', 'EXCLUDED'));

ALTER TABLE candidate DROP CONSTRAINT candidate_source_type_check;
ALTER TABLE candidate ADD CONSTRAINT candidate_source_type_check
    CHECK (source_type IN ('PR', 'COMMIT_CLUSTER', 'MANUAL'));

ALTER TABLE candidate
    ADD COLUMN source_ref           VARCHAR(20),
    ADD COLUMN pull_request_numbers INT[]        NOT NULL DEFAULT '{}',
    ADD COLUMN issue_numbers        INT[]        NOT NULL DEFAULT '{}',
    ADD COLUMN file_count           INT          NOT NULL DEFAULT 0,
    ADD COLUMN review_comment_count INT          NOT NULL DEFAULT 0,
    ADD COLUMN span_days            INT,
    ADD COLUMN code_ratio           DECIMAL(5,4);

-- 화면이 커밋마다 경로 글자를 받아서, 바뀐 파일 정보가 없는 커밋은 NULL 대신 빈 글자로 둔다.
ALTER TABLE candidate_commit ADD COLUMN display_path TEXT NOT NULL DEFAULT '';

CREATE TABLE candidate_grouping (
    user_repository_id BIGINT      PRIMARY KEY REFERENCES user_repository (id) ON DELETE CASCADE,
    input_fingerprint  VARCHAR(64) NOT NULL,
    grouping_version   INT         NOT NULL,
    grouped_at         TIMESTAMPTZ NOT NULL
);

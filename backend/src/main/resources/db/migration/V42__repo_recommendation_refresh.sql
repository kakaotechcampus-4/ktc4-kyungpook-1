-- 확정 카드가 바뀐 레포는 '다시 계산 필요' 시각만 남기고, 배경 작업이 1분 동안 더 바뀌지 않으면 그때 한 번 다시 계산한다.
-- 처음 계산하기 전에도 줄이 생기므로 계산 시각은 비어 있을 수 있고, 계산을 기다리거나 하는 동안은 COMPUTING 이다.

ALTER TABLE repo_recommendation
    ADD COLUMN user_id     BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    ADD COLUMN stale_since TIMESTAMPTZ,
    ALTER COLUMN computed_at DROP NOT NULL,
    DROP CONSTRAINT repo_recommendation_status_check,
    ADD CONSTRAINT repo_recommendation_status_check CHECK (status IN ('COMPUTING', 'READY', 'FAILED'));

CREATE INDEX repo_recommendation_stale_idx ON repo_recommendation (stale_since) WHERE stale_since IS NOT NULL;

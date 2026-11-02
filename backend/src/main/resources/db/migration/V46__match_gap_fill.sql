-- 공고 상세에서 못 채운 조건마다 AI 가 찾은 제안으로 (사용자, 공고)마다 한 줄이며, 찾을 때의 자료 지문이 같으면 다시 찾지 않는다.
-- 찾기를 시작한 시각이 오래된 RUNNING 은 서버가 찾는 중에 다시 시작된 것으로 보고 다음 요청이 다시 찾는다.

CREATE TABLE match_gap_fill (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    job_posting_id BIGINT      NOT NULL REFERENCES job_posting (id) ON DELETE CASCADE,
    status         VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'DONE', 'FAILED')),
    conditions     JSONB       NOT NULL DEFAULT '[]',
    fingerprint    VARCHAR(64),
    requested_at   TIMESTAMPTZ NOT NULL,
    finished_at    TIMESTAMPTZ,
    UNIQUE (user_id, job_posting_id)
);

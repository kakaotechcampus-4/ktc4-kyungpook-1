-- 공고 페이지 '내 조건으로 매칭하기' 의 결과로 사용자마다 한 줄이며, 계산할 때의 입력 지문(확정 카드 글·인적사항 저장 시각)을 남긴다.
-- 계산을 시작한 시각이 오래된 COMPUTING 은 서버가 계산 중에 다시 시작된 것으로 보고 다음 요청이 다시 계산한다.

CREATE TABLE overall_recommendation (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT      NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    status         VARCHAR(20) NOT NULL CHECK (status IN ('COMPUTING', 'READY', 'FAILED')),
    companies      JSONB       NOT NULL DEFAULT '[]',
    fingerprint    VARCHAR(64),
    progress_done  INT         NOT NULL DEFAULT 0,
    progress_total INT         NOT NULL DEFAULT 0,
    requested_at   TIMESTAMPTZ NOT NULL,
    computed_at    TIMESTAMPTZ
);

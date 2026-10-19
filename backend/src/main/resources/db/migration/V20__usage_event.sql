-- V2 가 보류한 지표 표를 usage_event 로 연다.
-- 화면이 보낸 사용 지표를 로그인한 사용자 기준으로 쌓고, 퍼널 숫자는 SQL 로 센다.

CREATE TABLE usage_event (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        VARCHAR(40)  NOT NULL,
    props       JSONB        NOT NULL DEFAULT '{}',
    path        VARCHAR(255) NOT NULL,
    client_at   TIMESTAMPTZ  NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_usage_user_time ON usage_event (user_id, received_at);

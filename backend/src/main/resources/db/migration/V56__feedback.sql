-- 사용자가 화면에서 보낸 문제 신고로, 글은 우리 DB 에만 두고 팀 알림에는 번호만 보낸다
-- 계정을 지우면 신고도 함께 지우려고 user_id 에 ON DELETE CASCADE 를 둔다
CREATE TABLE feedback (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    message    TEXT        NOT NULL,
    path       VARCHAR(255),
    user_agent VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL
);

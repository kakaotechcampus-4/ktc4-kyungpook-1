-- 서버가 사용자에게 남기는 알림 한 줄이며, 레포 이름과 카드 제목은 남긴 순간의 값을 payload 에 그대로 담는다
-- 목록은 사용자별 최근 순으로 읽으므로 (user_id, created_at DESC) 인덱스를 둔다
-- posting_alert_run 은 새 공고를 어디까지 확인했는지 한 줄만 남긴다
CREATE TABLE notification (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id  UUID        NOT NULL UNIQUE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind       VARCHAR(30) NOT NULL CHECK (kind IN ('CARDS_DRAFTED', 'REPO_REFRESHED', 'ANALYSIS_FAILED', 'POSTINGS_MATCHED')),
    payload    JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    read_at    TIMESTAMPTZ
);

CREATE INDEX idx_notification_user ON notification (user_id, created_at DESC);

CREATE TABLE posting_alert_run (
    id              INT         PRIMARY KEY CHECK (id = 1),
    checked_through TIMESTAMPTZ NOT NULL
);

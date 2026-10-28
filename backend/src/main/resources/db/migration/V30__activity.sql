-- 레포 밖 경험(동아리·대외활동 등)을 묶는 활동이고, 활동을 지우면 안의 카드도 DB 가 함께 지운다.
-- 기간은 달 단위라 그달 1일로 저장한다.
CREATE TABLE activity (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id       UUID         NOT NULL UNIQUE,
    user_id         BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name            VARCHAR(50)  NOT NULL,
    activity_type   VARCHAR(20)  NOT NULL CHECK (activity_type IN ('CLUB', 'EXTERNAL', 'CONTEST', 'INTERNSHIP',
                                                                    'EDUCATION', 'PART_TIME', 'VOLUNTEER', 'STUDY',
                                                                    'OTHER')),
    start_month     DATE         NOT NULL CHECK (EXTRACT(DAY FROM start_month) = 1),
    end_month       DATE         CHECK (EXTRACT(DAY FROM end_month) = 1),
    role            VARCHAR(50),
    idempotency_key TEXT,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT activity_period CHECK (end_month IS NULL OR end_month >= start_month)
);
CREATE INDEX idx_activity_user ON activity (user_id, start_month DESC);
CREATE UNIQUE INDEX uq_activity_idempotency ON activity (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

ALTER TABLE card ADD COLUMN activity_id BIGINT REFERENCES activity (id) ON DELETE CASCADE;
ALTER TABLE card ADD CONSTRAINT card_repo_or_activity CHECK (user_repository_id IS NULL OR activity_id IS NULL);
CREATE INDEX idx_card_activity ON card (activity_id) WHERE activity_id IS NOT NULL;

-- 홈 한 줄 명령 실행 한 건으로, 화면에 보일 줄과 AI 에 되돌려 보낼 걸음을 items 한 곳에 둔다
-- 워커가 집은 실행은 running_since 가 차 있고, 걸음을 저장할 때마다 updated_at 이 바뀌어 멈춘 실행을 가려낸다
CREATE TABLE assistant_run (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id     UUID        NOT NULL UNIQUE,
    user_id       BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    state         VARCHAR(20) NOT NULL CHECK (state IN ('RUNNING', 'WAITING_USER', 'DONE', 'FAILED')),
    step_limit    INT         NOT NULL,
    steps_used    INT         NOT NULL DEFAULT 0,
    repos         JSONB       NOT NULL DEFAULT '[]',
    items         JSONB       NOT NULL DEFAULT '[]',
    error_code    VARCHAR(40),
    running_since TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT assistant_run_failed CHECK ((state = 'FAILED') = (error_code IS NOT NULL))
);
CREATE INDEX idx_assistant_run_user ON assistant_run (user_id, created_at DESC);
CREATE INDEX idx_assistant_run_waiting ON assistant_run (updated_at) WHERE state = 'RUNNING';

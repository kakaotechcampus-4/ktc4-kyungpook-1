-- 자소서 한 편과 그 단계 상태다. 줄 서 있음과 도는 중은 단계 이름을 나누지 않고 running_since(워커가 집은 시각)로 가른다.
-- AI 가 한 일은 화면이 통째로 읽는 값이라 JSONB 로 둔다.
CREATE TABLE cover_letter (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id       UUID         NOT NULL UNIQUE,
    user_id         BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    job_role        VARCHAR(100) NOT NULL,
    memo            TEXT,
    stage           VARCHAR(20)  NOT NULL CHECK (stage IN ('ASSIGNING', 'ASSIGNED', 'ASKING', 'ASKED', 'DRAFTING',
                                                           'DRAFTED', 'FAILED')),
    running_since   TIMESTAMPTZ,
    failed_step     VARCHAR(10)  CHECK (failed_step IN ('ASSIGN', 'ASK', 'DRAFT')),
    error_code      VARCHAR(40),
    assign_steps    JSONB        NOT NULL DEFAULT '[]',
    draft_steps     JSONB        NOT NULL DEFAULT '[]',
    idempotency_key TEXT,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT cover_letter_failed CHECK ((stage = 'FAILED') = (failed_step IS NOT NULL))
);
CREATE INDEX idx_cover_letter_user ON cover_letter (user_id, created_at DESC);
CREATE INDEX idx_cover_letter_waiting ON cover_letter (updated_at) WHERE stage IN ('ASSIGNING', 'ASKING', 'DRAFTING');
CREATE UNIQUE INDEX uq_cover_letter_idempotency ON cover_letter (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- 문항마다 배정 카드·질문과 답·초안을 둔다. 카드를 지워도 자소서는 남고 그 자리만 비운다.
-- 질문·답·초안 문장은 문항마다 통째로 저장하고 읽어서 JSONB 다.
CREATE TABLE cover_letter_question (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id          UUID        NOT NULL UNIQUE,
    cover_letter_id    BIGINT      NOT NULL REFERENCES cover_letter (id) ON DELETE CASCADE,
    seq                SMALLINT    NOT NULL,
    text               TEXT        NOT NULL,
    char_limit         INT         NOT NULL CHECK (char_limit BETWEEN 100 AND 5000),
    theme              VARCHAR(20),
    primary_card_id    BIGINT      REFERENCES card (id) ON DELETE SET NULL,
    supporting_card_id BIGINT      REFERENCES card (id) ON DELETE SET NULL,
    assigned_by        VARCHAR(20),
    pick_reason        TEXT,
    considered         JSONB       NOT NULL DEFAULT '[]',
    asks               JSONB       NOT NULL DEFAULT '[]',
    answers            JSONB       NOT NULL DEFAULT '[]',
    ai_text            TEXT,
    draft_text         TEXT,
    edited             BOOLEAN     NOT NULL DEFAULT FALSE,
    char_count         INT,
    over_by            INT,
    sentences          JSONB       NOT NULL DEFAULT '[]',
    marks              JSONB       NOT NULL DEFAULT '[]',
    blanks             JSONB       NOT NULL DEFAULT '[]',
    draft_source       VARCHAR(10),
    UNIQUE (cover_letter_id, seq)
);
CREATE INDEX idx_cover_letter_question_primary ON cover_letter_question (primary_card_id)
    WHERE primary_card_id IS NOT NULL;
CREATE INDEX idx_cover_letter_question_supporting ON cover_letter_question (supporting_card_id)
    WHERE supporting_card_id IS NOT NULL;

-- 자소서 질문에서 받은 답 중 경험 자체에 대한 것(상황·행동·결과·배운 점·계기)을 카드마다 기억해 다음 자소서에 다시 쓴다.
-- 직무 연결·지원 이유는 회사마다 달라 기억하지 않는다.
CREATE TABLE card_material_answer (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    card_id    BIGINT      NOT NULL REFERENCES card (id) ON DELETE CASCADE,
    item       VARCHAR(20) NOT NULL CHECK (item IN ('SITUATION', 'ACTION', 'RESULT', 'LEARNING', 'TRIGGER')),
    text       TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (card_id, item)
);

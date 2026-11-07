-- 매칭 2단계 AI 판단을 (카드, 카드 내용 해시, 공고 조건)마다 남겨 레포 추천·전체 추천·공고 상세가 같이 쓰고, 카드 글이 그대로면 다시 묻지 않는다.
-- 레포 추천은 화면이 저장본만 읽도록 레포마다 한 줄로 두어, 새 공고가 들어와도 다시 계산하기 전까지 바뀌지 않는다.

CREATE TABLE match_judgement (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    card_id      BIGINT       NOT NULL REFERENCES card (id) ON DELETE CASCADE,
    card_hash    CHAR(64)     NOT NULL,
    criterion_id BIGINT       NOT NULL REFERENCES job_posting_criterion (id) ON DELETE CASCADE,
    met          BOOLEAN      NOT NULL,
    reason       VARCHAR(200),
    judged_at    TIMESTAMPTZ  NOT NULL,
    UNIQUE (card_id, card_hash, criterion_id),
    CHECK (met OR reason IS NULL)
);

CREATE INDEX match_judgement_criterion_idx ON match_judgement (criterion_id);

CREATE TABLE repo_recommendation (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_repository_id BIGINT      NOT NULL UNIQUE REFERENCES user_repository (id) ON DELETE CASCADE,
    status             VARCHAR(20) NOT NULL CHECK (status IN ('READY', 'FAILED')),
    companies          JSONB       NOT NULL DEFAULT '[]',
    computed_at        TIMESTAMPTZ NOT NULL
);

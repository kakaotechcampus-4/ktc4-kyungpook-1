-- 서버가 정리한 레포를 스스로 다시 읽은 일 한 건을 한 줄로 남겨, 분석과 카드 초안이 끝나면 화면이 알릴 것으로 읽게 한다
-- 레포 추천은 다시 고를 때 바뀌기 전 회사를 previous_companies 에 남겨 새로 든 회사를 가린다

CREATE TABLE repo_refresh (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id              BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_repository_id   BIGINT      NOT NULL REFERENCES user_repository (id) ON DELETE CASCADE,
    analysis_job_id      BIGINT      NOT NULL UNIQUE REFERENCES analysis_job (id) ON DELETE CASCADE,
    draft_job_id         BIGINT      UNIQUE REFERENCES analysis_job (id) ON DELETE CASCADE,
    previous_analyzed_at TIMESTAMPTZ NOT NULL,
    card_count           INT         NOT NULL DEFAULT 0 CHECK (card_count >= 0),
    state                VARCHAR(20) NOT NULL CHECK (state IN ('READING', 'DRAFTING', 'READY', 'CLOSED')),
    created_at           TIMESTAMPTZ NOT NULL,
    finished_at          TIMESTAMPTZ,
    seen_at              TIMESTAMPTZ,
    CONSTRAINT repo_refresh_draft_job CHECK (state NOT IN ('DRAFTING', 'READY') OR draft_job_id IS NOT NULL),
    CONSTRAINT repo_refresh_finished CHECK ((finished_at IS NULL) = (state IN ('READING', 'DRAFTING'))),
    CONSTRAINT repo_refresh_seen CHECK (seen_at IS NULL OR state = 'READY')
);

CREATE INDEX repo_refresh_repository_idx ON repo_refresh (user_repository_id, created_at DESC);
CREATE INDEX repo_refresh_unseen_idx ON repo_refresh (user_id) WHERE state = 'READY' AND seen_at IS NULL;

ALTER TABLE repo_recommendation ADD COLUMN previous_companies JSONB;

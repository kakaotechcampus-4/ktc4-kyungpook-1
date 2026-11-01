-- AI 를 부를 때마다 AI 가 준 처리 기록(LLM 호출 수·토큰·끝난 방식 등)을 한 줄씩 남긴다(#468).
-- AI 기능이 늘어도 고치지 않게 경로·끝난 방식에는 CHECK 를 두지 않는다.
-- 기록은 따로 커밋하므로, 부른 쪽이 잠근 Job·카드 행에 막히지 않게 job_id·card_id 에 FK 를 두지 않는다.

CREATE TABLE ai_call (
    id               BIGSERIAL    PRIMARY KEY,
    path             VARCHAR(100) NOT NULL,
    job_id           BIGINT,
    card_id          BIGINT,
    http_status      INT,
    error_code       VARCHAR(40),
    model            VARCHAR(100),
    llm_calls        INT,
    tool_calls       INT,
    repair_rounds    INT,
    input_tokens     INT,
    output_tokens    INT,
    github_calls     INT,
    ended_by         VARCHAR(20),
    ai_processing_ms INT,
    elapsed_ms       BIGINT       NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_call_job ON ai_call (job_id);
CREATE INDEX idx_ai_call_card ON ai_call (card_id);

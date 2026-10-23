-- 기업 채용 공고, AI 가 뽑은 조건, 회사별 가져오기 기록을 담는 표 3개를 연다.
-- 공고 본문 글은 저장하지 않고 AI 가 준 해시만 content_hash 에 두며, indexed_content_hash 는 색인에 반영된 해시다.

CREATE TABLE job_posting (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id            UUID         NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    company_key          VARCHAR(50)  NOT NULL,
    external_id          VARCHAR(255) NOT NULL,
    company_name         VARCHAR(255) NOT NULL,
    title                VARCHAR(255) NOT NULL,
    source_url           TEXT         NOT NULL,
    -- 조건을 뽑기 전에는 NULL 이다.
    category             VARCHAR(20)
        CHECK (category IN ('BACKEND', 'FRONTEND', 'MOBILE', 'DEVOPS', 'DATA', 'AI_ML', 'SECURITY', 'QA', 'ETC')),
    -- 뽑기 전에도 UNSPECIFIED 이고, 뽑았는지는 extracted_at 으로 본다.
    experience_level     VARCHAR(20)  NOT NULL DEFAULT 'UNSPECIFIED'
        CHECK (experience_level IN ('ENTRY', 'EXPERIENCED', 'UNSPECIFIED')),
    experience_min_years INT,
    experience_quote     VARCHAR(1000),
    status               VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'EXPIRED', 'REMOVED')),
    content_status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
        CHECK (content_status IN ('PENDING', 'READY', 'METADATA_ONLY')),
    content_hash         CHAR(64),
    indexed_content_hash CHAR(64),
    source_updated_at    TIMESTAMPTZ,
    expires_at           TIMESTAMPTZ,
    first_seen_at        TIMESTAMPTZ  NOT NULL,
    last_seen_at         TIMESTAMPTZ  NOT NULL,
    extracted_at         TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (company_key, external_id),
    -- AI 의 경력 규칙과 같다: 표시 없음은 둘 다 비고, 신입은 인용만, 경력은 연차와 인용이 함께 있다.
    CONSTRAINT job_posting_experience_check CHECK (
        (experience_level = 'UNSPECIFIED' AND experience_min_years IS NULL AND experience_quote IS NULL)
        OR (experience_level = 'ENTRY' AND experience_min_years IS NULL AND experience_quote IS NOT NULL)
        OR (experience_level = 'EXPERIENCED' AND experience_min_years IS NOT NULL
            AND experience_min_years BETWEEN 1 AND 60 AND experience_quote IS NOT NULL)
    )
);

CREATE TABLE job_posting_criterion (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id      UUID          NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    job_posting_id BIGINT        NOT NULL REFERENCES job_posting (id) ON DELETE CASCADE,
    type           VARCHAR(20)   NOT NULL CHECK (type IN ('REQUIRED', 'PREFERRED', 'RESPONSIBILITY')),
    seq            INT           NOT NULL,
    text           VARCHAR(1000) NOT NULL,
    quote          VARCHAR(4000) NOT NULL,
    UNIQUE (job_posting_id, seq)
);

CREATE TABLE job_posting_source (
    company_key     VARCHAR(50) PRIMARY KEY,
    last_attempt_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_error_code VARCHAR(30)
);

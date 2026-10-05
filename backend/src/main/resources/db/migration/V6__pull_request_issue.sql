-- AI 가 커밋과 함께 보내는 PR·이슈를 담을 테이블을 만든다.

CREATE TABLE pull_request (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    repository_id        BIGINT       NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    collection_run_id    BIGINT       REFERENCES collection_run (id) ON DELETE SET NULL,
    github_pr_number     INT          NOT NULL,
    title                VARCHAR(256) NOT NULL,
    -- AI 가 1,000자로 잘라 보내는 본문. 되묻기 질문이 인용하는 값이다.
    body_excerpt         TEXT,
    -- GitHub 은 머지된 PR 도 CLOSED 로 준다. 머지 여부는 merged_at 으로 판단한다.
    state                VARCHAR(10)  NOT NULL CHECK (state IN ('OPEN', 'CLOSED')),
    author_login         VARCHAR(39),
    base_branch          VARCHAR(255) NOT NULL,
    head_branch          VARCHAR(255) NOT NULL,
    opened_at            TIMESTAMPTZ  NOT NULL,
    merged_at            TIMESTAMPTZ,
    commit_shas          TEXT[]       NOT NULL DEFAULT '{}',
    linked_issue_numbers INT[]        NOT NULL DEFAULT '{}',
    UNIQUE (repository_id, github_pr_number)
);

CREATE TABLE issue (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    repository_id       BIGINT       NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    collection_run_id   BIGINT       REFERENCES collection_run (id) ON DELETE SET NULL,
    github_issue_number INT          NOT NULL,
    title               VARCHAR(256) NOT NULL,
    body_excerpt        TEXT,
    state               VARCHAR(10)  NOT NULL CHECK (state IN ('OPEN', 'CLOSED')),
    author_login        VARCHAR(39),
    labels              TEXT[]       NOT NULL DEFAULT '{}',
    opened_at           TIMESTAMPTZ  NOT NULL,
    closed_at           TIMESTAMPTZ,
    UNIQUE (repository_id, github_issue_number)
);

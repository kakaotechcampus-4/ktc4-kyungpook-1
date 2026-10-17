-- AI 가 PR 과 함께 보내는 리뷰와 줄 댓글을 담을 테이블을 만든다.
-- GitHub id 는 INT 를 넘어 BIGINT 이고, 이 id 의 유니크에 ON CONFLICT 로 저장해 같은 PR 을 두 Job 이 함께 저장해도 부딪히지 않는다.

CREATE TABLE pr_review (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    repository_id     BIGINT      NOT NULL,
    github_pr_number  INT         NOT NULL,
    collection_run_id BIGINT      REFERENCES collection_run (id) ON DELETE SET NULL,
    github_review_id  BIGINT      NOT NULL UNIQUE,
    -- 제출하지 않은 PENDING 리뷰는 작성자에게만 보이는 초안이라 저장하지 않는다.
    state             VARCHAR(20) NOT NULL
        CHECK (state IN ('APPROVED', 'CHANGES_REQUESTED', 'COMMENTED', 'DISMISSED')),
    author_login      VARCHAR(39),
    body_excerpt      TEXT,
    -- AI 계약에서 제출 시각은 비어 올 수 있다.
    submitted_at      TIMESTAMPTZ,
    FOREIGN KEY (repository_id, github_pr_number)
        REFERENCES pull_request (repository_id, github_pr_number) ON DELETE CASCADE
);
CREATE INDEX idx_pr_review_pr ON pr_review (repository_id, github_pr_number);

CREATE TABLE pr_review_comment (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    repository_id     BIGINT      NOT NULL,
    github_pr_number  INT         NOT NULL,
    collection_run_id BIGINT      REFERENCES collection_run (id) ON DELETE SET NULL,
    github_comment_id BIGINT      NOT NULL UNIQUE,
    -- 상태를 몰라 건너뛴 리뷰의 댓글도 남기려고 pr_review 를 참조하지 않는다.
    github_review_id  BIGINT,
    author_login      VARCHAR(39),
    body_excerpt      TEXT        NOT NULL,
    path              TEXT,
    line              INT,
    commented_at      TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (repository_id, github_pr_number)
        REFERENCES pull_request (repository_id, github_pr_number) ON DELETE CASCADE
);
CREATE INDEX idx_pr_review_comment_pr ON pr_review_comment (repository_id, github_pr_number);

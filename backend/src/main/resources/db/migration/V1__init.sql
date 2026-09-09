-- Gitory 초기 스키마
--
-- 팀 ERD(2026-09-09)를 기준으로 삼는다. 테이블명·컬럼명·타입·BIGINT PK 는 ERD 를 그대로 따랐다.
-- 스펙(gitory-docs)이 요구하는데 ERD 에 없던 네 가지만 더했다 —
--   github_connection(동의·토큰) · collection_run(스냅샷) · analysis_job(비동기) · audit_event(감사)
-- 무엇을 왜 더했는지는 각 테이블 주석에 적었다.
--
-- 스키마의 단일 출처는 이 파일이고 JPA 는 validate 만 한다(ddl-auto=validate).
-- 이미 머지된 마이그레이션은 고치지 않는다 — 새 V<n>__*.sql 을 추가한다.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ════════════════════════════════════════════════════ 사용자 · 연결

-- ERD 그대로. 단 user_token_enc 만 github_connection 으로 옮겼다(아래 주석 참고).
CREATE TABLE users (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    github_user_id BIGINT      NOT NULL UNIQUE,
    github_login   VARCHAR(39) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at  TIMESTAMPTZ,
    -- 소프트 삭제. 스펙은 계정 삭제 요청 시 서비스 데이터에서 7일 안에 실제 삭제를
    -- 요구하므로, 이 컬럼은 '삭제 요청 시각'이고 실제 삭제 배치가 따로 돈다.
    deleted_at     TIMESTAMPTZ
);

-- ★ ERD 에 없던 테이블. users.user_token_enc 를 여기로 옮겼다.
--
-- 두 가지 이유다.
--   1) 스펙의 '연결 동의' 엔터티가 빠져 있었다. 어떤 범위에 동의했는지 · 언제 철회했는지가
--      기록돼야 "연결 범위 확인 / 연결 해제" 사용자 통제권(security-privacy.md)을 만들 수 있다.
--   2) 토큰을 users 행에 두면 사용자 프로필을 읽는 모든 쿼리가 토큰을 함께 읽는다.
--      분리하면 consent 모듈만 이 테이블을 보고, 나머지는 users 만 본다.
--
-- ⚠️ 스펙은 "장기 토큰을 별도 데이터베이스에 평문 저장하지 않는다"고 못 박았다.
--    token_enc 는 반드시 암호화된 값이고, 키는 배포 환경의 비밀 관리에서 주입한다.
--    토큰이 만료·철회되면 값을 NULL 로 지우고 revoked_at 을 채운다.
CREATE TABLE github_connection (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    scopes       TEXT[]      NOT NULL,          -- 최소 권한만: read:user, public_repo
    token_enc    TEXT,                          -- 암호화된 액세스 토큰. 철회 시 NULL
    token_expires_at TIMESTAMPTZ,
    granted_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at   TIMESTAMPTZ,
    CONSTRAINT connection_revoked_after_granted
        CHECK (revoked_at IS NULL OR revoked_at >= granted_at),
    -- 철회된 연결은 토큰을 들고 있을 수 없다.
    CONSTRAINT connection_revoked_has_no_token
        CHECK (revoked_at IS NULL OR token_enc IS NULL)
);
CREATE UNIQUE INDEX uq_connection_active
    ON github_connection (user_id) WHERE revoked_at IS NULL;

-- ════════════════════════════════════════════════════ 저장소

-- ERD 그대로.
CREATE TABLE repository (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    github_repo_id    BIGINT       NOT NULL UNIQUE,   -- git 이 repo 를 식별하는 번호
    owner_login       VARCHAR(39)  NOT NULL,          -- ex) grow22/gitory -> grow22
    name              VARCHAR(100) NOT NULL,          -- ex) grow22/gitory -> gitory
    visibility        VARCHAR(10)  NOT NULL CHECK (visibility IN ('public', 'private')),
    primary_language  VARCHAR(40),
    default_branch    VARCHAR(255),
    -- 마지막 수집 시점. 재분석 시 이 시각 이후만 읽어 토큰 비용을 줄인다(ERD 의도).
    last_collected_at TIMESTAMPTZ,
    UNIQUE (owner_login, name)
);

-- ERD 그대로 + public_id 추가.
--
-- ★ public_id 를 더한 이유: 스펙이 "내부 엔터티는 예측하기 어려운 내부 식별자를 사용하고
--   외부 서비스 ID 를 직접 노출하지 않는다"고 요구한다. 순차 BIGINT 를 URL 에 그대로 쓰면
--   /api/repositories/1, 2, 3 … 으로 남의 자원 존재 여부를 훑을 수 있다.
--   BIGINT 는 조인·저장에 그대로 쓰고(ERD 선택이 맞다), URL 에는 public_id 만 나간다.
CREATE TABLE user_repository (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id                UUID        NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    user_id                  BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    repository_id            BIGINT      NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    status                   VARCHAR(20) NOT NULL DEFAULT 'CONNECTED'
                             CHECK (status IN ('CONNECTED', 'ANALYZING', 'ANALYZED', 'FAILED')),
    failure_reason           VARCHAR(30),
    -- 전체 커밋 수 / 그중 내 커밋 수. 프론트의 myCommitCount·teamCommitCount 가 여기서 나온다.
    -- ⚠️ 세는 법에 함정이 셋 있다 (실측) —
    --    ① 기본 브랜치만 읽으면 기여가 사라진다 (main 기준 0개 / 본인 브랜치 기준 26개)
    --       -> collection_run.branches 로 어떤 브랜치를 읽었는지 남긴다
    --    ② 봇·머지 커밋을 빼야 한다 -> git_commit.is_excluded
    --    ③ 로그인 대소문자를 정규화해야 한다. 안 하면 own_* 이 조용히 0이 된다
    commit_count             INT         NOT NULL DEFAULT 0,
    own_commit_count         INT         NOT NULL DEFAULT 0,
    pr_count                 INT         NOT NULL DEFAULT 0,
    own_pr_count             INT         NOT NULL DEFAULT 0,
    review_comment_count     INT         NOT NULL DEFAULT 0,
    own_review_comment_count INT         NOT NULL DEFAULT 0,
    -- 정성 카드 4문항(협업/갈등/문제해결/리더십)의 근거 밀도 등급.
    -- ⚠️ 이 등급은 화면 배지·정렬용이고 카드 문장에 넣지 않는다.
    --    "92% 기여했습니다" 류의 수치화된 자기 주장을 만들지 않기로 한 것과 같은 이유다.
    fit_collaboration        CHAR(1) CHECK (fit_collaboration    IN ('A','B','C','D')),
    fit_conflict             CHAR(1) CHECK (fit_conflict         IN ('A','B','C','D')),
    fit_problem_solving      CHAR(1) CHECK (fit_problem_solving  IN ('A','B','C','D')),
    fit_leadership           CHAR(1) CHECK (fit_leadership       IN ('A','B','C','D')),
    connected_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_analyzed_at         TIMESTAMPTZ,
    UNIQUE (user_id, repository_id),
    -- 내 커밋이 전체보다 많을 수 없다. 집계 로직이 틀리면 여기서 걸린다.
    CONSTRAINT own_counts_within_total CHECK (
        own_commit_count <= commit_count
        AND own_pr_count <= pr_count
        AND own_review_comment_count <= review_comment_count)
);
CREATE INDEX idx_user_repository_user ON user_repository (user_id, connected_at DESC);

-- ★ ERD 에 없던 테이블. repository.last_collected_at 의 의도를 테이블로 편 것이다.
--
-- 세 가지가 필요해서 만들었다.
--   1) 스냅샷 — 스펙: "분석 결과는 분석 범위의 스냅샷에 연결한다. 이후 저장소 활동이
--      바뀌어도 생성 시점의 근거를 설명할 수 있어야 한다." 카드가 3개월 뒤에도
--      "그때 그 커밋으로 만들었다"를 말할 수 있어야 한다.
--   2) partial — 1차 상한(커밋 1,000 · PR 50 · Issue 50)을 넘겼을 때 버리지 않고 표시한다.
--   3) branches — 어떤 브랜치를 읽었는지. 위의 함정 ①이 재현되지 않게 기록으로 남긴다.
CREATE TABLE collection_run (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_repository_id BIGINT      NOT NULL REFERENCES user_repository (id) ON DELETE CASCADE,
    branches           TEXT[]      NOT NULL DEFAULT '{}',
    -- 증분 수집 시작점. NULL 이면 전체 수집이다.
    since              TIMESTAMPTZ,
    head_sha           CHAR(40),
    commits_collected  INT         NOT NULL DEFAULT 0,
    prs_collected      INT         NOT NULL DEFAULT 0,
    issues_collected   INT         NOT NULL DEFAULT 0,
    partial            BOOLEAN     NOT NULL DEFAULT FALSE,
    partial_reason     VARCHAR(40),        -- LIMIT_EXCEEDED · RATE_LIMITED · …
    collected_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT partial_has_reason CHECK (partial = (partial_reason IS NOT NULL))
);
CREATE INDEX idx_collection_run_repo ON collection_run (user_repository_id, collected_at DESC);

-- ════════════════════════════════════════════════════ 커밋

-- ERD 그대로 + author_name · collection_run_id 추가.
CREATE TABLE git_commit (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    repository_id    BIGINT      NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    -- ★ 어느 수집에서 들어왔는지. 스냅샷이 성립하려면 필요하다.
    collection_run_id BIGINT     REFERENCES collection_run (id) ON DELETE SET NULL,
    sha              CHAR(40)    NOT NULL,
    author_login     VARCHAR(39),
    -- ★ ERD 에 없던 컬럼. GitHub 계정과 연결되지 않은 커밋은 author_login 이 NULL 이고
    --   커밋 author name 만 남는다. 폴백이 없으면 그 커밋의 기여가 통째로 사라진다.
    --   (실측 도구에서 login -> name 폴백으로 처리했던 지점이다.)
    author_name      VARCHAR(255),
    message          TEXT,
    authored_at      TIMESTAMPTZ,
    additions        INT,
    deletions        INT,
    changed_files    INT,
    -- 부모 커밋 수가 2 이상이면 머지 커밋이다.
    parent_count     SMALLINT    NOT NULL DEFAULT 1,
    -- 분석 단계에서 배제할지. ERD 주석의 "is_excluded 를 채우는 로직"이 이 컬럼이다.
    is_excluded      BOOLEAN     NOT NULL DEFAULT FALSE,
    exclusion_reason VARCHAR(20) CHECK (exclusion_reason IN ('MERGE', 'BOT', 'VENDORED', 'TOO_LARGE')),
    UNIQUE (repository_id, sha),
    CONSTRAINT excluded_has_reason CHECK (is_excluded = (exclusion_reason IS NOT NULL))
);
CREATE INDEX idx_commit_repo_author ON git_commit (repository_id, author_login)
    WHERE is_excluded = FALSE;
CREATE INDEX idx_commit_repo_time ON git_commit (repository_id, authored_at DESC);

-- ════════════════════════════════════════════════════ 후보

-- ERD 그대로 + public_id.
CREATE TABLE candidate (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id          UUID          NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    user_repository_id BIGINT        NOT NULL REFERENCES user_repository (id) ON DELETE CASCADE,
    -- pr: 정상적으로 PR 이 있는 경우 / commit_cluster: main 에 직접 커밋만 있는 경우
    source_type        VARCHAR(15)   NOT NULL CHECK (source_type IN ('pr', 'commit_cluster')),
    github_pr_number   INT,
    title              VARCHAR(200)  NOT NULL,   -- ex) "이메일 발송", "서버 구축"
    -- ⚠️ NOT NULL 이 곧 규칙이다 — 추천 이유를 쓸 수 없는 후보는 화면에 올리지 않는다.
    reason_text        VARCHAR(300)  NOT NULL,
    tech_tags          VARCHAR(200),             -- ex) "Java,Spring,Jwt"
    score              DECIMAL(5,4),             -- 랭킹 점수
    status             VARCHAR(10)   NOT NULL DEFAULT 'proposed'
                       CHECK (status IN ('proposed', 'confirmed', 'used', 'excluded')),
    -- 사용자가 추천 후보를 추가·제외했는지
    is_user_modified   BOOLEAN       NOT NULL DEFAULT FALSE,
    -- ★ ERD 에 없던 컬럼. 프론트의 "카드감 낮음" 배지(lowCardWorth).
    --   규칙 판정이다 — 문서·설정 전용 변경, 변경량 임계 미만, 근거 커밋 1건.
    --   전부 정수 비교이므로 모델을 부르지 않는다.
    low_card_worth     BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- pr 후보는 PR 번호가 있어야 하고, commit_cluster 는 없어야 한다.
    CONSTRAINT candidate_pr_number_matches_type
        CHECK ((source_type = 'pr') = (github_pr_number IS NOT NULL))
);
CREATE INDEX idx_candidate_repo_status ON candidate (user_repository_id, status, score DESC);

-- ERD 그대로. 후보 하나에 어떤 커밋이 들어갔는지.
CREATE TABLE candidate_commit (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    candidate_id BIGINT      NOT NULL REFERENCES candidate (id) ON DELETE CASCADE,
    commit_id    BIGINT      NOT NULL REFERENCES git_commit (id) ON DELETE CASCADE,
    -- ai: AI 가 처음에 넣은 커밋 / user: 사용자가 직접 넣은 커밋
    origin       VARCHAR(10) NOT NULL CHECK (origin IN ('ai', 'user')),
    -- 사용자가 뺐어도 행을 지우지 않고 false 로만 바꾼다(ERD 의도).
    -- 되돌리기와 "왜 빠졌는지"가 남는다.
    is_included  BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (candidate_id, commit_id)
);

-- ════════════════════════════════════════════════════ 카드

-- ERD 그대로 + public_id · current_version.
CREATE TABLE card (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id          UUID         NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    -- user_id 는 user_repository 로 유도 가능하지만 목록 조회 경로를 짧게 하려고 둔다(ERD 선택).
    user_id            BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_repository_id BIGINT       REFERENCES user_repository (id) ON DELETE SET NULL,
    -- 직접 입력 카드(POST /api/cards/manual)는 후보가 없다.
    candidate_id       BIGINT       REFERENCES candidate (id) ON DELETE SET NULL,
    card_type          VARCHAR(12)  NOT NULL CHECK (card_type IN ('tech', 'qualitative')),
    origin             VARCHAR(20)  NOT NULL DEFAULT 'ai' CHECK (origin IN ('ai', 'manual')),
    -- 정성 카드가 어느 주제에서 시작했는지 (갈등/협업/문제해결/리더십)
    theme              VARCHAR(20)  CHECK (theme IN ('collaboration', 'conflict', 'problem_solving', 'leadership')),
    title              VARCHAR(200) NOT NULL,
    status             VARCHAR(10)  NOT NULL DEFAULT 'draft'
                       CHECK (status IN ('draft', 'confirmed')),
    -- ★ ERD 에 없던 컬럼. 프론트 계약의 GET /cards/{id}/versions · restore 가
    --   카드 단위 버전을 요구한다. ERD 의 version_no 는 문장 단위라 카드 단위 지시자가 없었다.
    --   카드 버전 N = 각 (star_slot, seq) 에서 version_no <= N 인 최신 행의 집합.
    current_version    SMALLINT     NOT NULL DEFAULT 1,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    confirmed_at       TIMESTAMPTZ,             -- 사용자 카드 확정 시점
    deleted_at         TIMESTAMPTZ,
    -- ADR-0004: 확인 없이 확정 카드로 저장하지 않는다. 상태와 시각이 어긋날 수 없게 잠근다.
    CONSTRAINT card_confirmed_needs_time
        CHECK ((status = 'confirmed') = (confirmed_at IS NOT NULL)),
    -- 정성 카드만 theme 을 갖는다.
    CONSTRAINT theme_only_for_qualitative
        CHECK (theme IS NULL OR card_type = 'qualitative')
);
CREATE INDEX idx_card_user_status ON card (user_id, status, created_at DESC)
    WHERE deleted_at IS NULL;

-- ERD 그대로. AI 가 던진 질문 하나와 그에 대한 답.
--
-- card_statement 가 source_turn_id 로 이 테이블을 참조하므로 먼저 만든다.
CREATE TABLE interview_turn (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    card_id        BIGINT      NOT NULL REFERENCES card (id) ON DELETE CASCADE,
    seq            SMALLINT    NOT NULL,        -- 몇 번째 질문 (다음 질문 누르면 +1)
    star_slot      CHAR(1)     NOT NULL CHECK (star_slot IN ('S', 'T', 'A', 'R')),
    -- evidence_gap: 코드에서 못 찾은 부분을 묻는다
    -- followup:     답변을 보고 부족한 부분을 이어간다
    -- recall_aid:   증거가 없을 때의 회상 질문
    question_type  VARCHAR(20) NOT NULL
                   CHECK (question_type IN ('evidence_gap', 'followup', 'recall_aid')),
    -- user: 사용자가 다시 질문 요청 / auto: 꼬리 질문 등 AI 가 필요할 때
    trigger_source VARCHAR(15) NOT NULL CHECK (trigger_source IN ('user', 'auto')),
    -- 꼬리질문이면 부모 질문을 가리킨다.
    parent_turn_id BIGINT      REFERENCES interview_turn (id) ON DELETE SET NULL,
    question_text  TEXT        NOT NULL,
    answer_text    TEXT,
    -- 사용자가 직접 타이핑했는지 / AI 추천 답변을 골랐는지
    answer_source  VARCHAR(12) CHECK (answer_source IN ('typed', 'selected')),
    -- ⚠️ 'insufficient' 다. ERD 의 'iunsufficient' 는 오타로 보고 고쳤다.
    --   later·skipped 가 있는 것이 중요하다 — 사용자가 모른다고 하면 추정으로 채우지 않고
    --   보완 필요 상태로 남긴다(스펙의 적응형 인터뷰 정책 3).
    outcome        VARCHAR(12) CHECK (outcome IN ('answered', 'later', 'skipped', 'insufficient')),
    asked_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    answered_at    TIMESTAMPTZ,
    UNIQUE (card_id, seq),
    CONSTRAINT answered_turn_has_source
        CHECK (outcome <> 'answered' OR (answer_text IS NOT NULL AND answer_source IS NOT NULL))
);
CREATE INDEX idx_turn_card ON interview_turn (card_id, seq);

-- ERD 그대로. AI 가 함께 제시한 추천 답변들. 고른 것/안 고른 것을 모두 남긴다.
CREATE TABLE interview_option (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    turn_id     BIGINT   NOT NULL REFERENCES interview_turn (id) ON DELETE CASCADE,
    seq         SMALLINT NOT NULL,
    option_text TEXT     NOT NULL,
    is_selected BOOLEAN  NOT NULL DEFAULT FALSE,
    UNIQUE (turn_id, seq)
);

-- ERD 그대로.
--
-- S/T/A/R 문장을 한 줄씩 담는다. 문장마다 근거 커밋을 붙이려고 문장 단위로 나눈 것이
-- 이 스키마에서 제일 잘 된 결정이다 — 칸 하나에 텍스트 한 덩어리를 넣으면
-- "이 문장의 근거가 뭐냐"에 답할 수 없다.
CREATE TABLE card_statement (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    card_id       BIGINT      NOT NULL REFERENCES card (id) ON DELETE CASCADE,
    -- 문장 수정 이력. 고치면 덮어쓰지 않고 +1 값으로 새 행을 추가한다(append-only).
    version_no    SMALLINT    NOT NULL DEFAULT 1,
    star_slot     CHAR(1)     NOT NULL CHECK (star_slot IN ('S', 'T', 'A', 'R')),
    seq           SMALLINT    NOT NULL,        -- 같은 칸에 문장이 여러 개일 때 순서
    body          TEXT        NOT NULL,
    -- ADR-0001 이 구현되는 자리. 문장의 출처 분류다.
    --   commit:       저장소 활동에서 직접 확인 (관측됨)
    --   user_written: 되묻기로 사용자가 직접 확인 (사용자 진술)
    --   ai_suggested: 확인 전 초안 (AI 제안)
    --   inferred:     여러 근거를 묶은 제한적 해석 (추론)
    -- ★ 'inferred' 를 더했다. 스펙의 주장 4분류 중 하나인데 ERD 에 없었다.
    evidence_type VARCHAR(12) NOT NULL
                  CHECK (evidence_type IN ('commit', 'user_written', 'ai_suggested', 'inferred')),
    confidence    VARCHAR(6)  CHECK (confidence IN ('low', 'medium', 'high')),
    -- 어떤 인터뷰 턴에서 이 문장이 완성되었는지.
    source_turn_id BIGINT     REFERENCES interview_turn (id) ON DELETE SET NULL,
    UNIQUE (card_id, version_no, star_slot, seq),
    -- 사용자 진술 문장은 어느 인터뷰에서 왔는지 밝혀야 한다. 못 밝히면 출처 없는 주장이다.
    CONSTRAINT user_written_has_turn
        CHECK (evidence_type <> 'user_written' OR source_turn_id IS NOT NULL)
);
CREATE INDEX idx_statement_card ON card_statement (card_id, star_slot, seq, version_no DESC);

-- ERD 그대로. 한 문장에 근거 커밋을 연결한다.
CREATE TABLE statement_evidence (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    statement_id BIGINT NOT NULL REFERENCES card_statement (id) ON DELETE CASCADE,
    -- ⚠️ RESTRICT 다. 근거로 쓰인 커밋은 지울 수 없다 —
    --    카드가 "이 sha 로 만들었다"를 나중에도 말할 수 있어야 한다.
    commit_id    BIGINT NOT NULL REFERENCES git_commit (id) ON DELETE RESTRICT,
    UNIQUE (statement_id, commit_id)
);
CREATE INDEX idx_statement_evidence_commit ON statement_evidence (commit_id);

-- ════════════════════════════════════════════════════ 비동기 · 감사

-- ★ ERD 에 없던 테이블. 프론트 계약(api-spec.md §3)이 요구한다.
--
-- 레포 분석은 실측에서 초안 122~139초, STAR 배치 140~315초가 걸렸다.
-- 동기 요청으로 처리할 수 없다. POST /analyze 는 jobId 만 돌려주고 프론트가 폴링한다.
CREATE TABLE analysis_job (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id          UUID        NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    user_id            BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_repository_id BIGINT      NOT NULL REFERENCES user_repository (id) ON DELETE CASCADE,
    -- 계약 원칙 3(멱등성). 재시도가 중복 분석·중복 카드를 만들지 않는다.
    idempotency_key    TEXT        NOT NULL,
    type               VARCHAR(20) NOT NULL DEFAULT 'REPO_ANALYSIS',
    state              VARCHAR(10) NOT NULL DEFAULT 'RUNNING'
                       CHECK (state IN ('RUNNING', 'DONE', 'FAILED')),
    -- 화면의 4단계 체크리스트와 1:1 — COMMITS · PR_REVIEW · COMPRESS · REASON
    steps              JSONB       NOT NULL DEFAULT '[]',
    partial            BOOLEAN     NOT NULL DEFAULT FALSE,
    collection_run_id  BIGINT      REFERENCES collection_run (id) ON DELETE SET NULL,
    -- 운영용 분류. 사용자에게 보여 줄 메시지와 분리한다(계약 원칙 4).
    error_code         VARCHAR(40),
    started_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at        TIMESTAMPTZ,
    UNIQUE (user_id, idempotency_key)
);
CREATE INDEX idx_job_running ON analysis_job (user_repository_id) WHERE state = 'RUNNING';

-- ★ ERD 에 없던 테이블. 스펙의 '감사 이벤트' 엔터티다.
--
-- ⚠️ 본문 컬럼이 없는 것이 설계다. 카드 본문·인터뷰 답변·저장소 이름을 남기지 않는다.
--    요청 식별자와 완료 상태만 남긴다(security-privacy.md 관측성).
CREATE TABLE audit_event (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    action      VARCHAR(40) NOT NULL,    -- CONNECT · REVOKE · ANALYZE · CONFIRM_CARD · DELETE_DATA …
    subject_id  BIGINT,
    outcome     VARCHAR(10) NOT NULL CHECK (outcome IN ('OK', 'DENIED', 'FAILED')),
    request_id  TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_user_time ON audit_event (user_id, occurred_at DESC);

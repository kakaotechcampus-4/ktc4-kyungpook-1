-- Gitory 스키마 — V2 까지 적용된 최종 상태 (2026-09-14)
--
-- ⚠️ 이 파일은 Flyway 가 읽지 않는다. 실행용이 아니라 대조용이다.
--    실제 스키마의 단일 출처는 여전히 db/migration/ 의 V1 + V2 다.
--
--    db/migration/V1__init.sql        팀 ERD(2026-09-09) 기준 초기 스키마
--    db/migration/V2__erd_review.sql  ERD 리뷰 13건 반영 (2026-09-14)
--    docs/schema-v2.sql               ← 이 파일. 둘을 합친 결과를 한 눈에 본다
--
-- 왜 있나 — 팀이 erdcloud·노션을 손으로 맞출 때 ALTER 문 스무 개를 머릿속에서
-- 합치게 하지 않으려고 만들었다. "v1.schema 기준으로 작성했다"고 할 때의 그 자리를
-- 이 파일이 대신한다.
--
-- ⚠️ 이 파일과 V1+V2 가 같은 스키마를 만드는지는 pg_dump 비교로 검증한다.
--    마이그레이션을 고쳤으면 이 파일도 같이 고치고 다시 비교해라. 갈라지면
--    팀이 틀린 것을 보고 ERD 를 맞추게 된다.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ════════════════════════════════════════════════════ 사용자 · 연결

CREATE TABLE users (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    github_user_id BIGINT      NOT NULL UNIQUE,
    github_login   VARCHAR(39) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at  TIMESTAMPTZ,
    -- 소프트 삭제. 스펙은 계정 삭제 요청 시 7일 안에 실제 삭제를 요구하므로,
    -- 이 컬럼은 '삭제 요청 시각'이고 실제 삭제 배치가 따로 돈다.
    deleted_at     TIMESTAMPTZ
);

-- users.user_token_enc 를 여기로 옮겼다 — 프로필을 읽는 모든 쿼리가 토큰을 함께
-- 읽지 않게, 그리고 '연결 동의'가 엔터티로 남게.
--
-- ⚠️ 스펙은 "장기 토큰을 별도 데이터베이스에 평문 저장하지 않는다"고 못 박았다.
--    token_enc 는 반드시 암호화된 값이고, 키는 배포 환경의 비밀 관리에서 주입한다.
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
    CONSTRAINT connection_revoked_has_no_token
        CHECK (revoked_at IS NULL OR token_enc IS NULL)
);
-- C-2. "user_id 당 활성 연결 1개"가 여기서 강제된다. 인가 3겹의 세 번째(동의 상태
-- 확인)가 어느 행을 볼지 모호해지지 않는다. (V1 부터 있었다)
CREATE UNIQUE INDEX uq_connection_active
    ON github_connection (user_id) WHERE revoked_at IS NULL;

-- ════════════════════════════════════════════════════ 저장소

CREATE TABLE repository (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    github_repo_id    BIGINT       NOT NULL UNIQUE,
    owner_login       VARCHAR(39)  NOT NULL,          -- ex) grow22/gitory -> grow22
    name              VARCHAR(100) NOT NULL,          -- ex) grow22/gitory -> gitory
    visibility        VARCHAR(10)  NOT NULL CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    primary_language  VARCHAR(40),
    default_branch    VARCHAR(255),
    -- 마지막 수집 시점. 재분석 시 이 시각 이후만 읽어 토큰 비용을 줄인다.
    last_collected_at TIMESTAMPTZ,
    UNIQUE (owner_login, name)
);

-- public_id: 순차 BIGINT 를 URL 에 쓰면 남의 자원 존재 여부를 훑을 수 있다.
-- BIGINT 는 조인·저장에 그대로 쓰고, URL 에는 public_id 만 나간다.
CREATE TABLE user_repository (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id                UUID        NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    user_id                  BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    repository_id            BIGINT      NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    status                   VARCHAR(20) NOT NULL DEFAULT 'CONNECTED'
                             CHECK (status IN ('CONNECTED', 'ANALYZING', 'ANALYZED', 'FAILED')),
    failure_reason           VARCHAR(30),
    -- ⚠️ 세는 법에 함정이 셋 있다 (실측) —
    --    ① 기본 브랜치만 읽으면 기여가 사라진다 -> collection_run.branches 로 남긴다
    --    ② 봇·머지 커밋을 빼야 한다 -> git_commit.is_excluded
    --    ③ 로그인 대소문자를 정규화해야 한다. 안 하면 own_* 이 조용히 0이 된다
    -- C-8. 레포를 선택하는 시점엔 아직 수집 전이라 DEFAULT 0 이 필요하다. (V1 부터 있었다)
    commit_count             INT         NOT NULL DEFAULT 0,
    own_commit_count         INT         NOT NULL DEFAULT 0,
    pr_count                 INT         NOT NULL DEFAULT 0,
    own_pr_count             INT         NOT NULL DEFAULT 0,
    review_comment_count     INT         NOT NULL DEFAULT 0,
    own_review_comment_count INT         NOT NULL DEFAULT 0,
    -- 정성 카드 4문항의 근거 밀도 등급.
    -- ⚠️ 화면 배지·정렬용이고 카드 문장에 넣지 않는다. "92% 기여했습니다" 류의
    --    수치화된 자기 주장을 만들지 않기로 한 것과 같은 이유다.
    fit_collaboration        CHAR(1) CHECK (fit_collaboration    IN ('A','B','C','D')),
    fit_conflict             CHAR(1) CHECK (fit_conflict         IN ('A','B','C','D')),
    fit_problem_solving      CHAR(1) CHECK (fit_problem_solving  IN ('A','B','C','D')),
    fit_leadership           CHAR(1) CHECK (fit_leadership       IN ('A','B','C','D')),
    connected_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_analyzed_at         TIMESTAMPTZ,
    UNIQUE (user_id, repository_id),
    CONSTRAINT own_counts_within_total CHECK (
        own_commit_count <= commit_count
        AND own_pr_count <= pr_count
        AND own_review_comment_count <= review_comment_count)
);
CREATE INDEX idx_user_repository_user ON user_repository (user_id, connected_at DESC);

-- 스냅샷. 카드가 3개월 뒤에도 "그때 그 커밋으로 만들었다"를 말할 수 있어야 한다.
CREATE TABLE collection_run (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_repository_id BIGINT      NOT NULL REFERENCES user_repository (id) ON DELETE CASCADE,
    branches           TEXT[]      NOT NULL DEFAULT '{}',
    since              TIMESTAMPTZ,        -- NULL 이면 전체 수집
    head_sha           CHAR(40),
    commits_collected  INT         NOT NULL DEFAULT 0,
    prs_collected      INT         NOT NULL DEFAULT 0,
    issues_collected   INT         NOT NULL DEFAULT 0,
    partial            BOOLEAN     NOT NULL DEFAULT FALSE,
    -- D-2. 허용 값을 고정한다. 예외 시나리오와 1:1 이다 —
    --      RATE_LIMITED(E-2) · CAP_EXCEEDED(1차 상한 초과) · TIMEOUT(E-7).
    --      analysis_job.error_code 의 RATE_LIMITED 와 이름을 맞췄다. 한 글자 차이로
    --      갈라지면 같은 상황을 두 이름으로 부르게 된다.
    partial_reason     VARCHAR(40)
                       CHECK (partial_reason IS NULL OR
                              partial_reason IN ('RATE_LIMITED', 'CAP_EXCEEDED', 'TIMEOUT')),
    collected_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT partial_has_reason CHECK (partial = (partial_reason IS NOT NULL))
);
CREATE INDEX idx_collection_run_repo ON collection_run (user_repository_id, collected_at DESC);

-- ════════════════════════════════════════════════════ 커밋

CREATE TABLE git_commit (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    repository_id    BIGINT      NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    -- 어느 수집에서 들어왔는지. 스냅샷이 성립하려면 필요하다.
    collection_run_id BIGINT     REFERENCES collection_run (id) ON DELETE SET NULL,
    sha              CHAR(40)    NOT NULL,
    author_login     VARCHAR(39),
    -- GitHub 계정과 연결되지 않은 커밋은 author_login 이 NULL 이다. 폴백이 없으면
    -- 그 커밋의 기여가 통째로 사라진다.
    author_name      VARCHAR(255),
    message          TEXT,
    authored_at      TIMESTAMPTZ,
    additions        INT,
    deletions        INT,
    changed_files    INT,
    parent_count     SMALLINT    NOT NULL DEFAULT 1,   -- 2 이상이면 머지 커밋
    is_excluded      BOOLEAN     NOT NULL DEFAULT FALSE,
    exclusion_reason VARCHAR(20) CHECK (exclusion_reason IN ('MERGE', 'BOT', 'VENDORED', 'TOO_LARGE')),
    UNIQUE (repository_id, sha),
    CONSTRAINT excluded_has_reason CHECK (is_excluded = (exclusion_reason IS NOT NULL))
);
CREATE INDEX idx_commit_repo_author ON git_commit (repository_id, author_login)
    WHERE is_excluded = FALSE;
CREATE INDEX idx_commit_repo_time ON git_commit (repository_id, authored_at DESC);

-- ════════════════════════════════════════════════════ 후보

CREATE TABLE candidate (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id          UUID          NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    user_repository_id BIGINT        NOT NULL REFERENCES user_repository (id) ON DELETE CASCADE,
    -- PR: 정상적으로 PR 이 있는 경우 / COMMIT_CLUSTER: main 에 직접 커밋만 있는 경우
    source_type        VARCHAR(15)   NOT NULL CHECK (source_type IN ('PR', 'COMMIT_CLUSTER')),
    github_pr_number   INT,
    title              VARCHAR(200)  NOT NULL,   -- ex) "이메일 발송", "서버 구축"
    -- ⚠️ NOT NULL 이 곧 규칙이다 — 추천 이유를 쓸 수 없는 후보는 화면에 올리지 않는다.
    reason_text        VARCHAR(300)  NOT NULL,
    tech_tags          VARCHAR(200),             -- ex) "Java,Spring,Jwt"
    score              DECIMAL(5,4),
    status             VARCHAR(10)   NOT NULL DEFAULT 'PROPOSED'
                       CHECK (status IN ('PROPOSED', 'CONFIRMED', 'USED', 'EXCLUDED')),
    is_user_modified   BOOLEAN       NOT NULL DEFAULT FALSE,
    -- 프론트의 "카드감 낮음" 배지(lowCardWorth). 규칙 판정이다 — 문서·설정 전용 변경,
    -- 변경량 임계 미만, 근거 커밋 1건. 전부 정수 비교이므로 모델을 부르지 않는다.
    low_card_worth     BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT candidate_pr_number_matches_type
        CHECK ((source_type = 'PR') = (github_pr_number IS NOT NULL))
);
CREATE INDEX idx_candidate_repo_status ON candidate (user_repository_id, status, score DESC);

CREATE TABLE candidate_commit (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    candidate_id BIGINT      NOT NULL REFERENCES candidate (id) ON DELETE CASCADE,
    commit_id    BIGINT      NOT NULL REFERENCES git_commit (id) ON DELETE CASCADE,
    origin       VARCHAR(10) NOT NULL CHECK (origin IN ('AI', 'USER')),
    -- 사용자가 뺐어도 행을 지우지 않고 FALSE 로만 바꾼다. 되돌리기와 "왜 빠졌는지"가 남는다.
    is_included  BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (candidate_id, commit_id)
);

-- ════════════════════════════════════════════════════ 카드

CREATE TABLE card (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id          UUID         NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    user_id            BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_repository_id BIGINT       REFERENCES user_repository (id) ON DELETE SET NULL,
    -- 직접 입력 카드(POST /api/cards/manual)는 후보가 없다.
    candidate_id       BIGINT       REFERENCES candidate (id) ON DELETE SET NULL,
    card_type          VARCHAR(12)  NOT NULL CHECK (card_type IN ('TECH', 'QUALITATIVE')),
    origin             VARCHAR(20)  NOT NULL DEFAULT 'AI' CHECK (origin IN ('AI', 'MANUAL')),
    theme              VARCHAR(20)  CHECK (theme IN ('COLLABORATION', 'CONFLICT', 'PROBLEM_SOLVING', 'LEADERSHIP')),
    title              VARCHAR(200) NOT NULL,
    status             VARCHAR(10)  NOT NULL DEFAULT 'DRAFT'
                       CHECK (status IN ('DRAFT', 'CONFIRMED')),
    -- 카드 단위 버전. 카드 버전 N = 각 (star_slot, seq) 에서 version_no <= N 인 최신 행의 집합.
    current_version    SMALLINT     NOT NULL DEFAULT 1,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    confirmed_at       TIMESTAMPTZ,
    deleted_at         TIMESTAMPTZ,
    -- ADR-0004: 확인 없이 확정 카드로 저장하지 않는다.
    CONSTRAINT card_confirmed_needs_time
        CHECK ((status = 'CONFIRMED') = (confirmed_at IS NOT NULL)),
    CONSTRAINT theme_only_for_qualitative
        CHECK (theme IS NULL OR card_type = 'QUALITATIVE')
);
CREATE INDEX idx_card_user_status ON card (user_id, status, created_at DESC)
    WHERE deleted_at IS NULL;

-- card_statement 가 source_turn_id 로 참조하므로 먼저 만든다.
CREATE TABLE interview_turn (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    card_id        BIGINT      NOT NULL REFERENCES card (id) ON DELETE CASCADE,
    seq            SMALLINT    NOT NULL,        -- 몇 번째 질문
    star_slot      CHAR(1)     NOT NULL CHECK (star_slot IN ('S', 'T', 'A', 'R')),
    -- EVIDENCE_GAP: 코드에서 못 찾은 부분을 묻는다
    -- FOLLOWUP:     답변을 보고 부족한 부분을 이어간다
    -- RECALL_AID:   증거가 없을 때의 회상 질문
    question_type  VARCHAR(20) NOT NULL
                   CHECK (question_type IN ('EVIDENCE_GAP', 'FOLLOWUP', 'RECALL_AID')),
    trigger_source VARCHAR(15) NOT NULL CHECK (trigger_source IN ('USER', 'AUTO')),
    parent_turn_id BIGINT      REFERENCES interview_turn (id) ON DELETE SET NULL,
    question_text  TEXT        NOT NULL,
    answer_text    TEXT,
    answer_source  VARCHAR(12) CHECK (answer_source IN ('TYPED', 'SELECTED')),
    -- LATER·SKIPPED 가 있는 것이 중요하다 — 사용자가 모른다고 하면 추정으로 채우지 않고
    -- 보완 필요 상태로 남긴다(스펙의 적응형 인터뷰 정책 3).
    -- C-4. 'INSUFFICIENT' 가 12자로 VARCHAR(12) 를 정확히 채워서 여유를 줬다.
    outcome        VARCHAR(20) CHECK (outcome IN ('ANSWERED', 'LATER', 'SKIPPED', 'INSUFFICIENT')),
    asked_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    answered_at    TIMESTAMPTZ,
    UNIQUE (card_id, seq),
    CONSTRAINT answered_turn_has_source
        CHECK (outcome <> 'ANSWERED' OR (answer_text IS NOT NULL AND answer_source IS NOT NULL))
);
CREATE INDEX idx_turn_card ON interview_turn (card_id, seq);

-- AI 가 함께 제시한 추천 답변들. 고른 것/안 고른 것을 모두 남긴다.
CREATE TABLE interview_option (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    turn_id     BIGINT   NOT NULL REFERENCES interview_turn (id) ON DELETE CASCADE,
    seq         SMALLINT NOT NULL,
    option_text TEXT     NOT NULL,
    is_selected BOOLEAN  NOT NULL DEFAULT FALSE,
    UNIQUE (turn_id, seq)
);

-- S/T/A/R 문장을 한 줄씩 담는다. 문장마다 근거 커밋을 붙이려고 문장 단위로 나눴다 —
-- 칸 하나에 텍스트 한 덩어리를 넣으면 "이 문장의 근거가 뭐냐"에 답할 수 없다.
CREATE TABLE card_statement (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    card_id       BIGINT      NOT NULL REFERENCES card (id) ON DELETE CASCADE,
    -- 고치면 덮어쓰지 않고 +1 값으로 새 행을 추가한다(append-only).
    version_no    SMALLINT    NOT NULL DEFAULT 1,
    star_slot     CHAR(1)     NOT NULL CHECK (star_slot IN ('S', 'T', 'A', 'R')),
    seq           SMALLINT    NOT NULL,
    body          TEXT        NOT NULL,
    -- ADR-0001 이 구현되는 자리. 스펙 「용어·enum 약속」의 세 값뿐이다 —
    --   COMMIT:        저장소 활동에서 직접 확인 (관측됨).  근거 1개 이상 필수 · linter 대상
    --   USER_STATED:   사용자가 직접 말했거나 쓴 문장.      근거 불필요 · linter 비대상
    --   USER_SELECTED: AI 선택지 중 사용자가 고른 문장.     근거 불필요 · linter 비대상
    --
    -- ⚠️ V1 의 'inferred' · 'ai_suggested' 는 V2 에서 없앴다.
    --    "근거 없이 추론한 문장"이면 우리 유일한 규칙(모든 문장에 근거 링크)의 예외가 되고,
    --    "커밋에서 추론했지만 직접 인용은 아님"이면 근거 커밋이 그대로 붙으므로 출처가 아니라
    --    확신도 문제다 — 그건 아래 confidence = 'LOW' 가 표현한다.
    --    '확인 전 초안'이라는 뜻은 문장이 아니라 card.status = 'DRAFT' 가 들고 있다.
    --
    -- C-4. 'USER_SELECTED' 가 13자라 V1 의 VARCHAR(12) 에 들어가지 않는다.
    evidence_type VARCHAR(20) NOT NULL
                  CHECK (evidence_type IN ('COMMIT', 'USER_STATED', 'USER_SELECTED')),
    -- 'MEDIUM' 이 6자로 V1 의 VARCHAR(6) 를 정확히 채워서 여유를 줬다.
    confidence    VARCHAR(10) CHECK (confidence IN ('LOW', 'MEDIUM', 'HIGH')),
    source_turn_id BIGINT     REFERENCES interview_turn (id) ON DELETE SET NULL,
    UNIQUE (card_id, version_no, star_slot, seq),
    -- C-6. 선택지에서 고른 문장은 어느 턴의 선택지에서 왔는지 반드시 밝혀야 한다.
    --      못 밝히면 출처 없는 주장이다.
    --      ⚠️ V1 은 이걸 user_written(=USER_STATED) 에 걸었는데, 그래서 인터뷰를 안 거치는
    --         직접 작성 카드(POST /api/cards/manual)가 저장 자체를 못 했다.
    --         직접 입력 문장 = USER_STATED + source_turn_id NULL 로 통과한다.
    CONSTRAINT user_selected_has_turn
        CHECK (evidence_type <> 'USER_SELECTED' OR source_turn_id IS NOT NULL)
);
CREATE INDEX idx_statement_card ON card_statement (card_id, star_slot, seq, version_no DESC);

-- 한 문장에 근거 커밋을 연결한다.
-- ⚠️ RESTRICT 다. 근거로 쓰인 커밋은 지울 수 없다 — 카드가 "이 sha 로 만들었다"를
--    나중에도 말할 수 있어야 한다.
CREATE TABLE statement_evidence (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    statement_id BIGINT NOT NULL REFERENCES card_statement (id) ON DELETE CASCADE,
    commit_id    BIGINT NOT NULL REFERENCES git_commit (id) ON DELETE RESTRICT,
    UNIQUE (statement_id, commit_id)
);
CREATE INDEX idx_statement_evidence_commit ON statement_evidence (commit_id);

-- ════════════════════════════════════════════════════ 비동기 · 감사

-- 레포 분석은 실측에서 초안 122~139초, STAR 배치 140~315초가 걸렸다.
-- 동기 요청으로 처리할 수 없다. POST /analyze 는 jobId 만 돌려주고 프론트가 폴링한다.
--
-- ⚠️ updated_at 이 마지막 컬럼인 것은 V2 에서 ADD COLUMN 으로 붙었기 때문이다.
--    실제 DB 의 컬럼 순서와 이 파일을 일치시키려고 일부러 여기 뒀다 (pg_dump 대조용).
CREATE TABLE analysis_job (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id          UUID        NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    user_id            BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_repository_id BIGINT      NOT NULL REFERENCES user_repository (id) ON DELETE CASCADE,
    -- 계약 원칙 3(멱등성). 재시도가 중복 분석·중복 카드를 만들지 않는다.
    idempotency_key    TEXT        NOT NULL,
    type               VARCHAR(20) NOT NULL DEFAULT 'ANALYZE',
    -- C-4·C-5. 확정 5개. "부분 완료"는 상태가 아니라 SUCCEEDED + partial = TRUE 다.
    --          철자는 CANCELED, L 하나. 행이 생기는 시점이 접수 시점이라 기본값은 QUEUED.
    state              VARCHAR(20) NOT NULL DEFAULT 'QUEUED'
                       CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED')),
    -- D-1. 화면의 4단계 체크리스트와 1:1 — 순서도 고정이다.
    --   [{ "key": "COMMITS",   "state": "SUCCEEDED", "done": 184, "total": 184  },
    --    { "key": "PR_REVIEW", "state": "RUNNING",   "done": 12,  "total": 31   },
    --    { "key": "COMPRESS",  "state": "QUEUED",    "done": 0,   "total": null },
    --    { "key": "REASON",    "state": "QUEUED",    "done": 0,   "total": null }]
    --   total: null 이면 총량 미확정이고 화면은 스피너를 돈다.
    --   DB 는 배열이라는 것까지만 잡는다. 키 검증은 애플리케이션 몫이다.
    steps              JSONB       NOT NULL DEFAULT '[]',
    partial            BOOLEAN     NOT NULL DEFAULT FALSE,
    collection_run_id  BIGINT      REFERENCES collection_run (id) ON DELETE SET NULL,
    -- D-2. 운영용 분류. 사용자에게 보여 줄 메시지와 분리한다(계약 원칙 4).
    --      분류되지 않은 실패는 INTERNAL_ERROR 로 접는다.
    --      ⚠️ common.api.ErrorCode 와는 다른 이름 공간이다 — 저쪽은 HTTP 에러 봉투,
    --         이쪽은 200 응답 본문에 실려 가는 Job 실패 분류다.
    error_code         VARCHAR(40)
                       CHECK (error_code IS NULL OR
                              error_code IN ('GITHUB_UNAVAILABLE', 'RATE_LIMITED', 'DRAFT_TIMEOUT',
                                             'EVIDENCE_MISSING', 'INTERNAL_ERROR')),
    started_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at        TIMESTAMPTZ,
    -- C-3. started_at·finished_at 만으로는 "진행 중인데 5분째 그대로"를 구분할 수 없다.
    --      steps 진행이 바뀔 때마다 갱신하고, 프론트가 updatedAt 으로 정체를 감지한다.
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- C-1. 같은 키로 두 번 들어와도 Job 이 하나다. (V1 부터 있었다)
    UNIQUE (user_id, idempotency_key),
    CONSTRAINT analysis_job_steps_is_array CHECK (jsonb_typeof(steps) = 'array')
);
-- C-1. 키가 달라도 같은 레포에 이미 도는 Job 이 있으면 막는다 — 다른 탭에서 누르면
--      키가 다르기 때문이다. QUEUED 를 빼면 접수 직후 두 번째 요청이 안 걸려 Job 이 둘 생긴다.
CREATE UNIQUE INDEX uq_job_active ON analysis_job (user_repository_id)
    WHERE state IN ('QUEUED', 'RUNNING');

-- 스펙의 '감사 이벤트' 엔터티.
--
-- ⚠️ 본문 컬럼이 없는 것이 설계다. 카드 본문·인터뷰 답변·저장소 이름을 남기지 않는다.
--    요청 식별자와 완료 상태만 남긴다(security-privacy.md 관측성).
-- C-7. user_id 는 ON DELETE SET NULL 이다. 기본값(NO ACTION)이면 탈퇴가 막힌다. (V1 부터)
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

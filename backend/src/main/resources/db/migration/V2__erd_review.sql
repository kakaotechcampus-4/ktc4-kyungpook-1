-- ERD 리뷰 13건 반영 (2026-09-14)
--
-- V1 이후 세 곳에서 나온 결론을 하나로 내린다 —
--   ① ERD 변경 리뷰 13건 (V-1·V-2 · C-1~C-8 · D-1·D-2 · S-1)
--   ② 백엔드 팀원 답변 (V-2.txt)
--   ③ 프론트 질문 8개 답변 (FE-ANSWERS.md, 1·2·3·4·5·8 확정 발송분)
--
-- 리뷰 13건 중 C-2(활성 연결 UNIQUE) · C-7(audit_event FK SET NULL) ·
-- C-8(카운트 6개 DEFAULT 0) 과 C-1 의 UNIQUE(user_id, idempotency_key) 는
-- 이미 V1 에 들어가 있어 여기서 손대지 않는다. ERD 툴(erdcloud)에만 빠져 있던 것이다.
--
-- 이 마이그레이션이 하지 않는 것 —
--   S-1 의 raw_event · analysis_cache · event_log 는 보류다. 쓰는 곳이 정해지기
--   전에 테이블부터 만들면 스키마만 늘고 검증이 안 된다. 특히 event_log(지표)는
--   오탐률·놓침률 계산 근거라 3~4주차 게이트가 걸려 있으니, 목적이 확정되면 V3 로 넣는다.

-- ════════════════════════════════════════════════════ V-1  표기를 대문자로 통일
--
-- 스펙 「용어·enum 약속」: "코드·API·DB의 값은 전부 영문 대문자 enum".
-- V1 은 ERD 를 그대로 따르느라 소문자로 들어간 자리가 남아 있었다. 같은 개념이
-- 두 표기로 갈라지면 프론트가 문자열을 추측하게 된다.
--
-- ⚠️ 값이 든 CHECK 를 먼저 지우고 → 데이터를 올리고 → 다시 건다. 순서를 바꾸면
--    UPDATE 가 기존 CHECK 에 걸려 마이그레이션이 멈춘다.

-- repository.visibility
ALTER TABLE repository DROP CONSTRAINT repository_visibility_check;
UPDATE repository SET visibility = upper(visibility);
ALTER TABLE repository ADD CONSTRAINT repository_visibility_check
    CHECK (visibility IN ('PUBLIC', 'PRIVATE'));

-- candidate.source_type · status
-- candidate_pr_number_matches_type 이 source_type = 'pr' 를 들고 있어 함께 다시 건다.
ALTER TABLE candidate DROP CONSTRAINT candidate_pr_number_matches_type;
ALTER TABLE candidate DROP CONSTRAINT candidate_source_type_check;
ALTER TABLE candidate DROP CONSTRAINT candidate_status_check;
UPDATE candidate SET source_type = upper(source_type), status = upper(status);
ALTER TABLE candidate ALTER COLUMN status SET DEFAULT 'PROPOSED';
ALTER TABLE candidate ADD CONSTRAINT candidate_source_type_check
    CHECK (source_type IN ('PR', 'COMMIT_CLUSTER'));
ALTER TABLE candidate ADD CONSTRAINT candidate_status_check
    CHECK (status IN ('PROPOSED', 'CONFIRMED', 'USED', 'EXCLUDED'));
ALTER TABLE candidate ADD CONSTRAINT candidate_pr_number_matches_type
    CHECK ((source_type = 'PR') = (github_pr_number IS NOT NULL));

-- candidate_commit.origin
ALTER TABLE candidate_commit DROP CONSTRAINT candidate_commit_origin_check;
UPDATE candidate_commit SET origin = upper(origin);
ALTER TABLE candidate_commit ADD CONSTRAINT candidate_commit_origin_check
    CHECK (origin IN ('AI', 'USER'));

-- card.card_type · origin · status · theme
-- card_confirmed_needs_time 은 status = 'confirmed' 를,
-- theme_only_for_qualitative 는 card_type = 'qualitative' 를 들고 있다.
ALTER TABLE card DROP CONSTRAINT card_confirmed_needs_time;
ALTER TABLE card DROP CONSTRAINT theme_only_for_qualitative;
ALTER TABLE card DROP CONSTRAINT card_card_type_check;
ALTER TABLE card DROP CONSTRAINT card_origin_check;
ALTER TABLE card DROP CONSTRAINT card_status_check;
ALTER TABLE card DROP CONSTRAINT card_theme_check;
UPDATE card SET card_type = upper(card_type),
                origin    = upper(origin),
                status    = upper(status),
                theme     = upper(theme);
ALTER TABLE card ALTER COLUMN origin SET DEFAULT 'AI';
ALTER TABLE card ALTER COLUMN status SET DEFAULT 'DRAFT';
ALTER TABLE card ADD CONSTRAINT card_card_type_check
    CHECK (card_type IN ('TECH', 'QUALITATIVE'));
ALTER TABLE card ADD CONSTRAINT card_origin_check
    CHECK (origin IN ('AI', 'MANUAL'));
ALTER TABLE card ADD CONSTRAINT card_status_check
    CHECK (status IN ('DRAFT', 'CONFIRMED'));
ALTER TABLE card ADD CONSTRAINT card_theme_check
    CHECK (theme IN ('COLLABORATION', 'CONFLICT', 'PROBLEM_SOLVING', 'LEADERSHIP'));
ALTER TABLE card ADD CONSTRAINT card_confirmed_needs_time
    CHECK ((status = 'CONFIRMED') = (confirmed_at IS NOT NULL));
ALTER TABLE card ADD CONSTRAINT theme_only_for_qualitative
    CHECK (theme IS NULL OR card_type = 'QUALITATIVE');

-- interview_turn.question_type · trigger_source · answer_source · outcome
-- answered_turn_has_source 가 outcome = 'answered' 를 들고 있다.
ALTER TABLE interview_turn DROP CONSTRAINT answered_turn_has_source;
ALTER TABLE interview_turn DROP CONSTRAINT interview_turn_question_type_check;
ALTER TABLE interview_turn DROP CONSTRAINT interview_turn_trigger_source_check;
ALTER TABLE interview_turn DROP CONSTRAINT interview_turn_answer_source_check;
ALTER TABLE interview_turn DROP CONSTRAINT interview_turn_outcome_check;
UPDATE interview_turn SET question_type  = upper(question_type),
                          trigger_source = upper(trigger_source),
                          answer_source  = upper(answer_source),
                          outcome        = upper(outcome);
-- 'INSUFFICIENT' 가 12자로 VARCHAR(12) 를 정확히 채운다. C-4 와 같은 이유로 여유를 준다.
ALTER TABLE interview_turn ALTER COLUMN outcome TYPE VARCHAR(20);
ALTER TABLE interview_turn ADD CONSTRAINT interview_turn_question_type_check
    CHECK (question_type IN ('EVIDENCE_GAP', 'FOLLOWUP', 'RECALL_AID'));
ALTER TABLE interview_turn ADD CONSTRAINT interview_turn_trigger_source_check
    CHECK (trigger_source IN ('USER', 'AUTO'));
ALTER TABLE interview_turn ADD CONSTRAINT interview_turn_answer_source_check
    CHECK (answer_source IN ('TYPED', 'SELECTED'));
ALTER TABLE interview_turn ADD CONSTRAINT interview_turn_outcome_check
    CHECK (outcome IN ('ANSWERED', 'LATER', 'SKIPPED', 'INSUFFICIENT'));
ALTER TABLE interview_turn ADD CONSTRAINT answered_turn_has_source
    CHECK (outcome <> 'ANSWERED' OR (answer_text IS NOT NULL AND answer_source IS NOT NULL));

-- ════════════════════════════════════════════════════ V-2 · C-6  문장의 출처
--
-- V-2 — 'inferred' 를 없앤다.
--   이 프로젝트의 유일한 규칙이 "모든 문장에 근거 링크"이고, 규칙 4 는 근거 0개 문장을
--   화면에서 뺀다. "추론"이라는 출처 유형은 읽기에 따라 근거 없이 쓴 문장을 합법화하는
--   칸이 된다. 두 갈래 뜻 중 어느 쪽이어도 새 유형이 필요 없다 —
--     ① "커밋에서 추론했지만 직접 인용은 아님" → 근거 커밋은 그대로 붙는다.
--        낮은 확신은 이미 있는 confidence = 'LOW' 로 표현된다
--        (프론트의 칸 상태 NEEDS_REVIEW 와 1:1).
--     ② "근거 없이 모델이 채운 칸"      → 규칙 4 에 정면으로 걸린다. 나오면 안 되는 문장이다.
--   'ai_suggested' 도 같은 이유로 없앤다. 확인 전 초안이라는 뜻은 문장이 아니라
--   card.status = 'DRAFT' 가 들고 있고, 초안 문장도 근거 커밋이 붙으므로 COMMIT 이다.
--   남는 값은 스펙 「용어·enum 약속」의 세 개뿐이다.
--
-- C-6 — 직접 작성 카드가 화면에 뜨지 않는 문제.
--   원인은 근거 필수 규칙이 아니라 user_written_has_turn CHECK 다. 이 CHECK 가
--   사용자 진술 문장에 인터뷰 턴을 요구하는데, POST /api/cards/manual 은 S/T/A/R
--   텍스트만 받고 인터뷰를 안 거쳐서 턴이 없다.
--   작성 주체는 evidence_type 이 이미 담고 있으므로 컬럼을 더하지 않는다 —
--   근거 검사(linter) 대상은 evidence_type = 'COMMIT' 인 문장이고,
--   USER_STATED · USER_SELECTED 는 출처가 사용자 자신이라 애초에 검사 대상이 아니다.
--   다만 턴을 전면 선택값으로 풀지는 않는다. 선택지에서 고른 문장(USER_SELECTED)은
--   반드시 어느 턴의 선택지에서 왔는지 밝혀야 한다 — 못 밝히면 출처 없는 주장이다.

ALTER TABLE card_statement DROP CONSTRAINT user_written_has_turn;
ALTER TABLE card_statement DROP CONSTRAINT card_statement_evidence_type_check;
ALTER TABLE card_statement DROP CONSTRAINT card_statement_confidence_check;

UPDATE card_statement SET confidence = upper(confidence);
-- 'inferred' 는 낮은 확신 표시로 옮긴다. 이미 값이 있으면 덮어쓰지 않는다.
UPDATE card_statement SET confidence = 'LOW'
    WHERE evidence_type = 'inferred' AND confidence IS NULL;
UPDATE card_statement SET evidence_type = CASE evidence_type
    WHEN 'commit'       THEN 'COMMIT'
    WHEN 'user_written' THEN 'USER_STATED'
    WHEN 'ai_suggested' THEN 'COMMIT'
    WHEN 'inferred'     THEN 'COMMIT'
    ELSE upper(evidence_type)
END;

-- 'USER_SELECTED' 가 13자라 VARCHAR(12) 에 들어가지 않는다. 반드시 늘려야 한다.
ALTER TABLE card_statement ALTER COLUMN evidence_type TYPE VARCHAR(20);
-- 'MEDIUM' 이 6자로 VARCHAR(6) 를 정확히 채운다.
ALTER TABLE card_statement ALTER COLUMN confidence TYPE VARCHAR(10);

ALTER TABLE card_statement ADD CONSTRAINT card_statement_evidence_type_check
    CHECK (evidence_type IN ('COMMIT', 'USER_STATED', 'USER_SELECTED'));
ALTER TABLE card_statement ADD CONSTRAINT card_statement_confidence_check
    CHECK (confidence IN ('LOW', 'MEDIUM', 'HIGH'));
-- 직접 입력 문장은 USER_STATED + source_turn_id NULL 로 통과한다.
ALTER TABLE card_statement ADD CONSTRAINT user_selected_has_turn
    CHECK (evidence_type <> 'USER_SELECTED' OR source_turn_id IS NOT NULL);

-- ════════════════════════════════════════════════════ analysis_job
--
-- C-5 — 화면 흐름에 [ 정리 시작 ] [ 취소 ] 가 그려져 있는데 CANCELED 상태가 없었다.
-- C-4 — VARCHAR(10) 은 확정 5개는 들어가지만 여유가 없다.
-- C-1 — 같은 레포에 이미 도는 Job 을 막는 유니크가 없었다(다른 탭에서 누르면 키가 다르다).
--
-- ⚠️ 팀원 답변(V-2.txt C-1)은 "v1.schema 와 fe 스펙이 RUNNING·DONE·FAILED 라
--    QUEUED 를 빼자"였는데, 그 세 값은 V1 작성 시점의 옛 목록이다. 확정 발송한
--    프론트 답변 3번이 QUEUED 포함 5개를 명시하고, 2번이 active = QUEUED + RUNNING 으로,
--    1번이 POST /analyze 의 202 응답을 state: "QUEUED" 로 못 박았다. 그래서 QUEUED 를 넣는다.
--    QUEUED 를 빼면 접수 직후 두 번째 요청이 유니크에 안 걸려 Job 이 두 개 생긴다 —
--    C-1 이 막으려던 바로 그 경우다.
--
-- 확정 5개: QUEUED · RUNNING · SUCCEEDED · FAILED · CANCELED
-- "부분 완료"는 상태가 아니라 SUCCEEDED + partial = true 다(프론트 답변 3번).
-- 철자는 CANCELED, L 하나.

DROP INDEX idx_job_running;
ALTER TABLE analysis_job DROP CONSTRAINT analysis_job_state_check;
ALTER TABLE analysis_job ALTER COLUMN state TYPE VARCHAR(20);
UPDATE analysis_job SET state = 'SUCCEEDED' WHERE state = 'DONE';
-- Job 행은 요청을 접수하는 순간 만들어진다. 워커가 집기 전까지는 QUEUED 다.
ALTER TABLE analysis_job ALTER COLUMN state SET DEFAULT 'QUEUED';
ALTER TABLE analysis_job ADD CONSTRAINT analysis_job_state_check
    CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED'));

-- C-1. UNIQUE (user_id, idempotency_key) 는 V1 에 이미 있다. 여기서는 활성 Job 쪽만 막는다.
CREATE UNIQUE INDEX uq_job_active ON analysis_job (user_repository_id)
    WHERE state IN ('QUEUED', 'RUNNING');

-- C-3. started_at · finished_at 만으로는 "진행 중인데 5분째 그대로"를 구분할 수 없다.
--      한 번에 2~5분 걸리는 작업이라 "느린 것"과 "죽은 것"이 눈으로 구별되지 않는다.
--      steps 진행이 바뀔 때마다 갱신하고, 프론트가 updatedAt 으로 정체를 감지한다.
ALTER TABLE analysis_job ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- 프론트 답변 1번이 응답 예시를 type: "ANALYZE" 로 확정 발송했다. DB 와 API 가
-- 같은 문자열을 쓰도록 맞춘다(V-1 과 같은 취지).
UPDATE analysis_job SET type = 'ANALYZE' WHERE type = 'REPO_ANALYSIS';
ALTER TABLE analysis_job ALTER COLUMN type SET DEFAULT 'ANALYZE';

-- D-1. steps JSONB 의 모양을 여기 적어 둔다. key 4개는 화면 4단계와 1:1 이고 순서도 고정이다.
--      백엔드가 키 하나만 바꿔도 화면이 조용히 깨지는 자리다.
--        [{ "key": "COMMITS",   "state": "SUCCEEDED", "done": 184, "total": 184  },
--         { "key": "PR_REVIEW", "state": "RUNNING",   "done": 12,  "total": 31   },
--         { "key": "COMPRESS",  "state": "QUEUED",    "done": 0,   "total": null },
--         { "key": "REASON",    "state": "QUEUED",    "done": 0,   "total": null }]
--      total: null 이면 총량 미확정이고 화면은 스피너를 돈다.
--      DB 는 배열이라는 것까지만 잡는다. 키 검증은 애플리케이션 몫이다.
ALTER TABLE analysis_job ADD CONSTRAINT analysis_job_steps_is_array
    CHECK (jsonb_typeof(steps) = 'array');

-- D-2. 허용 값이 어디에도 적혀 있지 않아 판정이 갈라질 수 있는 자리였다.
--      이 다섯은 프론트 답변 4번 표로 확정 발송했고, 프론트가 문구를 분기하는 값이다.
--      분류되지 않은 실패는 INTERNAL_ERROR 로 접는다(프론트 답변 4번 "그 외").
--      ⚠️ common.api.ErrorCode 와는 다른 이름 공간이다 — 저쪽은 HTTP 에러 봉투,
--         이쪽은 200 응답 본문에 실려 가는 Job 실패 분류다.
ALTER TABLE analysis_job ADD CONSTRAINT analysis_job_error_code_check
    CHECK (error_code IS NULL OR error_code IN (
        'GITHUB_UNAVAILABLE', 'RATE_LIMITED', 'DRAFT_TIMEOUT',
        'EVIDENCE_MISSING', 'INTERNAL_ERROR'));

-- D-2. partial_reason 도 같은 이유로 고정한다. 예외 시나리오와 1:1 이다 —
--      RATE_LIMITED(E-2) · CAP_EXCEEDED(1차 상한 초과) · TIMEOUT(E-7).
--      리뷰에는 RATE_LIMIT 로 적었으나 error_code 의 RATE_LIMITED 와 한 글자 차이로
--      갈라지면 같은 상황을 두 이름으로 부르게 되므로 RATE_LIMITED 로 맞춘다.
ALTER TABLE collection_run ADD CONSTRAINT collection_run_partial_reason_check
    CHECK (partial_reason IS NULL OR partial_reason IN (
        'RATE_LIMITED', 'CAP_EXCEEDED', 'TIMEOUT'));

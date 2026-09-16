# 백엔드 아키텍처

이 문서는 `backend/` 의 폴더 구조와 모듈 경계를 확정한 것이다.
근거는 두 곳이다 — 제품 스펙([gitory-docs](https://github.com/kakaotechcampus-gitory/gitory-docs))과
레포 분석 파이프라인 실측 결과.

**여기 적힌 경계 중 기계로 잴 수 있는 것은 `ModuleBoundaryTest` 로 강제된다.**
주석만 있는 경계는 두 달 뒤에 없는 경계다.

## 왜 단일 서비스인가

스펙의 AI 에이전트 아키텍처는 구성요소를 9개로 나누지만, 그게 서비스를 9개로 나누라는
뜻은 아니다. 지금 필요한 것은 **배포 단위 분리가 아니라 참조 방향 통제**다.
그래서 Spring Boot 하나 안에서 패키지로 모듈을 가르고, 경계를 테스트로 잠근다.

떼어낼 자리는 미리 만들어 뒀다. `agent` 모듈은 포트 인터페이스(`agent.port`) 뒤에 있어서
나중에 별도 서비스로 분리해도 호출부가 바뀌지 않는다.

## 왜 이 구조인가 — 레이어드도 헥사고날도 아니다

이 트리는 셋이 섞여 있고, 각각 다른 이유로 골랐다.

```
바깥  기능별 수직 분할 (모듈러 모놀리스)   consent · ingest · evidence · …
안    3층 레이어드                        api · domain · infra
문    포트/어댑터                         ingest.port · agent.port  — 두 곳만
```

### 최상위를 레이어드로 두지 않은 이유

`controller/` `service/` `repository/` 로 나누면 **이 제품에서 제일 중요한 경계가
트리에서 사라진다.**

> `evidence`·`recommend` 는 LLM 을 부르면 안 된다

`EvidenceService` 와 `AgentService` 가 둘 다 `service/` 에 있으면 이 규칙을 쓸 자리가 없다.
컴파일러도 못 막고 ArchUnit 도 못 막는다. 레이어드는 **기술적 유사성**으로 묶는데
(컨트롤러끼리, 서비스끼리), 여기서 위험한 결합은 기술이 아니라 **도메인 방향**이다.

그 규칙은 관례가 아니라 실측 결론이다 — 팀·개인 판정이 552토큰 메타데이터만으로
3/3 정답이 나왔고 근거값이 전부 정수·문자열이었다. 거기에 모델이 끼면 비용·지연만 늘고
재현성이 떨어진다. **측정된 결론이라 트리에서 보이고 테스트로 잠겨야 했다.**

부수적으로, 팀이 `gitory-api`·`gitory-agent` 로 나뉘어 있다. 수직 분할이면 담당 모듈끼리
충돌 없이 나뉘는데, 레이어드면 네 명이 전부 `service/` 를 동시에 건드린다.

### 전면 헥사고날로 가지 않은 이유

제대로 된 헥사고날은 모듈마다 `application/port/in` · `application/port/out` ·
`application/service` · `domain` · `adapter/in/web` · `adapter/out/persistence` 를 요구한다.
모듈 9개면 **업무 코드 한 줄 쓰기 전에 패키지 45개**다.

**포트는 공짜가 아니다.** 인터페이스 하나 = 파일 하나 + 구현 하나 + 조립 지점 하나 +
"이거 왜 있지"라고 묻는 사람 하나. 갈아끼울 게 없는 곳에 달면 의식(ceremony)이다.

그래서 기준을 하나로 정했다 — **갈아끼울 것, 또는 새면 안 되는 것에만 문을 단다.**

| 포트 | 갈아끼울 것 | 새면 안 될 것 |
|---|---|---|
| `agent.port` | **모델 제공자가 미확정이다.** 스펙은 `gpt-4.1-mini`, 실측은 `claude -p` 로 돌았고, 스펙은 "학습 사용 비활성화를 계약으로 확인 못 하는 제공자는 안 쓴다"고 못 박았다 | 비밀값·원문이 프롬프트로 새는 것 |
| `ingest.port` | GitHub API 버전 · 증분 수집 전략 | **선택 안 한 저장소 접근**(ADR-0003). 범위 제한이 흩어지면 안 된다 |

나머지 일곱 모듈은 갈아끼울 대상이 없다. `card` 의 저장소를 PostgreSQL 말고 뭘로 바꾸겠나.
거기 포트를 달면 코드만 늘고 얻는 게 없다.

**한 줄 요약** — 모듈러 모놀리스 + 선택적 포트. 수직 경계는 도메인 결합을 막으려고,
수평 3층은 각 모듈 안의 읽는 순서 때문에, 포트는 진짜 갈아끼울 두 곳에만.
`agent` 를 별도 서비스로 떼는 길은 이미 열려 있다 — **필요해지면 그때 옮기고,
지금부터 그 비용을 내지는 않는다.**

### 모듈 개수는 ERD 애그리거트가 정한다

초안에서는 스펙의 "구성요소 9개" 표를 패키지 9개로 그대로 옮겼다. **그건 책임 목록이지
모듈 목록이 아니었다.** 팀 리뷰에서 셋을 정리했다.

| 바뀐 것 | 왜 |
|---|---|
| `interview` → `card` 로 합침 | `interview_turn.card_id → card.id` 로 종속되는데 거꾸로 `card_statement.source_turn_id → interview_turn.id` 로 돌아온다. **모듈로 가르면 양방향 의존이 되어 순환 참조 금지 규칙을 스스로 위반한다** |
| `evidence` → `card` 로 합침 | 소유 테이블이 0개였다. `git_commit` 은 `ingest` 것, `candidate_commit` 은 `recommend` 것, `statement_evidence` 만 `card` 것이다. 가진 게 없는 모듈은 계층이다 |
| `matching` 삭제 | ERD 에도 프론트 라우트 맵에도 없다. 빈 패키지를 미리 만들어 두는 건 비용만 있다. 기업 매칭까지 가면 그때 패키지 하나 + 테이블 하나면 된다 |
| `job` 신설 | `analysis_job` 이 살 곳이 없었다. `ingest → recommend → card` 를 엮는 오케스트레이션은 그 자체로 책임이다 |

`evidence` 를 합치면서 **그것이 지키던 규칙 하나가 최상위에서 사라진다.** `card` 는 초안을
만들어야 하니 `agent` 를 반드시 부르기 때문이다. 그래서 규칙을 한 칸 내렸다 —
`card.domain.evidence` 는 `agent` 를 참조할 수 없다. ArchUnit 은 패키지 깊이를 가리지 않아
**모듈을 합쳐도 보장은 그대로다.**

`agent` 는 소유 테이블이 0개인데도 최상위에 남겼다. **테이블이 없는 게 존재 이유**여서다 —
모델 호출이 한 곳에만 있어야 제공자를 바꿀 때 손댈 곳이 하나고, 비밀값이 프롬프트로 새는
경로를 타입으로 막을 수 있다.

## 폴더 구조

```
ktc4-kyungpook-1/
├─ frontend/                        (feature/frontend-scaffold — Vite + React)
│  └─ docs/api-spec.md              ★ 백엔드가 맞춰야 할 계약
├─ backend/
│  ├─ build.gradle                  Spring Boot 4.1.1 · Java 21(툴체인 자동 조달)
│  ├─ compose.yaml                  로컬 PostgreSQL
│  ├─ ARCHITECTURE.md               이 문서
│  ├─ docs/schema-v2.sql            V1+V2 를 합친 최종 스키마 (대조용 · Flyway 밖)
│  └─ src/
│     ├─ main/java/com/gitory/backend/
│     │  ├─ GitoryApplication.java
│     │  ├─ common/api/             ApiResponse · ErrorCode · CursorPage
│     │  ├─ consent/                연결·동의 경계
│     │  ├─ ingest/                 수집·정규화·기여 집계   (+ port/)
│     │  ├─ recommend/              후보 추천 (규칙)
│     │  ├─ agent/                  LLM 경계                (+ port/)
│     │  ├─ card/                   카드·되묻기·근거
│     │  │  └─ domain/
│     │  │     ├─ compose/          초안 조립      — agent 호출
│     │  │     ├─ interview/        되묻기 턴      — agent 호출
│     │  │     └─ evidence/         sha 실재 검증  ★ agent 금지
│     │  ├─ job/                    분석 오케스트레이터 (비동기)
│     │  └─ audit/                  정책·감사
│     ├─ main/resources/
│     │  ├─ application.yml
│     │  └─ db/migration/
│     │     ├─ V1__init.sql         ★ 팀 ERD 기준 · 15 테이블
│     │     ├─ V2__erd_review.sql   ERD 리뷰 13건 반영 (2026-09-14)
│     │     └─ V3__session_store.sql 세션 저장소 (spring-session-jdbc 원문)
│     └─ test/java/.../architecture/ModuleBoundaryTest.java
└─ .github/
   ├─ workflows/backend-ci.yml      팀 추가 (운영 3개는 건드리지 않음)
   └─ ISSUE_TEMPLATE/
```

각 모듈 안은 `api/` · `domain/` · `infra/` 세 층이다.

| 층 | 무엇 | 규칙 |
|---|---|---|
| `api` | 컨트롤러 · 요청/응답 DTO | 엔티티를 직접 노출하지 않는다 |
| `domain` | 엔티티 · 도메인 서비스 | `api` 를 참조하지 않는다 |
| `infra` | 리포지토리 · 외부 클라이언트 | **같은 모듈 밖에서 참조 불가** |
| `port` | 모듈 간 계약 인터페이스 | `agent`·`ingest` 에만 있다 |

## 모듈 경계 — 테스트로 강제되는 것

`ModuleBoundaryTest` 의 8개 규칙이다. 위반 클래스를 넣어 **실제로 발동하는 것까지 확인했다**
(규칙이 클래스가 없어서 조용히 통과하는 상태를 만들지 않는다).

| 규칙 | 왜 |
|---|---|
| 모듈 간 순환 참조 없음 | 순환이 생기면 분리 가능성이 사라진다 |
| `infra` 는 모듈 내부 전용 | GitHub 클라이언트와 LLM 클라이언트가 새어 나가지 않는다. 바꿀 때 손댈 곳이 한 곳 |
| `ingest`·`recommend` → `agent` 금지 | 규칙 계층에 모델이 끼면 비용·지연만 늘고 재현성이 떨어진다. 기여 집계도 여기 포함이다 |
| **`card.domain.evidence` → `agent` 금지** | sha 검증 옆에 모델이 있으면 "모델한테 다시 물어보자"가 자연스러운 수정이 된다. 유령 sha·오귀속이 나온 경로다 |
| **아무도 `job` 을 참조하지 않음** | 오케스트레이션 의존은 한 방향. 역방향이 생기면 job 이 순환의 중심이 된다 |
| `domain` → `api` 금지 | 도메인이 웹 모양에 끌려가지 않게 |
| 컨트롤러는 `api` 에만 | 위 규칙이 우회되지 않게 |
| `api` 가 `@Entity` 참조 금지 | 엔티티가 응답으로 새면 스키마 변경이 곧 계약 변경이 된다 |

```
$ ./gradlew test --tests '*ModuleBoundaryTest' --rerun-tasks
BUILD SUCCESSFUL       ModuleBoundaryTest: tests=8 failures=0 errors=0

# 새 규칙 2개를 겨냥한 위반을 넣는다
$ ./gradlew test --tests '*ModuleBoundaryTest' --rerun-tasks
> Task :test FAILED
ModuleBoundaryTest > infra 는 같은 모듈 안에서만 접근한다 FAILED
ModuleBoundaryTest > card.domain.evidence 는 agent(LLM)를 참조하지 않는다 FAILED
ModuleBoundaryTest > 오케스트레이션 의존은 한 방향이다 — 아무도 job 을 참조하지 않는다 FAILED
BUILD FAILED

# 위반을 지운다
$ ./gradlew test --tests '*ModuleBoundaryTest' --rerun-tasks
BUILD SUCCESSFUL
```

## 데이터 흐름

```
동의·저장소 선택        consent
        │
        ▼
      job  ─────────── 오케스트레이션. 여기서만 아래를 호출한다
        ├─ 수집·정규화·기여 집계   ingest      (GitHub 을 보는 유일한 곳)
        └─ 후보 추천 · 순위        recommend   (결정적. LLM 없음)
        │
   [사람 개입 1] 후보 확정 ── 여기부터 코드 깊이 읽기 비용이 발생한다
        │
        ▼
      card
        ├─ domain.compose     초안 조립    ─┐
        ├─ domain.interview   되묻기 ↔ 답변 ─┼→ agent (LLM)
        └─ domain.evidence    sha 실재 검증  ← agent 를 부를 수 없다
        │
   [사람 개입 2] 카드 확정 ── ADR-0004. 확인 없이 저장하지 않는다
        │
      audit ─────────── 모든 단계를 가로질러 기록. 원문은 남기지 않는다
```

**사람 개입이 두 번 있고, 둘 다 되돌리기 비싼 상태 전이다.**

의존 방향은 `job → ingest·recommend·card` 한 방향이다. 역방향은 경계 테스트가 막는다 —
그게 뚫리면 분석 흐름을 바꿀 때마다 전 모듈이 흔들린다.

## 비동기 — 왜 Job 인가

레포 분석은 실측에서 **초안 생성 122~139초, STAR 배치 140~315초**가 걸렸다.
동기 요청으로 처리할 수 없다.

```
POST /api/repositories/{id}/analyze  →  { jobId }        (즉시 반환)
GET  /api/jobs/{jobId}               →  { state, steps, partial }   (폴링)
```

- `steps` 는 화면의 4단계 체크리스트와 1:1 대응한다 —
  `COMMITS → PR_REVIEW → COMPRESS → REASON`. 내부 모양은 V2 주석에 고정돼 있다
- 상태는 5개다 — `QUEUED · RUNNING · SUCCEEDED · FAILED · CANCELED`.
  **"부분 완료"는 상태가 아니라 `SUCCEEDED` + `partial = true`** 다. 별도 상태로 두면
  성공 분기를 두 벌 쓰게 된다
- 상한 초과·요청 한도 소진은 실패가 아니라 `partial: true` 다. 버리지 않고 표시한다.
- **멱등성은 두 겹이다.** `UNIQUE (user_id, idempotency_key)` 가 같은 키의 재시도를 막고,
  부분 유니크 인덱스 `uq_job_active` 가 **키가 달라도 같은 레포에 도는 Job 이 있으면** 막는다.
  다른 탭에서 누르면 키가 다르기 때문이다. 어느 경우든 409 가 아니라 기존 `jobId` 를 돌려준다
- `updated_at` 이 있어야 **"느린 것"과 "죽은 것"이 구별된다.** 한 번에 2~5분 걸리는 작업이라
  `started_at`·`finished_at` 만으로는 멈춘 Job 을 폴링 화면이 영원히 돌린다

> ⚠️ **`PR_REVIEW` 단계의 산출은 후보 추천용이고, 카드 초안 입력이 아니다.**
> 실측에서 PR 리뷰 전문을 초안 입력에 넣자 토큰이 2.2배, 지연이 113초가 됐는데
> 스택 적중률은 오히려 내려갔다. 리뷰는 정성 카드와 되묻기 재료로만 쓴다.

## 데이터 모델 — 팀 ERD 기준 <!-- 2026-09-09 · V2 반영 2026-09-14 -->

`V1__init.sql` 은 팀 ERD 를 기준으로 삼는다. 테이블명·컬럼명·타입·`BIGINT` PK 는
ERD 를 그대로 따랐고, 스펙이 요구하는데 없던 것만 더했다.
`V2__erd_review.sql` 이 그 위에 ERD 리뷰 13건을 얹는다.

> **읽는 순서** — 지금 스키마가 뭔지만 알고 싶으면 `docs/schema-v2.sql` 하나만 보면 된다.
> V1+V2 를 합친 최종 상태를 한 파일로 뒤집어 둔 것이고, 같은 스키마인지는 `pg_dump` 비교로
> 확인한다(차이 0줄). **Flyway 는 이 파일을 읽지 않는다** — 실행되는 것은 `db/migration/` 둘뿐이고,
> 마이그레이션을 고치면 이 파일도 같이 고쳐야 한다.

### V2 — ERD 리뷰 13건 반영 <!-- 2026-09-14 -->

리뷰 13건 중 **넷은 이미 V1 에 들어가 있었다.** ERD 툴(erdcloud)에만 빠져 있던 것이라
스키마가 할 일이 없었다 — 활성 연결 UNIQUE · `audit_event` FK `SET NULL` ·
카운트 6개 `DEFAULT 0` · `UNIQUE (user_id, idempotency_key)`.

나머지가 V2 다.

| 무엇 | 왜 |
|---|---|
| **enum 값 전부 대문자** | 스펙 「용어·enum 약속」이 *"코드·API·DB 의 값은 전부 영문 대문자"* 로 못 박았는데 V1 은 ERD 를 따르느라 소문자가 남아 있었다. 같은 개념이 두 표기로 갈라지면 **프론트가 문자열을 추측하게 된다.** 6개 테이블 14개 컬럼 |
| **`evidence_type` 4개 → 3개** | `inferred`·`ai_suggested` 삭제. 아래 「신뢰 경계」 참고 |
| **`user_written_has_turn` → `user_selected_has_turn`** | V1 제약이 **직접 작성 카드의 저장 자체를 막고 있었다.** 인터뷰를 안 거치니 턴이 없다 |
| **Job 상태 3개 → 5개** | `QUEUED`·`CANCELED` 추가, `DONE` → `SUCCEEDED`. 화면 흐름에 `[취소]` 가 그려져 있는데 상태가 없었다 |
| **`uq_job_active` 부분 유니크** | 키가 달라도 같은 레포에 도는 Job 을 막는다 (다른 탭 대응) |
| **`analysis_job.updated_at`** | 멈춘 Job 과 느린 Job 을 구별한다 |
| **폭 넓힘 3건** | `USER_SELECTED`(13자)가 `VARCHAR(12)` 에 안 들어갔다. `INSUFFICIENT`·`MEDIUM` 은 딱 맞아 있어서 같이 넓혔다 |
| **코드 값 CHECK 2건** | `error_code` 5개 · `partial_reason` 3개. 허용 값이 어디에도 없으면 판정이 갈라진다 — 실측에서 이미 겪은 실패다 |

**넣지 않은 것** — 스펙 테이블 목록에 있는 `raw_event` · `analysis_cache` · `event_log` 셋은
보류했다. 쓰는 곳이 정해지기 전에 테이블부터 만들면 스키마만 늘고 검증이 안 된다.
`event_log` 는 따로 봐야 한다(아래 「아직 정하지 않은 것」).

### 테이블은 모듈과 1:1 로 나뉜다

모듈 개수를 스펙의 "구성요소 9개"가 아니라 **ERD 의 애그리거트**에 맞췄다.
15개 테이블이 빈틈없이 나뉜다.

| 모듈 | 소유 테이블 |
|---|---|
| `consent` | `users` · `github_connection` |
| `ingest` | `repository` · `user_repository` · `collection_run` · `git_commit` |
| `recommend` | `candidate` · `candidate_commit` |
| `card` | `card` · `card_statement` · `statement_evidence` · `interview_turn` · `interview_option` |
| `job` | `analysis_job` |
| `audit` | `audit_event` |
| `agent` | — (경계 모듈. 테이블이 없는 게 존재 이유다) |
| `common` | — |

처음 초안에는 `evidence`·`interview` 가 최상위 모듈이었는데 **소유 테이블이 0개였다.**
가진 게 없는 모듈은 모듈이 아니라 계층이다. `card` 로 합쳤다.

### ERD 에서 그대로 가져온 것 — 그리고 왜 좋은 결정인가

| 결정 | 왜 |
|---|---|
| `card_statement` 를 **문장 단위**로 쪼갬 | 칸 하나에 텍스트 한 덩어리를 넣으면 "이 문장의 근거가 뭐냐"에 답할 수 없다. `statement_evidence` 로 문장마다 커밋이 붙는다 |
| `interview_turn` + `interview_option` | 되묻기를 턴 모델로 풀었다. `parent_turn_id` 로 꼬리질문이, `question_type` 으로 질문 종류가, `outcome` 으로 종료 사유가 남는다 |
| `outcome` 에 `later`·`skipped` | 사용자가 모른다고 하면 **추정으로 채우지 않고** 보완 필요로 남긴다. 스펙의 적응형 인터뷰 정책 3이 여기서 지켜진다 |
| `git_commit.parent_count` + `is_excluded` | 머지 커밋 배제. 기여도 집계 함정 셋 중 하나가 스키마에 이미 있었다 |
| `own_commit_count` vs `commit_count` | 프론트의 `myCommitCount`/`teamCommitCount` 가 여기서 나온다 |
| `candidate_commit.is_included` | 사용자가 빼도 행을 지우지 않는다. 되돌리기와 "왜 빠졌는지"가 남는다 |
| `repository.last_collected_at` | 증분 수집으로 토큰 비용을 줄이려는 의도 |
| `candidate.reason_text` | 추천 이유를 못 쓰면 후보를 올리지 않는다. `NOT NULL` 이 곧 그 규칙이다 |

### 더한 것 넷 — 스펙이 요구하는데 ERD 에 없었다

| 테이블 | 왜 |
|---|---|
| `github_connection` | 스펙의 **'연결 동의'** 엔터티가 없었다. 어떤 범위에 동의했고 언제 철회했는지가 없으면 "연결 범위 확인 / 연결 해제" 사용자 통제권을 만들 수 없다. `users.user_token_enc` 를 여기로 옮겼다 — 프로필을 읽는 모든 쿼리가 토큰을 함께 읽지 않게 |
| `collection_run` | 스펙: *"분석 결과는 스냅샷에 연결한다. 이후 저장소 활동이 바뀌어도 생성 시점의 근거를 설명할 수 있어야 한다."* `last_collected_at` 의 의도를 테이블로 폈고, `partial` 과 `branches` 가 여기 붙는다 |
| `analysis_job` | 프론트 계약(`api-spec.md` §3)이 요구한다. `idempotency_key` 가 멱등성을 강제한다 |
| `audit_event` | 스펙의 '감사 이벤트'. **본문 컬럼이 없는 것이 설계다** — 요청 식별자와 완료 상태만 남긴다 |

### 컬럼 단위로 더한 것

- **`public_id UUID`** (`card`·`candidate`·`user_repository`·`analysis_job`) — 스펙은
  "예측하기 어려운 내부 식별자"를 요구한다. 순차 `BIGINT` 를 URL 에 쓰면
  `/api/cards/1, 2, 3…` 으로 남의 자원 존재를 훑을 수 있다.
  **`BIGINT` 는 조인·저장에 그대로 쓰고(ERD 선택이 맞다), URL 에는 `public_id` 만 나간다.**
- **`git_commit.author_name`** — GitHub 계정과 연결되지 않은 커밋은 `author_login` 이 NULL 이다.
  폴백이 없으면 그 커밋의 기여가 통째로 사라진다.
- **`collection_run.branches`** — 기본 브랜치만 읽으면 기여가 사라진다
  (실측: `main` 기준 0개 / 본인 브랜치 기준 26개).
- **`candidate.low_card_worth`** — 프론트의 "카드감 낮음" 배지. 규칙 판정이다.
- **`card.current_version`** — ERD 의 `version_no` 는 문장 단위인데 프론트 계약은
  카드 단위 버전(`GET /cards/{id}/versions`)을 요구한다. 카드 버전 N =
  각 `(star_slot, seq)` 에서 `version_no <= N` 인 최신 행의 집합.
- ~~**`evidence_type` 에 `inferred` 추가**~~ — V1 에서 더했다가 **V2 에서 되돌렸다.**
  이유는 아래 「신뢰 경계」에 적었다.

### 고친 것

- `interview_turn.outcome` 의 `iunsufficient` → `INSUFFICIENT` (V1 에서 오타로 판단, V2 에서 대문자).
- `analysis_job.type` 기본값 `REPO_ANALYSIS` → `ANALYZE` (V2). 프론트에 확정으로 보낸
  응답 예시가 `"type": "ANALYZE"` 였다. DB 와 API 가 같은 문자열을 쓴다.

## 신뢰 경계 — ADR-0001 이 구현되는 자리

카드의 **문장마다** 출처 분류가 붙는다. `card_statement.evidence_type` 이 `NOT NULL` 인 것이
그 강제 지점이다 — **분류 없는 문장은 저장할 수 없다.**

| `evidence_type` | 스펙의 분류 | 의미 | 근거 필수 | 근거 검사(linter) |
|---|---|---|---|---|
| `COMMIT` | 관측됨 | 선택 저장소 활동에서 직접 확인 | **1개 이상** | ⭕ |
| `USER_STATED` | 사용자 진술 | 사용자가 직접 말했거나 쓴 문장 | 불필요 | ❌ |
| `USER_SELECTED` | 사용자 선택 | AI 선택지 중 사용자가 고른 문장 | 불필요 | ❌ |

### V1 의 네 분류를 셋으로 줄인 이유

`inferred`(추론)와 `ai_suggested`(AI 제안)를 V2 에서 없앴다. **`inferred` 는 읽기에 따라
근거 없이 쓴 문장을 합법화하는 칸이 된다.** 두 갈래로 읽히는데 어느 쪽이어도 새 유형이
필요 없었다 —

| 읽는 법 | 왜 유형이 아닌가 |
|---|---|
| *"커밋에서 추론했지만 직접 인용은 아님"* | 근거 커밋은 그대로 붙는다. 낮은 확신은 이미 있는 `confidence = 'LOW'` 가 표현한다 (프론트 칸 상태 `NEEDS_REVIEW` 와 1:1) |
| *"근거 없이 모델이 채운 칸"* | 규칙 4(근거 0개 문장은 화면에 뜨지 않는다)에 정면으로 걸린다. 애초에 나오면 안 되는 문장이다 |

`ai_suggested` 도 같다 — *확인 전 초안*이라는 뜻은 문장이 아니라 `card.status = 'DRAFT'` 가
들고 있고, 초안 문장에도 근거 커밋은 붙는다.

### 근거 필수 규칙은 「누가 썼나」로 갈린다

근거 검사의 목적이 *"모델이 GitHub 을 안 읽고 지어냈는가"*(E-8)를 잡는 것이라,
**사용자가 쓴 문장은 애초에 검사 대상이 아니다.** 그래서 linter 대상은
`evidence_type = 'COMMIT'` 인 문장뿐이고, 이게 **linter 가 사용자 문장을 지워 버리는 사고**를
구조로 막는다. 규칙 2(*"사용자가 쓴 문장은 윤문·병합하지 않는다"*)와도 맞는다.

> 별도의 `authored_by` 컬럼을 두는 안이 있었는데 넣지 않았다. 작성 주체를
> `evidence_type` 이 이미 담고 있어 중복이었다.

제약은 한 칸 옮겼다.

```sql
-- V1 — 직접 작성 카드의 저장 자체를 막고 있었다
CONSTRAINT user_written_has_turn
  CHECK (evidence_type <> 'user_written' OR source_turn_id IS NOT NULL)

-- V2
CONSTRAINT user_selected_has_turn
  CHECK (evidence_type <> 'USER_SELECTED' OR source_turn_id IS NOT NULL)
```

**선택지에서 고른 문장은 어느 턴의 선택지에서 왔는지 밝혀야 한다.** 못 밝히면 출처 없는
주장이다. 반면 사용자가 직접 친 문장은 턴이 없을 수 있다 — `POST /api/cards/manual` 은
S/T/A/R 텍스트만 받고 인터뷰를 안 거친다. V1 은 이 경로를 통째로 막고 있었다.
**직접 입력 = `USER_STATED` + `source_turn_id` NULL + `statement_evidence` 행 없음.**

### 빈 칸은 행(row)이 없는 것으로 표현한다

실측에서 T(과제) 칸은 snap 8개 카드 **전부**에서 비었다. 행동(A)과 결과(R)는 8/8·8/8 로
채워졌는데도 그렇다. 저장소에 남는 것은 코드고, "내가 맡기로 한 범위"는 사람 머릿속에만
있다. 채우려 들면 그때부터 지어내기 시작한다.

문장 단위 모델에서는 **빈 칸 = 그 `star_slot` 에 행이 없음**이다. 별도의 `EMPTY` 라벨이
필요 없고, `NULL` 본문을 허용할 필요도 없다 — ERD 쪽이 더 깔끔하다.
그 자리를 `interview_turn` 이 받는다.

### 제약이 실제로 발동하는지 확인했다

**V1** — PostgreSQL 에 스키마를 올려 위반 데이터를 넣어 봤다. **12건 전부 차단, 정상 5건
전부 통과**(오탐 0). 확인한 것 — 확정 시각 없는 확정 카드 · `own > total` 커밋 수 ·
`commit_cluster` 에 PR 번호 · 이유 없는 후보 · 출처 없는 `user_written` ·
철회됐는데 토큰 보유 · 사유 없는 배제/부분수집 · 같은 레포 sha 중복 ·
기술 카드에 정성 주제 · 잘못된 `star_slot` · 근거로 쓰인 커밋 삭제(RESTRICT).

**V2** — V1 을 올리고 **V1 시절 소문자 데이터를 넣은 뒤** V2 를 적용했다. 마이그레이션이
기존 행을 안 깨고 올리는지까지 봐야 해서다. 경계 **22건 전부 기대대로**(실패 0) —
직접 입력 문장 저장됨 · 턴 없는 `USER_SELECTED` 차단 · 활성 Job 중복 차단 ·
끝난 레포엔 새 Job 허용 · 옛 `DONE` 차단 · 정의 안 된 `error_code` 차단 ·
`steps` 에 객체 차단 · 소문자 값 차단 · V1 제약(확정 시각) 유지.
데이터도 확인했다 — `inferred` → `COMMIT` + `LOW`, `user_written` → `USER_STATED`,
`DONE` → `SUCCEEDED`. 빈 DB 신규 설치(V1→V2)도 통과.

**통합본 대조** — `docs/schema-v2.sql` 을 올린 DB 와 V1+V2 를 올린 DB 를 `pg_dump` 로
비교해 **차이 0줄**을 확인했다. 통합본이 낡으면 팀이 틀린 것을 보고 ERD 를 맞추게 된다.

## 프론트가 열어 둔 질문에 대한 답

`frontend/docs/api-spec.md` §8 의 미확정 6건 중 3건은 실측에 답이 있다.

| # | 질문 | 답 | 근거 |
|---|---|---|---|
| 2 | `needsReview` 계산 위치 | **서버.** 세 화면이 같은 규칙을 쓴다 | 프론트 제안대로 |
| 3 | `lowCardWorth` 규칙 vs 모델 | **규칙.** 문서 전용 변경·변경량 임계·근거 1건은 전부 정수 비교 | 팀·개인 판정이 552토큰 메타데이터로 3/3 정답. 근거값이 전부 정수·문자열이었다 |
| 4 | `myCommitCount`/`teamCommitCount` 세는 법 | `recommend` 모듈. 함정 셋을 밟아야 한다 | ↓ |

**#4 의 함정 셋** — 실측에서 전부 밟았다.

1. **기본 브랜치만 읽으면 기여가 사라진다.** 수업 레포에서 `main` 기준 본인 커밋 0개,
   본인 브랜치 기준 26개였다. `analysis_scope.branches` 가 그래서 있다.
2. **봇·머지 커밋을 빼야 한다.** 안 빼면 팀 커밋 수가 부풀려진다.
3. **로그인 대소문자를 정규화해야 한다.** 안 하면 본인 커밋이 **조용히 0개**가 되고
   예외도 경고도 없다. 실측 도구에서 실제로 났던 버그다. 0개면 예외를 던진다.

나머지 3건(크레딧 시스템 · 버전 히스토리 API · 이탈 후 Job 상태)은 제품 결정이라
스펙 쪽에 물어야 한다.

## 기여 귀속 — 이 제품에서 가장 틀리기 쉬운 곳

**커밋 저자는 코드 작성자가 아니다.** 남이 만든 파일에 설정 한 줄만 고쳐도 커밋 저자가 된다.
실측에서 실제 사례가 나왔다.

```
cf192e03e1  {tgu0925}   SecurityConfig.java  +1/-0     ← 이걸 근거로 "오귀속" 판정을 했었다
9d6424a582  taehun0208  JwtTokenProvider.java +71/-0   ← JWT 본체는 이 사람이 썼다
```

여기서 나오는 규칙 세 개다.

1. **저자 불일치를 근거 배제 조건으로 쓰지 않는다.** 정당한 기여를 지운다.
   sha 검사는 **실재만** 판정하고(`evidence`), 기여 판정은 **변경량**이 한다(`recommend`).
2. **"주도/전담/리드" 문자열을 금지하지 않는다.** 98% 기여자에게는 사실이다.
   기여율 조건부로 본다 — 근거가 전부 본인인 카드의 "주도"는 과장이 아니다.
3. **`shared_with` 를 스키마에서 빼지 않는다.** 빼면 저자 불일치를 처리할 자리가 없어져
   배제 아니면 흡수, 둘 중 하나로 무너진다.

실측에서 변경량을 입력에 넣자 모델이 **지시하지 않은 선을 스스로 그었다** —
`"엔드포인트 구현 자체는 HYH1945 기여. 본인 몫은 +4/-3 코드 정리와 문서 반영"`.
사실을 주면 모델이 알아서 한다. 금지어로 막으려 하면 오탐이 난다.

## 보안 경계

| 무엇 | 어디서 강제 |
|---|---|
| OAuth 토큰이 `consent` 밖으로 안 나감 | `ingest` 는 토큰이 아니라 범위가 걸린 클라이언트를 받는다 |
| 모델에 비밀값이 안 감 | `DraftRequest` 타입에 그 필드가 없다 |
| 저장소 텍스트 = 비신뢰 입력 | `agent` 만 모델을 부르고, 커밋·PR 본문을 지시문으로 취급하지 않는다 |
| 감사 로그에 원문 없음 | `audit_event` 에 본문 컬럼이 없다 |
| 선택 안 한 저장소 미분석 | `analysis_scope` 를 거치지 않으면 `ingest` 를 부를 수 없다 |
| actuator 노출 최소 | `include: health`, `show-details: never` |

## 로컬 실행

```bash
cd backend
docker compose up -d          # PostgreSQL
./gradlew build               # 빌드 + ArchUnit 경계 테스트
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

비밀값은 환경변수로 준다. `.env` 와 키 파일은 커밋하지 않는다(`.gitignore` 가 이미 막는다).

## 아직 정하지 않은 것

- **기업 매칭 테이블이 없다.** PRD 7단계(기업 매칭)에 해당하는 테이블이 ERD 에도,
  프론트 `api-spec.md` 라우트 맵에도 없다. **팀이 범위에서 뺀 것으로 보고 V1 에 넣지 않았다.**
  넣을 거면 `company_context`(공개 출처 URL · 직무 · 인재상 태그 · 확인일 필수, 만료 시 추천 제외)가
  스펙이 요구하는 모양이다.
- **토큰 암호화 키 관리.** `github_connection.token_enc` 는 암호화된 값을 전제한다.
  키를 어디서 주입할지(배포 환경 비밀 관리) 아직 안 정했다. 스펙은 "장기 토큰을 별도
  데이터베이스에 평문 저장하지 않는다"고 못 박았다.
- **`fit_*` 등급을 무엇이 매기나.** ERD 주석은 "코드를 통해 매긴 등급"이라고만 한다.
  규칙인지 모델인지에 따라 `recommend` 냐 `agent` 냐가 갈리고, 모듈 경계 테스트가
  그 결정을 강제한다. **`lowCardWorth` 와 같은 종류의 질문이고, 같은 이유로 규칙이 낫다.**
- **카드 버전 복원의 정확한 의미.** `card.current_version` 으로 유도 규칙은 적었지만,
  "복원"이 새 버전을 만드는지 포인터만 옮기는지는 미정이다.
- **지표 테이블이 없다.** 스펙 목록의 `event_log` 자리를 `audit_event` 가 대신하고 있는데
  **둘은 목적이 다르다.** `audit_event.action` 은 `CONNECT`·`REVOKE`·`ANALYZE`·
  `CONFIRM_CARD`·`DELETE_DATA` — 보안 감사다. 후보 편집 같은 **사용자 개입이 행으로 남지
  않아서 오탐률·놓침률을 계산할 근거가 없다.** 3~4주차 게이트가 여기 걸려 있다.
  (핵심 설계 결정 3 — *"사용자 개입을 행으로 남긴다"*)
- **`analysis_cache` 를 Redis 에 둘지 DB 에 둘지.** 필요한 테이블이라는 데는 합의했고
  초반엔 어느 쪽이든 성능 문제가 없다. `raw_event` 는 목적부터 정해야 한다.
- **취소·임시 저장 엔드포인트가 아직 없다.** 스키마는 `CANCELED` 와 카드 버전을 이미
  받을 수 있지만 `POST /api/jobs/{id}/cancel` · `PATCH /api/cards/{id}/draft` 는 구현 전이다.
- **종료 상태의 무결성을 DB 가 안 잡는다.** `state` 가 `SUCCEEDED`/`FAILED`/`CANCELED` 인데
  `finished_at` 이 비어 있거나, `FAILED` 인데 `error_code` 가 없는 행을 지금은 넣을 수 있다.
  `card_confirmed_needs_time` 과 같은 종류의 제약인데 논의된 적이 없어 V2 에 넣지 않았다.
- **모델 제공자.** `agent.port` 뒤에 있어 나중에 정해도 호출부가 안 바뀐다.
- **Job 실행 방식.** 지금은 `@Async` 전제다. 인스턴스가 늘어나면 큐가 필요하다.
  `analysis_job` 이 이미 상태를 들고 있어 옮길 때 스키마는 안 바뀐다.
- **커밋 수백 개 레포.** 실측 표본이 9~67개뿐이라 1차 상한(커밋 1,000)이 실제로
  도는지 확인되지 않았다.
- **세션 테이블만 Flyway 밖이다.** `spring-session-jdbc` 의 스키마는 라이브러리 버전에
  묶여 있다. 도메인 테이블은 전부 Flyway 다.
- **프론트 CI.** 이번엔 `backend-ci.yml` 만 넣었다.
- **ERD 정리.** 다이어그램에 `test` · `hello/Untitled` 잔여 테이블 두 개가 남아 있다.

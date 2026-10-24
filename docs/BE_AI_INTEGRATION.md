# BE–AI 연결 및 API 문서

Spring이 FastAPI 내부 API(수집·경험 그룹화·diff 근거·STAR 초안·되묻기·공고 조건 뽑기·매칭 검색·공고 색인)를 부르는 구조와, 실행 코드에서 BE·AI OpenAPI를 생성하는 방법을 정리한다. 현재 분석 Job은 `JobWorker`가 꺼내 수집 → 수집 결과 저장 → 경험 그룹화(`ANALYSIS_GROUPING_ENABLED=true`일 때) → 카드 후보 저장까지 실행한다. 카드 초안 Job은 같은 워커 자리를 쓰며, `CARD_AI_DRAFT_ENABLED=true`일 때 카드마다 diff 근거(`/internal/analysis/diff-evidence`) → STAR 초안(`/internal/analysis/star`) → 저장 순서로 칸을 채우고, 끄면 AI를 부르지 않고 끝낸다.

## 연결 구조

```mermaid
flowchart LR
    Browser[브라우저] --> BE[Spring Backend]
    BE --> DB[(PostgreSQL)]
    Ingest[ingest: RepositoryActivityPort] --> Consent[consent: 연결 상태 확인·토큰 복호화]
    Consent --> HTTP[common: AiHttpClient]
    HTTP -->|내부 HTTP /internal/collect| AI[FastAPI]
    BE -->|/health · /openapi.json| AI
```

`AiRepositoryActivityAdapter`가 DB의 내부 `user_repository.id`를 GitHub 저장소 정보로 바꾼다. `consent`는 활성 연결·토큰 만료·계정 삭제 요청을 확인하고 토큰을 복호화한다. 토큰을 반환하는 포트는 없으며, HTTP 전송 경계에서 `X-GitHub-Token` 헤더에만 사용한다. 외부 HTTP 오류 본문과 예외 cause는 호출자에게 전달하지 않는다. 리다이렉트도 따라가지 않는다.

수집 응답은 성공 봉투, 연결 저장소 ID, GitHub 저장소 ID, SHA, 숫자 범위, 부분 수집·제외 판정을 검증한 뒤 기존 `CollectedActivity`로 변환한다. 수집 포트는 분석 Job 워커(`JobRunner`)만 호출하며, 공개 수집 API는 없다.

## 설정

| 변수 | 기본값 / 용도 |
|---|---|
| `AI_BASE_URL` | `http://localhost:8000`; Compose에서는 `http://ai:8000` |
| `AI_CONNECT_TIMEOUT` | `3s`; 연결 제한 |
| `AI_READ_TIMEOUT` | `180s`; 수집·경험 그룹화·되묻기·diff 근거·STAR 초안·공고 조건 뽑기·매칭 검색을 뺀 AI 호출의 응답 대기 제한(지금은 쓰는 호출 없음) |
| `AI_COLLECT_READ_TIMEOUT` | `600s`; GitHub 수집 응답 대기 제한. 멈춘 Job 판정(분석·카드 초안 Job 모두)은 마지막 진행 기록부터 이 값·`AI_GROUPS_READ_TIMEOUT`·`AI_DIFF_EVIDENCE_READ_TIMEOUT`·`AI_STAR_READ_TIMEOUT` 중 큰 값 + 5분 |
| `AI_GROUPS_READ_TIMEOUT` | `600s`; 경험 그룹화(`/internal/analysis/groups`) 응답 대기 제한 |
| `AI_DIFF_EVIDENCE_READ_TIMEOUT` | `300s`; 카드 초안의 diff 근거(`/internal/analysis/diff-evidence`) 응답 대기 제한 |
| `AI_STAR_READ_TIMEOUT` | `180s`; 카드 초안의 STAR 초안(`/internal/analysis/star`) 응답 대기 제한 |
| `AI_POSTING_EXTRACT_READ_TIMEOUT` | `300s`; 채용 공고 조건 뽑기(`/internal/job-postings/extract`) 응답 대기 제한 |
| `AI_MATCHING_INDEX_READ_TIMEOUT` | `120s`; 공고 색인(`/internal/matching/postings/index`)과 색인 정리(`/internal/matching/postings/prune`) 응답 대기 제한. 하루 한 번 공고 가져오기 끝에 활성 공고를 20개씩 보낸 뒤 남길 공고 목록으로 정리를 한 번 부른다 |
| `ANALYSIS_GROUPING_ENABLED` | `false`; 켜면 분석 Job 이 수집 뒤 경험 그룹화를 부른다. 운영 AI 에 LLM 설정(`GITORY_LLM_BASE_URL`·`GITORY_LLM_API_KEY`)을 넣은 뒤 켠다. `deploy/compose.yaml` 이 ai 에 LLM 설정을, backend 에 이 값을 이미 넘기므로 `deploy/.env` 에 적으면 들어간다 |
| `CARD_AI_DRAFT_ENABLED` | `false`; 켜면 후보로 만든 카드의 초안 Job 이 diff 근거·STAR 초안으로 칸을 채우고, 끄면 AI 를 부르지 않고 끝낸다. 운영 AI 에 LLM 설정을 넣은 뒤 켠다. `deploy/compose.yaml` 의 backend `environment` 에 `CARD_AI_DRAFT_ENABLED` 를 넘겨야 `deploy/.env` 값이 들어간다 |
| `API_DOCS_ENABLED` | BE 운영 기본 `false`, `local` 프로필 기본 `true` |
| `AI_DOCS_ENABLED` | AI 기본 `true`; 운영에서 문서가 필요 없으면 `false` |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | BE 기본 프로필의 PostgreSQL 접속 정보 |
| `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` | BE GitHub OAuth App 설정 |
| `TOKEN_ENC_KEY`, `TOKEN_ENC_SALT` | 기존 BE 토큰 암호화 설정; salt는 16진수 |

헬스·문서 조회는 수집과 별도로 응답 대기 제한 5초를 사용한다. 되묻기는 프론트가 요청을 30초에 끊으므로 그보다 짧은 `gitory.ai.interview-read-timeout`(기본 20초)을 응답 대기 제한으로 사용하고, 같은 이유로 매칭 검색(`/internal/matching/search`)은 `gitory.ai.matching-search-read-timeout`(기본 25초)을 사용한다. `AI_BASE_URL`에 인증 정보·쿼리·토큰을 넣지 않는다. Spring이 `.env` 파일을 자동으로 읽는 구성은 없으므로 환경변수를 실행 프로세스에 주입한다. 토큰 암호화 키를 바꾸면 기존 연결의 암호문을 읽을 수 없으므로 기존 키를 유지한다.

## 로컬 실행

AI는 Python 가상 환경에서 실행한다. 저장소 루트 기준:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r ai/requirements-dev.txt
cd ai
../.venv/bin/python -m uvicorn main:app --host 127.0.0.1 --port 8000
```

별도 터미널에서 BE를 실행한다. JDK 21과 Docker가 필요하다. 위 표의 OAuth·토큰 암호화 환경변수를 설정한 상태에서:

```bash
cd backend
docker compose up -d db
SPRING_PROFILES_ACTIVE=local AI_BASE_URL=http://127.0.0.1:8000 ./gradlew bootRun
```

`local` 프로필은 기존 개발 DB(`localhost:5432/gitory`, 사용자·비밀번호 `gitory`)를 사용하고 HTTP 로컬 세션 쿠키를 허용한다. 다른 DB를 쓰려면 우선순위가 높은 `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`로 재정의한다. 운영에서는 `local` 프로필을 사용하지 않는다.

## 문서 주소와 사용법

| 주소 | 용도 |
|---|---|
| <http://localhost:8080/api/swagger-ui.html> | BE Swagger; 구현된 API 조회·실행 |
| <http://localhost:8080/api/ai-docs.html> | BE에서 보는 AI Swagger; 실행 버튼 없는 읽기 전용 화면 |
| <http://localhost:8080/api/docs> | BE OpenAPI JSON |
| <http://localhost:8080/api/docs/ai> | 내부 AI `/openapi.json`의 원본 JSON 프록시 |
| <http://localhost:8000/docs> | 로컬 AI Swagger; 내부 API 직접 테스트 |
| <http://localhost:8000/redoc> | 로컬 AI 읽기 문서 |

BE 문서를 켜면 문서·정적 assets는 로그인 없이 볼 수 있다. 실제 BE API의 세션 인증과 CSRF 보호는 그대로 유지한다. 같은 브라우저에서 `/api/auth/github/start`로 로그인한 뒤 Swagger로 돌아온다. HttpOnly `SESSION` 쿠키는 브라우저가 자동 전송한다. 변경 요청에는 `XSRF-TOKEN` 쿠키의 값을 Swagger가 `X-XSRF-TOKEN` 헤더로 전달한다. Authorize 칸에 GitHub 토큰이나 세션 값을 입력하지 않는다.

BE에는 AI 실행용 공개 프록시가 없다. AI 읽기 화면은 저장소에 포함된 Swagger assets를 사용하고 `supportedSubmitMethods: []`로 실행을 비활성화한다. AI를 직접 시험하려면 로컬 AI `/docs`를 사용한다. 운영 AI는 내부 네트워크에만 두고 필요할 때 로컬 포워딩으로 문서를 확인한다.

BE의 `API_DOCS_ENABLED=false`는 Swagger·OpenAPI·AI 문서 프록시를 비활성화한다. 비로그인 요청은 기존 `/api/**` 인증 정책에 따라 401, 로그인 후 없는 문서 경로는 404다. AI의 `AI_DOCS_ENABLED=false`는 `/docs`, `/redoc`, `/openapi.json`을 404로 만들며 `/health`와 내부 API는 유지한다. BE에서 AI 문서를 읽으려면 양쪽 문서 설정을 켜야 한다. 설정 변경 후 프로세스를 재시작한다.

## OpenAPI 파일로 문서 작업

API가 바뀔 때 실행 코드에서 다시 생성한다. BE가 실행 중인 상태에서 저장소 루트에서:

```bash
PYTHON_BIN=.venv/bin/python bash scripts/export-api-docs.sh
# BE 주소가 다르면:
BACKEND_URL=http://localhost:18080 PYTHON_BIN=.venv/bin/python bash scripts/export-api-docs.sh
```

결과는 `docs/openapi/backend.json`, `docs/openapi/ai.json`이다. BE는 실제 `/api/docs`를 내려받고, AI는 실행 앱과 동일한 `create_app()`에서 오프라인 생성하므로 GitHub·LLM·AI 서버 기동이 필요 없다. AI Python 의존성은 설치되어 있어야 한다. 생성 파일을 수작업으로 수정하지 않고 컨트롤러·Pydantic 모델을 고친 뒤 다시 생성한다. 예시·스크린샷·export에 실제 토큰을 넣지 않는다.

AI만 내보내려면:

```bash
.venv/bin/python ai/scripts/export_openapi.py --output docs/openapi/ai.json
```

## 연결 상태 확인

```bash
curl --fail http://localhost:8000/health
curl --fail http://localhost:8080/actuator/health/readiness
curl --fail http://localhost:8080/actuator/health/liveness
```

AI `/health`는 `{"status":"ok","service":"gitory-ai","version":"0.0.1"}`를 반환한다. BE readiness는 DB와 AI의 이 응답을 확인한다. AI 연결이 끊기거나 서비스 식별자가 다르면 readiness는 DOWN·HTTP 503이 된다. liveness는 BE 프로세스 상태를 검사하므로 AI 장애에 따라 BE를 재시작시키지 않는다. 헬스 상세 내용은 공개하지 않는다.

이 확인은 HTTP 연결과 프로세스 생존을 뜻하며 GitHub 권한·LLM 설정·카드 생성 가능 여부를 보장하지 않는다. AI 문서 프록시 실패는 안전한 BE 오류 봉투로 502를 반환한다. 수집 오류는 `AiClientException`의 정제된 `errorCode`, `retryable`, `httpStatus`로 내부 호출자에게 전달한다. 분석 Job 워커는 GitHub 쪽 오류를 `GITHUB_UNAVAILABLE`, 연결 안 됨·시간 초과·AI가 다시 해 보라고 알린 실패(경험 그룹화 포함)를 다시 시도할 수 있는 `AI_UNAVAILABLE`, 그 밖의 오류를 `INTERNAL_ERROR`로 Job을 실패 처리한다.

## 되묻기 루프로 바뀌는 AI 계약 (#363 에서 정함, 구현은 #361·#362)

- **질문 요청(`/internal/interview-turns`)에 더할 것**
  - `target_star_slot`: 화면이 칸을 지정했을 때만 넣는다. 비면 AI 가 고른다.
  - `allowed_slots`: 서버가 규칙(같은 칸 2번 · `NOT_MEASURED`·`SKIP` 으로 끝난 칸 제외 · 카드당 상한)으로 거른, 고를 수 있는 칸 목록. AI 는 이 안에서만 고른다.
  - `previous_turns`: 지난 질문의 칸·질문·결과(`outcome`)·답. AI 가 '같은 칸 다시 / 다른 칸 / 끝' 을 판단하고 예상 답변을 만드는 재료다.
- **질문 응답(`InterviewTurnResult`)에 더할 것** — 지금 모양을 그대로 쓴다. `next_action` 의 `ASK_AGAIN`·`COMPLETE` 가 '묻기·끝', `target_star_slot` 이 고른 칸이다.
  - `answer_mode`: `TEXT`·`MEASURE`. R 이 아닌데 `MEASURE` 면 서버가 `TEXT` 로 고친다.
  - `suggested_answers`: 예상 답변 목록. 서버가 화면의 `options` 로 옮긴다.
  - `allowed_slots` 밖의 칸이 오면 서버가 받지 않고 규칙 틀 질문으로 대신한다.
- **수치 문장 API 새로 만듦** — `POST /internal/interview-measured-sentence`. 요청은 지금 R 문장 · 사용자가 쓴 글 · 카드 제목, 응답은 R 문장 하나다. 서버는 문장에 든 숫자가 모두 사용자 글 안에 있는지 확인하고, 탈락·실패·마감 초과면 지금 R 문장 뒤에 사용자 글을 붙인 틀 문장을 쓴다.
- **답 평가(`/internal/interview-answer-evaluations`)는 질문을 만들지 않는다.** 질문은 질문 요청에서만 만든다. 답이 모자라다는 판단은 다음 칸을 고를 때 재료로 쓴다(따로 부를지 질문 요청에 합칠지는 #361 에서 정한다).
- 되묻기 질문·다음 칸 고르기·수치 문장 호출은 AI 의 되묻기 전용 실행기(스레드 6개)로 보낸다. 실행기를 얻었을 때 마감까지 시간이 모자라면 부르지 않고 틀 질문·틀 문장으로 대신한다.

## 구현 범위와 다음 개발 순서

| 파트 | 현재 상태 / 남은 작업 |
|---|---|
| BE Job 워커 | `JobWorker`가 2초마다 대기 Job을 꺼내 실행(동시 실행 `JOB_WORKER_CONCURRENCY`, 기본 3, 카드 초안 Job과 같은 자리 사용). 분석 Job은 수집·저장·경험 그룹화·후보 저장까지 연결 |
| BE AI 연결 | 수집·경험 그룹화·되묻기·diff 근거·STAR 초안·매칭 검색 어댑터는 실행 경로에 연결. diff 근거·STAR 초안은 카드 초안 Job이 `CARD_AI_DRAFT_ENABLED=true`일 때 부른다. 되묻기 답 평가(`/internal/interview-answer-evaluations`)는 BE 가 더 부르지 않는다 — 다음 질문 요청에서 AI 가 앞 답을 읽고 다시 물을지 정한다 |
| BE API | 내 정보·분석 Job·카드·인터뷰 API를 BE Swagger로 문서화 |
| AI API | 수집 2개, 분석 A 계약 3개, 인터뷰 B 2개와 health를 문서화 |
| AI A | 경험 그룹화(#109)와 diff 근거·STAR 초안 구현. diff·STAR 연동은 [연동 문서](AI_DIFF_STAR_INTEGRATION.md) 참고 |
| AI B | 템플릿 질문·규칙 기반 답변 평가. LLM 판정기 연결은 별도 작업 |
| 인프라 | 내부 AI 주소·문서 토글·readiness 설정. EC2 배포(Dockerfile·`deploy/compose.yaml`·Caddy)는 [배포 문서](DEPLOYMENT.md) 참고 |

증분 수집은 작성 시각 기반 `since`를 사용하지 않는다. Spring이 이미 저장한 `known_commit_shas`를 보내면, AI는 commit 목록을 다시 확인하되 저장된 commit의 상세 조회를 생략한다. AI 응답의 `head_sha`는 Spring이 저장소 상태로 보관한다. 기존 BE의 수집 상한 설정은 AI 요청에 전달하는 필드가 없어 아직 적용되지 않는다. 검증된 사용자 이메일이 없어 `known_emails=[]`로 보내며, DB의 기본 브랜치가 없으면 `main`을 추측하지 않고 실패한다. 기존 BE 저장 DTO는 commit(바뀐 파일·이슈 번호 포함)·PR/Issue 상세·PR 리뷰와 줄 댓글·부분 수집 사유만 담으므로 AI의 `needs_confirmation`은 보존되지 않는다.

부분 응답은 `partialReason`으로 완료와 구분한다.

## 검증

2026-10-04 기준 최종 BE `build`의 **155개 테스트 통과**, AI **137 passed / 1 live skipped**를 확인했다. 임시 PostgreSQL·실제 Spring·FastAPI를 기동해 BE Swagger와 AI 읽기 화면, AI 명세 프록시, export 스크립트를 검증했다. 프록시 JSON은 AI 오프라인 export와 같았다. AI 중지 후 BE readiness는 **503 DOWN**, liveness는 **200 UP**, AI 명세 프록시는 정제된 **502**를 반환했다. 검증용 프로세스·DB는 종료했으며 운영 배포와 실제 OAuth/GitHub 호출은 수행하지 않았다.

BE는 JDK 21 Gradle 빌드·테스트, 모듈 경계, 실제 HTTP stub, 공유 수집 계약 fixture, PostgreSQL 기반 Swagger·세션·CSRF 통합 테스트로 확인한다. AI는 전체 pytest, 오프라인 OpenAPI export, 실제 uvicorn HTTP 서버의 health 테스트를 실행한다. 신규 라이브러리 검증으로 임시 복사본에서 Swagger CSRF 설정을 끄면 interceptor 검사 실패, AI health 등록을 제거하면 uvicorn HTTP 검사 실패를 확인했으며 원본 테스트는 통과했다. 실제 GitHub 호출은 토큰을 명시한 live 테스트로 별도 분리되어 있으며 이번 로컬 검증에서는 호출하지 않는다.

```bash
cd backend
./gradlew build
# 별도 터미널 / 저장소 루트
.venv/bin/python -m pytest -q ai/tests
```

문서 라이브러리는 [springdoc Boot 4 호환 버전](https://springdoc.org/)과 [FastAPI 문서 설정](https://fastapi.tiangolo.com/tutorial/metadata/)을 따른다. BE JSON은 `{data,error}`, AI 내부 JSON은 `{success,data,meta,error}`이며 OpenAPI JSON·health는 이 API 응답 봉투로 감싸지 않는다.

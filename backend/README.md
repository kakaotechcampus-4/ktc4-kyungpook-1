# backend — 백엔드 개발 가이드

Spring Boot 서버입니다. GitHub 로그인, 저장소 연결, 분석 Job, 카드, 기업 매칭 API 를 맡고, AI 기능은 FastAPI(`ai/`)를 내부 HTTP 로 부릅니다.
모듈을 왜 이렇게 나눴는지, 경계 규칙이 무엇인지는 [ARCHITECTURE.md](ARCHITECTURE.md) 를 먼저 읽으세요. 이 문서는 실행 절차만 다룹니다.

## 사전 준비

**JDK 21** 과 **Docker** 가 필요합니다.

- Spring Boot 4.1.1, Java 21, Gradle 9.4.1(래퍼 포함)입니다. 따로 Gradle 을 설치하지 않습니다.
- `settings.gradle` 이 툴체인 자동 조달을 켜 둬서, 다른 버전의 JDK 로 Gradle 을 띄워도 컴파일·시험은 Java 21 로 돕니다.
- Docker 는 로컬 DB 를 띄울 때와 시험(Testcontainers 가 PostgreSQL 컨테이너를 띄웁니다)에 씁니다.

### macOS

```bash
brew install --cask temurin@21
brew install --cask docker   # Docker Desktop. 설치 뒤 앱을 한 번 실행합니다
```

### Windows (PowerShell)

```powershell
winget install EclipseAdoptium.Temurin.21.JDK
winget install Docker.DockerDesktop
```

Windows 에서는 아래 명령의 `./gradlew` 를 `.\gradlew.bat` 으로 바꿔 씁니다.

## 처음 세팅 (클론 후 한 번)

### 1. 로컬 DB 띄우기

[compose.yaml](compose.yaml) 이 로컬 개발용 PostgreSQL 17 과 Redis 7 을 띄웁니다. 운영 구성이 아닙니다.

```bash
cd backend
docker compose up -d db
```

DB 는 `localhost:5432/gitory`, 사용자·비밀번호 모두 `gitory` 입니다. `local` 프로필(`application-local.yml`)이 이 값을 씁니다.
Redis 는 호출 제한을 켜 볼 때(`RATE_LIMIT_ENABLED=true`)만 필요합니다. 꺼 두면 백엔드는 Redis 없이 뜹니다.

### 2. GitHub OAuth App 만들기

GitHub → Settings → Developer settings → OAuth Apps → New OAuth App.
Authorization callback URL 은 `{baseUrl}/api/auth/github/callback` 입니다. 백엔드만 띄워 로그인하면 `http://localhost:8080/api/auth/github/callback` 입니다.
프론트 개발 서버(5173)를 거쳐 로그인할 때 어떤 주소를 넣어야 하는지는 [확인 필요]입니다.

### 3. 환경변수 넣기

Spring 은 `.env` 파일을 자동으로 읽지 않습니다. 실행하는 셸에 직접 넣습니다. 값은 저장소·채팅에 붙이지 않습니다.

| 이름 | 꼭 필요한가 | 내용 |
|---|---|---|
| `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` | 필요 | 위에서 만든 OAuth App 값 |
| `TOKEN_ENC_KEY` | 필요 | GitHub 토큰 암호화 키. `openssl rand -hex 32` |
| `TOKEN_ENC_SALT` | 필요 | 암호화 솔트. 16진수여야 합니다. `openssl rand -hex 8` |
| `AI_BASE_URL` | 선택 | 기본 `http://localhost:8000` |
| `ANALYSIS_GROUPING_ENABLED` | 선택 | 기본 `false`. 켜면 분석 Job 이 AI 경험 그룹화를 부릅니다 |
| `CARD_AI_DRAFT_ENABLED` | 선택 | 기본 `false`. 켜면 카드 초안 Job 이 AI 로 칸을 채웁니다 |
| `RATE_LIMIT_ENABLED` | 선택 | 기본 `false`. 켜면 Redis 가 필요합니다 |
| `MATCHING_SYNC_ENABLED` | 선택 | 기본 `false`. 켜면 매일 새벽 5시(서울)에 채용 공고를 가져옵니다 |
| `JOB_WORKER_ENABLED`, `JOB_WORKER_CONCURRENCY` | 선택 | 기본 `true`, `3` |
| `API_DOCS_ENABLED` | 선택 | `local` 프로필은 기본 `true` 입니다 |
| `ROCKETPUNCH_API_KEY` | 선택 | 비어 있으면 로켓펀치 공고만 건너뜁니다 |

AI 응답 대기 시간(`AI_*_TIMEOUT`)의 기본값과 뜻은 [BE–AI 연결 문서의 설정 절](../docs/BE_AI_INTEGRATION.md#설정)에 있습니다.
`local` 프로필을 쓰면 `DB_URL`·`DB_USERNAME`·`DB_PASSWORD` 는 넣지 않아도 됩니다.

```bash
export GITHUB_CLIENT_ID=...
export GITHUB_CLIENT_SECRET=...
export TOKEN_ENC_KEY=$(openssl rand -hex 32)
export TOKEN_ENC_SALT=$(openssl rand -hex 8)
```

토큰 암호화 키·솔트는 한 번 정하면 그대로 씁니다. 바꾸면 DB 에 저장된 토큰을 풀 수 없어 다시 로그인해야 합니다.

## 실행과 동작 확인

```bash
cd backend
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

`local` 프로필은 로컬 DB 를 쓰고, 세션 쿠키의 `Secure` 를 끄고(http://localhost 에서 로그인되게), `com.gitory.backend` 와 SQL 로그를 DEBUG 로 찍습니다.
서버가 뜰 때 Flyway 가 `src/main/resources/db/migration/` 의 마이그레이션을 적용합니다.

AI 까지 띄우려면 [BE–AI 연결 문서의 로컬 실행 절](../docs/BE_AI_INTEGRATION.md#로컬-실행)을 따릅니다. 로그인·저장소 목록은 AI 없이도 됩니다. 분석 Job 은 AI 가 없으면 실패로 끝납니다.

다른 터미널에서 확인합니다.

```bash
curl -i http://localhost:8080/actuator/health/readiness
# 예상 응답: HTTP 200, {"status":"UP"}  [확인 필요: 이번에 실행해 보지 못함]

curl -i http://localhost:8080/actuator/health/liveness
```

- readiness 는 DB 연결까지 봅니다. AI 는 넣지 않았습니다(AI 가 꺼져도 로그인은 돼야 해서). 운영 compose 의 healthcheck 도 이 주소를 씁니다.
- `/actuator/health` 전체 응답에는 AI 상태가 들어갑니다. AI 를 띄우지 않았으면 DOWN 이 나오는 것이 맞습니다.
- 상세 내용은 공개하지 않습니다(`show-details: never`).

API 문서는 <http://localhost:8080/api/swagger-ui.html> 입니다. 로그인은 같은 브라우저에서 <http://localhost:8080/api/auth/github/start> 로 합니다. 문서 주소 전체 목록은 [BE–AI 연결 문서](../docs/BE_AI_INTEGRATION.md#문서-주소와-사용법)에 있습니다.

## 자주 쓰는 명령

모두 `backend/` 에서 실행합니다.

| 명령 | 하는 일 |
|---|---|
| `docker compose up -d db` | 로컬 PostgreSQL 띄우기 |
| `docker compose down` | 로컬 DB·Redis 내리기 (데이터는 남음) |
| `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun` | 서버 실행 (포트 8080) |
| `./gradlew test` | 시험 전체 (Docker 필요) |
| `./gradlew test --tests '*ModuleBoundaryTest'` | 모듈 경계 시험만 (Docker 필요 없음) |
| `./gradlew build` | 컴파일 + 시험 전체. CI 가 돌리는 명령과 같습니다 |
| `./gradlew bootJar -x test` | 실행 jar 만 만들기. 운영 이미지(`Dockerfile`)가 이렇게 만듭니다 |

### 시험

- DB 를 쓰는 시험은 Testcontainers 로 `postgres:17-alpine` 컨테이너를 띄웁니다. Docker Desktop 이 켜져 있어야 합니다. 로컬 DB(`compose.yaml`)는 시험에 쓰지 않습니다.
- 시험 JVM 메모리는 1GB 입니다(`build.gradle`). 시험 설정(`src/test/resources/config/application.yml`)은 Job 워커를 끕니다.
- 시험 리포트는 `build/reports/tests/test/index.html` 에 생깁니다.

### 모듈 경계 시험

`ModuleBoundaryTest`(ArchUnit)가 모듈 사이 참조 규칙 11개를 검사합니다. 예를 들어 다른 모듈의 `infra` 를 참조하거나, `ingest`·`recommend` 에서 `agent`(LLM)를 부르면 실패합니다. 규칙과 이유는 [ARCHITECTURE.md](ARCHITECTURE.md#모듈-경계--테스트로-강제되는-것) 에 있습니다.

### OpenAPI 스냅샷 갱신

`OpenApiIntegrationTest` 가 지금 코드의 API 문서와 [docs/openapi/backend.json](../docs/openapi/backend.json) 을 비교합니다. 컨트롤러나 DTO 를 바꿔 이 시험이 실패하면, 아래 명령으로 다시 만든 뒤 함께 커밋합니다. 파일을 손으로 고치지 않습니다.

```bash
UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiIntegrationTest'
```

```powershell
$env:UPDATE_OPENAPI=1; ./gradlew test --tests '*OpenApiIntegrationTest'; Remove-Item Env:UPDATE_OPENAPI
```

이 시험은 PostgreSQL 컨테이너를 띄우므로 Docker 가 필요합니다. 서버를 띄운 상태에서 BE·AI 문서를 한 번에 내보내는 [scripts/export-api-docs.sh](../scripts/export-api-docs.sh) 도 있습니다.

### Flyway 마이그레이션 규칙

스키마는 Flyway 하나로만 바꿉니다. JPA 는 `ddl-auto: validate` 로 검사만 합니다.

- 새 변경은 새 파일 `V<n>__설명.sql` 로 추가합니다. `n` 은 지금 가장 큰 번호보다 커야 합니다.
- 이미 머지된 마이그레이션 파일은 고치지 않습니다. 그 버전을 적용한 서버에서 체크섬이 어긋나 배포가 멈춥니다.
- [backend CI](../.github/workflows/backend-ci.yml) 의 「마이그레이션 검사」가 기존 파일 수정을, 「마이그레이션 번호 순서 검사」가 작은 번호를 막습니다. 「마이그레이션 검사」는 develop 필수 체크입니다.

## 로그와 알림

| 어디서 | 어떻게 보나 |
|---|---|
| 로컬 `bootRun` | 터미널에 그대로 찍힙니다. 기본 INFO, `local` 프로필은 우리 코드와 SQL 이 DEBUG |
| 운영 서버 | `cd ~/gitory/deploy && docker compose logs backend --tail 100` |
| 운영, 여러 컨테이너를 함께 | CloudWatch 로그 그룹 `/gitory`, 스트림 `gitory-backend-1` |

- 운영 compose 는 컨테이너 로그를 Docker `awslogs` 드라이버로 CloudWatch 에 보냅니다. backend 는 날짜로 시작하지 않는 줄(예외 스택)을 앞 줄과 한 이벤트로 묶습니다.
- 알림: CloudWatch 지표 필터가 `ERROR` 줄과 Job 실패 코드(`INTERNAL_ERROR`·`GITHUB_UNAVAILABLE`·`AI_UNAVAILABLE`)를 세고, 경보가 SNS 로 팀 이메일에 보냅니다. 설정 절차는 [DEPLOYMENT.md 7절](../docs/DEPLOYMENT.md#7-운영) 에 있습니다.
- 서버가 뜰 때 `LLM 호출 제한이 켜져 있다/꺼져 있다` 한 줄이 남습니다. 운영에서 스위치가 들어갔는지 이 줄로 봅니다.
- 로그·오류 응답에 AI 응답 원문, GitHub 토큰을 남기지 않습니다. 어떤 장치가 이를 막는지는 [GUARDRAILS.md 2절](../docs/GUARDRAILS.md#2-spring-쪽-장치) 에 있습니다.

## 디렉토리 구조

```
backend/
├── build.gradle             의존성·Java 21·시험 설정
├── compose.yaml             로컬 PostgreSQL·Redis (운영 아님)
├── Dockerfile               운영 이미지. 실행은 deploy/compose.yaml
├── ARCHITECTURE.md          모듈 구조와 경계 규칙
├── docs/schema-v2.sql       V1+V2 를 합친 스키마 (대조용, Flyway 가 읽지 않음)
└── src/
    ├── main/java/com/gitory/backend/
    │   ├── agent/           LLM 을 부르는 유일한 모듈. 경험 그룹화·카드 초안·되묻기 질문을 AI 에 요청
    │   ├── audit/           접근·생성·철회 이벤트를 감사 로그로 남김
    │   ├── card/            근거와 답변을 STAR 카드로 묶고, 되묻기와 사용자 확인을 거쳐 확정
    │   ├── common/          응답 봉투·오류 코드·요청 id(api), AI HTTP 클라이언트·호출 제한(infra)
    │   ├── consent/         GitHub 연결·동의·분석 범위. 토큰은 이 모듈 밖으로 나가지 않음
    │   ├── ingest/          선택한 저장소의 커밋·PR·이슈를 수집해 분석용으로 정리
    │   ├── job/             분석·카드 초안을 비동기 Job 으로 돌리고 진행 상태를 알려 줌
    │   ├── matching/        기업 채용 공고를 가져와 조건을 저장하고 확정 카드와 맞춰 봄
    │   ├── recommend/       AI 경험 그룹화 결과로 후보를 저장하고, 사용자가 손댄 후보를 지킴
    │   └── usage/           화면이 보낸 사용 지표를 허용된 이름만 받아 저장
    ├── main/resources/
    │   ├── application.yml        기본 설정 (환경변수 이름과 기본값)
    │   ├── application-local.yml  로컬 프로필
    │   └── db/migration/          Flyway 마이그레이션 V1~
    └── test/
        ├── java/.../architecture/ ModuleBoundaryTest
        ├── java/.../support/      시험 픽스처
        └── resources/config/      시험용 설정 (Job 워커 끔)
```

모듈 안은 `api`(컨트롤러·DTO) · `domain`(엔티티·서비스) · `infra`(리포지토리·외부 클라이언트) 세 층이고, 일부 모듈에 `port` 가 있습니다. `common` 은 `api`·`infra` 만 있습니다.

다른 곳의 관련 문서:

- 프론트와의 API 계약: [frontend/docs/BACKEND_CONTRACT.md](../frontend/docs/BACKEND_CONTRACT.md)
- 운영 배포와 환경변수: [docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md), [deploy/compose.yaml](../deploy/compose.yaml), [deploy/.env.example](../deploy/.env.example)
- 브라우저로 끝까지 도는 시험: [e2e/README.md](../e2e/README.md)

## 문제가 생기면

### 시험이 컨테이너를 띄우지 못하고 실패할 때

Docker Desktop 이 꺼져 있는 경우입니다. 켠 뒤 `docker info` 가 답하는지 보고 다시 돌립니다.

### 로그인하고 돌아왔는데 로그인이 안 된 상태일 때

`local` 프로필 없이 띄웠을 수 있습니다. 기본 설정은 세션 쿠키가 `Secure` 라서 브라우저가 http://localhost 에서 쿠키를 저장하지 않습니다. `SPRING_PROFILES_ACTIVE=local` 로 다시 띄웁니다.

### GitHub 이 redirect_uri 가 맞지 않는다고 거절할 때

OAuth App 의 Authorization callback URL 이 `{baseUrl}/api/auth/github/callback` 과 같은지 봅니다. 접속한 주소(호스트·포트)까지 같아야 합니다.

### 서버가 Flyway 검증 오류로 뜨지 않을 때

머지된 마이그레이션 파일을 고쳤거나, 다른 브랜치에서 적용한 마이그레이션이 로컬 DB 에 남은 경우입니다. 파일을 되돌리는 것이 먼저입니다. 로컬 DB 를 비워도 되면 볼륨째 지우고 다시 띄웁니다. 로컬 데이터가 모두 지워집니다.

```bash
docker compose down -v
docker compose up -d db
```

### `OpenApiIntegrationTest` 만 실패할 때

API 모양이 바뀌었는데 스냅샷을 갱신하지 않은 경우입니다. 위 「OpenAPI 스냅샷 갱신」 명령을 돌리고 `docs/openapi/backend.json` 을 같이 커밋합니다.

### 호출 제한을 켰는데 `Redis 에 붙지 못해 … 호출 제한을 건너뛴다` 가 찍힐 때

Redis 가 떠 있지 않습니다. 요청은 막히지 않고 제한만 건너뜁니다. `docker compose up -d redis` 로 띄웁니다.

### 5432 포트가 이미 쓰이고 있을 때

다른 PostgreSQL 이 떠 있습니다. 그것을 멈추거나, `SPRING_DATASOURCE_URL`·`SPRING_DATASOURCE_USERNAME`·`SPRING_DATASOURCE_PASSWORD` 로 다른 DB 를 가리킵니다.

```bash
lsof -i :5432
```

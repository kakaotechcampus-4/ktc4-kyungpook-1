# 프론트 ↔ Spring 계약 (BE 인계용)

**실서버 동작은 병합된 Spring controller/service가 기준이고, FE 파싱 규약은 `src/api/schemas.ts`(zod)** 입니다. 이 문서는 구현된 경로와 프론트·Mock의 제안을 구분합니다.
프론트는 응답을 zod 로 파싱하므로, 여기 적힌 필드가 빠지거나 타입이 다르면 **첫 호출에서 `ContractError`** 로 드러납니다. 조용히 깨지지 않습니다.

카드 계약은 develop `333c109`과 [이슈 #183](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/issues/183), 병합된 [#162 직접 작성](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/pull/162), [#163 임시 저장](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/pull/163), [#175 이력·가리기](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/pull/175), [#172 되묻기 기록](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/pull/172)의 실제 구현을 확인했습니다(2026-10-12). 아래 레포·후보·Job·지표 표에는 기존 FE 기대 계약도 포함되므로 표 전체를 운영 구현 목록으로 읽지 않습니다. 카드 경로의 구현 상태는 카드 표에서 명시합니다.

## 공통 규칙

| 항목 | 규칙 |
|---|---|
| 접두사 | `/api` (프론트 `VITE_API_BASE`). 같은 오리진 리버스 프록시가 기본 |
| 봉투 | 모든 응답 `{ "data": …, "error": null }` 또는 `{ "data": null, "error": { "code", "message", "details"? } }` |
| enum | 전부 영문 대문자. 한국어는 프론트 `labels.ts` 만 안다 |
| 인증 | 세션 쿠키 (`credentials: 'include'`). 없으면 **401** → 프론트가 `/login` 으로 |
| CSRF | Spring Security `CookieCsrfTokenRepository.withHttpOnlyFalse()` 를 쓰면 프론트가 `XSRF-TOKEN` 쿠키를 읽어 `X-XSRF-TOKEN` 헤더로 자동 돌려줌 |
| 날짜 | ISO 8601 문자열 |
| id | 문자열 (숫자 PK 라면 문자열로 직렬화) |
| 호출 제한 | LLM 을 부르는 요청(되묻기 질문 `POST /cards/{id}/interview`, 수치 문장 미리 보기 `POST /cards/{id}/interview/{turnNo}/measured-draft`, 답 평가가 켜진 `POST /cards/{id}/interview/{turnNo}/answer`, 카드 초안이 켜진 `POST /repos/{id}/cards`)은 사용자별 1분·하루 한도를 넘으면 **429** `RATE_LIMITED`. `message` 는 "요청이 많아요. N분 뒤에 다시 시도해 주세요.", `details.retryAfterSec` 와 응답 헤더 `Retry-After` 에 기다릴 초가 같은 값으로 온다. 로그인·소유권·입력 검사(401·404·409·400)가 먼저이고, 거절된 요청은 아무것도 저장하지 않는다 |
| **에러가 아닌 것** | 후보 0개 · 부분 결과 · 작업 실패는 **200** + 본문 상태 (`verdict`/`partial`/`state`). 4xx/5xx 는 진짜 실패에만 |

`RATE_LIMITED`의 `retryAfterSec`가 60초 이하이면 분당 한도, 60초보다 길면 서울 기준 자정까지의 하루 한도 안내를 사용합니다(#335). 하루 한도는 "오늘 사용 한도를 다 썼어요 · 내일 다시 시도해 주세요"로 표시하며 즉시 재시도를 제공하지 않습니다. 초 값이 없거나 유효하지 않으면 짧은 대기 안내로 처리합니다. 어느 경우에도 LLM 요청을 자동 재전송하지 않습니다.

## OAuth (Spring 이 전부 처리)

```
GET  /api/auth/github/start          → 302 github.com/login/oauth/authorize (scope: public_repo read:user)
GET  /api/auth/github/callback?code= → 세션 생성 후 302 /            (프론트 /auth/callback 도 / 로 보냄)
                                        실패·취소 시 302 /login?error=access_denied
POST /api/auth/logout                → { data: { ok: true } } + 쿠키 만료
```
세션 만료로 401 이 나면 프론트가 `/login?reason=expired` 로 보내고, 재로그인 뒤 원래 화면으로 돌아옵니다(sessionStorage).

## 엔드포인트

### 사용자
| | 응답 `data` |
|---|---|
| `GET /me` | `Me` — `{ id, login, avatarUrl, plan:'FREE', github:{connected, scopes[], connectedAt, lastCollectedAt}, stats:{confirmedCards, analyzedRepos, interviewTurns, remainingCandidates} }` |
| `POST /github/disconnect` | `{ ok }` — 토큰 즉시 파기 |

### 인적사항 (#516, 구현됨)
| | 요청 | 응답 `data` |
|---|---|---|
| `GET /profile` | | `Profile` — 한 번도 저장하지 않았으면 네 목록이 `[]` 이고 `savedAt: null` |
| `PUT /profile` | `{ educations[], careers[], languages[], certifications[], interests[]? }` **네 목록 모두 필수**(없으면 `[]`). `interests` 는 보내면 통째로 바꾸고 안 보내면 그대로 둠 | `Profile` — 네 목록을 통째로 바꿈 |

```jsonc
// Profile — 요청 항목도 같은 모양(savedAt 빼고)이고, 비운 칸은 null
{ "educations": [{ "level": "HIGH_SCHOOL|ASSOCIATE|BACHELOR|MASTER|DOCTOR", "school": "경북대학교" | null, "major": "컴퓨터학부" | null,
                   "status": "ENROLLED|ON_LEAVE|GRADUATION_EXPECTED|GRADUATED|DROPPED_OUT",
                   "startMonth": "2021-03" | null, "endMonth": "2027-02" | null, "gpa": 3.8 | null, "gpaMax": 4.5 | null }],  // 5개까지
  "careers": [{ "company": "카카오", "role": "백엔드" | null, "type": "FULL_TIME|CONTRACT|INTERN|PART_TIME|FREELANCE",
                "startMonth": "2025-07", "endMonth": "2025-12" | null }],  // 10개까지, endMonth null = 재직 중
  "languages": [{ "test": "TOEIC|TOEIC_SPEAKING|OPIC|TOEFL|IELTS|TEPS|JLPT|JPT|HSK|OTHER", "testName": "TOPIK" | null,
                  "score": "IH", "acquiredMonth": "2025-05" | null }],  // 10개까지, testName 은 OTHER 일 때만 필수·저장
  "certifications": [{ "name": "정보처리기사", "issuer": "한국산업인력공단" | null, "acquiredMonth": "2026-06" | null }],  // 20개까지
  "interests": ["BACKEND", "DEVOPS"],   // 관심 분야 = 공고 직무 분류 이름(BACKEND·FRONTEND·MOBILE·DEVOPS·DATA·AI_ML·SECURITY·QA·ETC), 3개까지, 겹치지 않게. 아직 안 골랐으면 []
  "savedAt": "ISO" | null }
```

- 처음 들어올 때 `interests` 가 비어 있으면(전에 건너뛴 사용자 포함) 어느 화면이든 그 위에 입력 창을 띄우고, 저장해야 닫힌다(#656). '건너뛰기'는 없다. 창은 학력 구분·상태, 어학 시험+점수/등급, 자격증 이름만 받고 나머지 칸은 마이페이지 폼에서 더 적는다.
- **관심 분야(`interests`)**: 매칭 1단계가 이 직무의 공고만 후보로 올린다(레포 추천·전체 추천). 비어 있으면 거르지 않는다. 4개 이상·겹침·모르는 값은 400.
- 글은 앞뒤 공백을 지우고 50자까지(어학 점수는 20자), 빈 글은 null. 달은 `YYYY-MM`, 끝난 달이 시작한 달보다 앞서면 400. 학점은 만점(4.0·4.3·4.5·100)과 함께 보내거나 둘 다 null.
- 규칙을 어기면 400 `INVALID_REQUEST` 이고 `error.message` 에 "학력 1번째 학교 이름…" 처럼 목록과 순번을 적는다. CSRF 헤더 필요.
- 매칭 1차 필터(#532)와 자소서 AI(#484)가 이 값을 읽는다(아직 연결 전).

### 레포
| | 요청 | 응답 `data` |
|---|---|---|
| `GET /repos` | | `RepoSummary[]` — `{ id, owner, name, contribution:{mine, team, ratio, level:'NONE'|'PARTIAL'|'SHARED'|'MAJOR'}, prCount, reviewCount, language, activeFrom, activeTo, lastAnalyzedAt, countedAt, candidateCount, cardCount, recommended }`. **`countedAt` 이 `null` 이면 기여 개수(커밋·PR·리뷰)를 아직 한 번도 못 센 것**이라 개수 0 은 실제 0 이 아니다 — 프론트는 숫자 대신 "세는 중"을 보이고, "내 기여 0%"·"PR 0건" 경고와 PR 유무 필터에서 뺀다. 필드가 생략된 응답은 센 것으로 본다. 한 번도 센 적이 없는 사용자(처음 로그인)는 최근 push 된 저장소 20개만 세고, 나머지는 `countedAt: null` 로 두었다가 다음 `GET /repos` 때 센다 |
| `GET /repos/saved` | | `RepoSummary[]` — `GET /repos` 와 같은 모양. GitHub 을 부르지 않고 마지막으로 받아 둔 목록을 바로 돌려줘, `GET /repos` 를 기다리는 동안 먼저 그릴 때 쓴다. 받아 둔 목록이 없으면(처음 로그인) 빈 배열. 그사이 GitHub 에서 지웠거나 권한을 잃은 저장소가 남아 있을 수 있어 `GET /repos` 응답이 오면 그것으로 바꾼다. 커밋이 없다고 센 빈 저장소와 프로필 README 저장소(아이디/아이디)는 빠진다 |
| `GET /repos/{id}` | | `RepoDetail` = `RepoSummary` + `disclosure:{ reads[], skips[], estimatedSeconds }` (B2 사전 고지 문구는 서버가 만든다) |
| `POST /repos/{id}/analyze` | 헤더 `Idempotency-Key: <UUID v4>` | `{ jobId, state, pollAfterMs }` — 같은 키·같은 저장소 재요청 또는 같은 사용자의 같은 연결 저장소에 활성 Job이 있으면 기존 Job을 200/202로 반환. **같은 키를 다른 저장소에 사용하면 409 + IDEMPOTENCY_KEY_MISMATCH** (확정). FE는 409에 새 키를 만들어 자동 재요청하지 않는다. |
| `GET /repos/{id}/candidates` | | `CandidateBoard` (아래) |
| `POST /repos/{id}/candidates` | `{ title, summary, shas[] }` | `Candidate` (type `MANUAL`) |
| `POST /repos/{id}/cards` | `{ candidateIds[] }` | `{ jobId, cardIds[] }` — 후보 1개 = 카드 1장, DRAFT Job 1개. 한 요청에 8장까지이고, 중복을 뺀 후보가 9개 이상이면 아무것도 만들지 않고 **400** `INVALID_REQUEST`. AI 초안의 네 칸이 확인 필요 없이 모두 찬 카드는 Job 이 바로 `CONFIRMED` 로 두고(#568), 확인 필요 칸이나 빈 칸이 있는 카드만 `DRAFT` 로 남는다 — 화면은 `DRAFT` 카드에만 확정 버튼을 보여 준다 |
| `GET /repos/{id}/commits?q=` | | `RepoCommit[]` — `{ sha, message, path, at, url, candidateId }` (C5 커밋 찾기, 최대 20) |
| `GET /repos/{id}/materials` | | `RepoMaterials` (아래) — 분석이 끝난 뒤 '읽은 커밋·PR 전체 보기' 용. GitHub 을 부르지 않고 저장된 값만 읽는다. 분석 전이면 두 배열 모두 빈 배열 |
| `GET /repos/{id}/recall` | | `Recall` — `{ repoId, note, dirs:[{ path, filesChanged, myCommits, files[], question, options[] }] }` |
| `POST /repos/{id}/recall/answers` | `{ path, text }` | `Candidate` (type `MANUAL`, 근거 USER_STATED) |

**RepoMaterials**
```jsonc
{ "commits": [{ "sha": "앞 7자", "title": "메시지 첫 줄", "authoredAt": "ISO", "files": n,
                "pullRequestNumber": n | null,                        // 분석이 이 커밋을 묶은 PR. 묶을 PR 이 없거나 뺀 커밋이면 null
                "excluded": "BOT|MERGE_COMMIT|LOCKFILE_ONLY" | null }], // 분석에서 뺀 이유. 분석에 쓴 커밋이면 null
  "pullRequests": [{ "number": n, "title", "mergedAt": "ISO" | null, "commitCount": n }] }  // commitCount = 이 PR 에 묶인 내 커밋 수
```
- 이 저장소에 저장된 **내 커밋만** 분석에 쓴 것과 뺀 것을 함께 최근 순으로, PR 은 내 커밋이 묶인 것만 번호가 큰 순으로 담는다. 커밋을 PR 에 묶는 규칙은 분석과 같다.
- 나눠 받지 않고 한 번에 전부 온다. `GET /repos/{id}/commits?q=` 는 커밋 찾기(최대 20)용이라 이 목록에는 쓰지 않는다.

**CandidateBoard**
```jsonc
{ "repoId", "verdict": "OK|EMPTY|PARTIAL", "partial": bool, "retryAfterSeconds": n|null,
  "readCoverage": { "commitsRead", "commitsTotal", "prsRead", "prsTotal" } | null,
  "emptyReasons": ["내 커밋 3개 / 팀 183개", "커밋 메시지 평균 4글자", "PR 참여 없음"],   // EMPTY 일 때 3줄
  "candidates": [ Candidate ] }
```
**Candidate** `{ id, repoId, type:'PR'|'ISSUE'|'COMMIT_CLUSTER'|'MANUAL', status:'NEW'|'USED'|'EXCLUDED', ref, title, reason, meta:{commits, files, reviewComments, days, codeRatio}, weak, score, commits: ClusterCommit[]|null, usedByCardId }`
`ref` 는 `"#42"`(PR/ISSUE) 또는 날짜 `"2024-05-12"`(COMMIT_CLUSTER). 화면 라벨("PR #42" / "커밋 묶음")은 프론트가 만든다.
`ClusterCommit` `{ sha(full), message, path, at, included, url }`

| | 요청 | 응답 |
|---|---|---|
| `PATCH /candidates/{id}` | `{ status?: 'NEW'|'EXCLUDED', excludedShas?: string[] }` | `Candidate` — 제외/복원 · 군집 커밋 빼기(E-4) |

### Job (폴링 · 항상 200)

`GET /jobs/{id}` → `Job`

```jsonc
{ "jobId", "type": "ANALYZE|DRAFT", "state": "QUEUED|RUNNING|SUCCEEDED|FAILED|CANCELED",
  "partial": false,                                   // 부분 완료는 별도 상태가 아니다 → SUCCEEDED + partial:true
  "steps": [{ "key": "PR_REVIEW|COMMITS|COMPRESS|REASON", "state": "QUEUED|RUNNING|DONE|SKIPPED",
              "done": 0, "total": 0 }],               // 늘 4개. 순서는 아래 참고. 총량을 모르면 total: null
                                                      // FAILED·CANCELED 로 끝난 Job 의 RUNNING 은 중단된 단계, QUEUED 는 시작하지 못한 단계다(서버는 닫지 않는다)
  "activity": [{ "seq": 41, "step": "COMMITS", "kind": "PULL_REQUEST|COMMIT|GROUPED|DESCRIBED", ... }],  // 아래 참고. 없으면 []
  "errorCode": "GITHUB_UNAVAILABLE|GITHUB_RATE_LIMITED|GITHUB_UNAUTHORIZED|AI_UNAVAILABLE|COLLECTION_CAPPED|DRAFT_TIMEOUT|EVIDENCE_MISSING|INTERNAL_ERROR" | null,
  "retryable": bool | null,
  "retryAfterSec": n | null,                          // GITHUB_RATE_LIMITED 에서만 의미가 있다
  "startedAt", "updatedAt", "finishedAt": null,
  "result": { "repoId"?, "cardIds"?, "verdict": "OK|EMPTY|PARTIAL", "reasons": [] } | null,
  "pollAfterMs": 1000 }                               // 다음 폴링까지 기다릴 시간. 간격은 서버가 정한다(진행 중 Job 목록은 2000)
```

- **`activity` 는 AI 가 방금 읽거나 판단한 줄이다(#493).** 오래된 줄부터 오고, `seq` 는 Job 안에서 1부터 늘어나 이미 받은 줄을 가를 수 있다. 문장은 화면이 만든다.

  | `kind` | `step` | 값 | 화면 예시 |
  |---|---|---|---|
  | `PULL_REQUEST` | `PR_REVIEW` | `number`, `title`, `reviews` | #31 로그인 리팩터링 (리뷰 4건) |
  | `COMMIT` | `COMMITS` | `sha`(7자), `title`(메시지 첫 줄), `files`, `excluded`(`BOT`·`MERGE_COMMIT`·`LOCKFILE_ONLY`·`NOT_OWN`, 빼지 않았으면 null) | fix: 토큰 만료 처리 (파일 3개) |
  | `GROUPED` | `COMPRESS` | `units`(이번에 본 작업 수), `experiences`(만든 경험 수) | 작업 12개를 경험 4개로 묶었어요 |
  | `DESCRIBED` | `REASON` | `title`(경험 제목), `reason`(고른 이유) | 배포 자동화: 리뷰가 많이 오간 작업 |

  종류에 없는 칸은 null 이다. 읽기 줄(`PULL_REQUEST`·`COMMIT`)은 1초에 여러 개 지나가서 단계마다 최근 20줄만 오고, 판단 줄(`GROUPED`·`DESCRIBED`)은 모두 온다 — 고른 이유는 AI 한 번에 8개씩 한꺼번에 와서, 화면이 `seq` 로 아직 안 보인 줄을 골라 차례로 보여 줄 수 있다. 카드 초안 Job, Job 이 끝나고 10분 뒤, 서버가 다시 시작된 뒤에는 `[]` 이다.

- **단계 순서는 서버가 준 순서 그대로 그린다.** 분석(`ANALYZE`)은 AI 가 실제로 읽는 순서인 `PR_REVIEW → COMMITS → COMPRESS → REASON`, 카드 초안(`DRAFT`)은 `COMMITS → PR_REVIEW → COMPRESS → REASON` 이다.
- **분석 단계의 `done`/`total` 은 읽는 동안 실시간으로 바뀐다(서버 설정 `ANALYSIS_LIVE_PROGRESS_ENABLED`, 기본 켬).** 진행 중인 단계는 `RUNNING` + 지금까지 읽은 수/전체(전체를 아직 모르면 `total: null`), 앞 단계는 `DONE`, 뒤 단계는 `QUEUED` 다. 숫자는 저장소 전체가 아니라 **이번에 새로 읽는 양**이다 — 다시 분석하면 새 PR·커밋만 세고, 새로 읽을 게 없으면 `DONE` + `done: 0, total: 0` 이다. 갱신은 1초에 한 번 정도라 폴링마다 숫자가 조금씩 오른다.
- **분석 실패는 HTTP 오류가 아니다.** 폴링 응답은 늘 200 이고, 실패는 `state` 로 말한다.
- **후보 0개도 실패가 아니다.** `SUCCEEDED` + `result.verdict: "EMPTY"` + `reasons` 3줄.
- **남의 Job 은 403 이 아니라 404** — 존재 여부조차 알려 주지 않는다.
- 자동 재시도는 없다. 사용자가 버튼을 눌렀을 때만 다시 시작한다.
  `GITHUB_RATE_LIMITED` 일 때만 `retryAfterSec` 을 쓰고, 그 시간이 지나기 전까지 프론트가 버튼을 막는다.
- FAILED와 SUCCEEDED + partial 모두 **retryable === true**일 때만 재시도한다. false/null은 허용으로 추정하지 않는다. 오류 코드 이름만으로 재시도 여부를 추정하지 않는다.
- 2026-09-26 develop의 JobView는 GITHUB_UNAVAILABLE/GITHUB_RATE_LIMITED만 true이며 retryAfterSec/result는 아직 null이다. 향후 실제 대기 시간이 내려오면 finishedAt(없으면 updatedAt)부터 계산한다.
- **`COLLECTION_CAPPED`(제안 — 프론트가 먼저 받을 준비를 했다, 백엔드가 내려주면 켜진다)**: 수집 상한에 걸려 일부만 읽은 경우로, 실패가 아니라 `SUCCEEDED` + `partial: true` + `errorCode: COLLECTION_CAPPED` 이다(`GITHUB_RATE_LIMITED` 부분 결과와 같은 모양). 화면은 "한 번에 읽는 상한까지만 읽었어요"와 읽은 범위의 후보로 가는 버튼을 보이고, **`retryable` 이 `true` 일 때만** "이어 읽기"를 둔다(false/null 이면 버튼 자체를 숨긴다 — 상한은 기다려도 풀리지 않는다고 가정). `retryAfterSec` 은 쓰지 않는다. 이 코드가 오기 전에는 `errorCode` 가 null 인 부분 결과로 "일부 기록만 분석했어요"만 보인다. DB `partial_reason` 에도 같은 값이 들어가야 한다.
- **`GITHUB_UNAUTHORIZED`**: GitHub 이 토큰을 거절했거나(만료·취소) 쓸 토큰이 없어 분석하지 못한 경우로, `retryable: false` 다. 다시 시도가 아니라 GitHub 다시 연결을 안내한다. 이때 서버는 그 연결을 해제로 남겨 `/me` 의 `github.connected` 가 `false` 가 된다. 일시 장애는 지금처럼 `GITHUB_UNAVAILABLE` 이다.
- 활성 Job 중복 제약은 user_repository_id 기준이다. 동일 GitHub 저장소라도 사용자 A/B의 연결 행은 달라 서로 분석을 막지 않는다.

`GET /jobs?active=true` → `{ "jobs": [{ jobId, type, state, userRepositoryId, repoName, startedAt, pollAfterMs }] }`

진행 중(`QUEUED`·`RUNNING`)인 현재 사용자의 Job. 없으면 빈 배열 + 200.
백엔드 ActiveJobResponse의 `type`은 기존 응답에 포함돼 있다. 호환 응답에서 생략/null이면 프론트는 유형을 추정하지 않고 Job 상세 조회로 확인한다. 홈의 작업 목록과 `/jobs/{jobId}`는 같은 활성 Job 조회와 서버 `pollAfterMs`를 사용한다.
**프론트는 Job ID 를 브라우저에 저장하지 않는다.** 새로고침 복구는 URL 쿼리(`?job=`) 아니면 이 목록이다 —
그래야 다른 기기·다른 탭에서 시작한 작업도 잡히고, 캐시를 지웠다고 진행 중인 작업을 잃지 않는다.

`POST /jobs/{id}/cancel` → `Job`. 이미 끝난 Job 을 취소해도 오류가 아니라 현재 상태를 200 으로.

### 카드
| | 요청 | 응답 |
|---|---|---|
| `GET /cards` | 상태 쿼리 없음 | **구현됨.** 본인 카드 전체를 최근 수정순으로 반환하는 `CardSummary[]` — `{ id, kind, status, title, areas, techs, themes, versionNo, star:{S,T,A,R}, updatedAt }` + 부가 `{ sourceLabel, sourceType, period, evidenceCount, userStatedCount, repo }`. `repo`는 카드 상세의 `repo`와 같은 `{ id, owner, name }`이고, 저장소 없이 직접 쓴 카드는 null이다(#457). `activity`는 카드가 속한 활동 `{ id, name, type }`이고 활동 밖 카드는 null이다(#477). `repo`·`activity` 가 둘 다 값이 있는 카드는 없다. `areas`(분야, 최대 2개)·`techs`(기술, 최대 4개)는 AI 초안이 고른 중요한 순서이고 늘 배열이다. 직접 작성 카드·AI 초안이 꺼져 있거나 실패한 카드·태그가 생기기 전 카드는 빈 배열이다. 상태·태그 필터는 FE에서 적용 |
| `POST /cards/manual/draft` | `{ title, period, repoId|null, activityId|null }`, 헤더 `Idempotency-Key: <UUID v4>` | **구현됨.** `Card` — 빈 `USER_EDIT` v1 DRAFT. 같은 사용자·같은 키는 처음 만든 카드의 **현재** 상세를 반환 |
| `PATCH /cards/{id}/draft` | `{ situation, task, action, result }` **네 키 필수**, string/null | **구현됨.** `{ cardId, versionNo, situation, task, action, result, savedAt }` — 임시 저장 |
| `GET /cards/{id}` | | **구현됨.** `Card` (아래) |
| `DELETE /cards/{id}` | | **구현됨(#350).** `{ ok: true }` — 작성 중·확정 카드 모두 버전·문장·근거·되묻기까지 완전 삭제. 후보로 만든 카드면 후보가 `NEW` 로 돌아가 다시 고를 수 있다. 오류는 401 → 403 → 404(잘못된 UUID·없는 카드·이미 지운 카드·남의 카드) → 409 `INVALID_STATE`(초안을 만드는 중) 순 |
| `POST /cards/{id}/mask` | `{ rules:[{from,to}] }` | **구현됨.** `Card` — 원문 불변, 표시·내보내기용 |
| `POST /cards/{id}/reopen` | | **구현됨.** `Card` (DRAFT로). 이미 DRAFT면 no-op. 확정 카드도 다시 열지 않고 고칠 수 있어(#568) 화면은 쓰지 않아도 되고, 화면이 쓰지 않게 되면 지운다 |
| `GET /cards/{id}/versions` | | **구현됨.** `[{ versionNo, source, createdAt, summary, current }]` 최신순 |
| `GET /cards/{id}/versions/{no}` | | **구현됨.** `CardVersion` — 그 버전의 `{ versionNo, source, createdAt, situation, task, action, result }`(`Card.version` 과 같은 모양, 빈 칸 null, 가리기 없는 원문). 남의 카드·없는 버전 번호는 404 |
| `POST /cards/{id}/versions/{no}/restore` | | **구현됨.** `Card` — 과거 버전을 복사한 RESTORE 새 버전. 현재 버전이면 no-op |
| `POST /cards/{id}/draft/retry` | | **구현됨(#419).** `{ jobId }` — AI 초안을 채우지 못한 카드(`Card.draftFailure` 가 `GITHUB_UNAUTHORIZED`·`AI_UNAVAILABLE`·`INTERNAL_ERROR`)만 새 DRAFT Job 으로 다시 채운다. `GITHUB_UNAUTHORIZED` 는 GitHub 이 토큰을 거절한 실패라 다시 연결한 뒤 다시 만들어야 하고, 연결 없이 다시 만들면 같은 이유로 다시 실패한다. 사용자가 쓴 칸은 그대로. 실패가 없거나 1번 버전을 지났으면 409 `INVALID_STATE`, 확정 카드는 409 `CARD_CONFIRMED`, 초안이 켜져 있으면 하루 한도 1 (429) |
| `POST /cards/{id}/regenerate` | `{ field }` | **Mock 체험 전용.** 실서버 controller 없음. FE는 실제 API 모드에서 화면과 endpoint 모두 차단 |
| `POST /cards/{id}/confirm` | `{ edited, maskedFields[] }` | **FE 기대 계약·Mock. 실서버 controller 없음.** Mock `ConfirmResult` `{ cardId, status:'CONFIRMED', versionNo, usedCandidateIds[], remainingCandidates, repoId }`. S·A 없으면 422 `NOT_CONFIRMABLE`; 현재 버전을 확정 표시하고 같은 내용의 버전을 덧붙이지 않음 |

`POST /cards`와 `POST /cards/{id}/versions`는 실서버에 없는 미사용 경로여서 FE endpoint/hook과 Mock에서 제거했습니다. 직접 작성은 manual/draft → PATCH draft를 사용합니다. 이 계약 정리는 미구현 confirm·되묻기 생성/답변 등의 서버 경로를 추가하지 않습니다.

**직접 작성 시작 (`POST /cards/manual/draft`)**

- 제목은 공백만 있는 값을 거절하고 최대 256자, 기간은 최대 50자의 자유 글입니다. 빈 기간/null/공백만 있는 기간은 null입니다. 제목·기간의 U+0000은 400 `INVALID_REQUEST`입니다. 의미 있는 글의 앞뒤 공백은 보존합니다.
- `repoId`가 있으면 본인 연결 저장소를 먼저 확인합니다. 없거나 다른 사용자의 저장소는 404이고, 같은 키로 이미 만든 카드가 있어도 이 검사를 건너뛰지 않습니다.
- `activityId`도 같은 규칙으로 본인 활동을 확인합니다(#477). `repoId`와 함께 보내면 400 `INVALID_REQUEST`입니다. 활동은 [경험 카드 계약](2026-10-28-EXPERIENCE_CARDS.md#1-활동)의 `/activities`로 만듭니다.
- 키는 사용자 ID와 함께 식별합니다. 같은 키로 다른 제목·기간·저장소를 보내도 처음 카드의 현재 제목·기간·저장소·버전·상태를 반환합니다. FE는 화면 진입마다 UUID v4 하나를 만들고 실패 재요청에 재사용하며, 반환된 메타데이터를 미구현 PATCH metadata로 덮어쓰지 않습니다.
- 서버는 키가 없거나 공백이면 매번 새 카드를 만들 수 있습니다. 의미 있는 키는 최대 255자입니다. 분석 Job의 키와 직접 작성 키는 별도 범위입니다. Mock은 이 매핑을 Job과 함께 snapshot/hydrate에서 보존하고 reset에서 초기화합니다.

**가리기·이력 (`#175`)**

- `rules` 목록은 필수이며 최대 20개, from/to는 각 최대 100자입니다. from은 공백만 있으면 안 되고 to의 빈 문자열은 삭제 규칙으로 허용합니다. null 규칙·누락된 from/to·U+0000은 400 `INVALID_REQUEST`입니다. 원문을 수정하지 않습니다.
- 확정 카드도 다시 열지 않고 mask 를 바꾸며 확정 상태는 그대로입니다(#568). 규칙 값과 순서가 기존 목록과 같으면 버전과 수정 시각을 바꾸지 않습니다. 달라지면 원문·근거를 복사한 MASK 버전을 추가합니다.
- 과거 버전 restore는 RESTORE 버전을 추가한 뒤 그 글로 상태를 다시 정합니다(아래 임시 저장과 같은 규칙, #568). 현재 버전 restore는 확정 상태·시각·버전을 그대로 두고, 없는 번호는 404입니다. 마스킹 규칙은 카드에 속하므로 과거 원문 복원으로 이전 규칙까지 되돌리지 않습니다.
- summary는 빈 버전이면 `채운 칸 없음`, 아니면 `S · T · A · R 채움` 중 실제 채운 칸입니다. 첫 AI_DRAFT에만 ` · 원본(잠김)`, 확정된 버전에는 ` · 확정`을 붙입니다.

**카드 목록의 `star`**

`FILLED` · `EMPTY` · `NEEDS_REVIEW` 세 값. **현재 카드 버전의 실제 칸 상태를 서버가 판정해서 내려준다.**
프론트에 있던 "근거 수로 되짚어 추정하는" 로직은 지웠다 — 목록과 상세가 어긋나는 원인이었다.
목록의 sourceLabel/sourceType/period/evidenceCount/userStatedCount는 부가 정보다. 생략/null은 모름으로 보존하고 숨긴다. `MANUAL`이나 0을 기본값으로 넣지 않는다. 서버가 명시한 0만 실제 0건이다. 빈칸·확인 필요 필터는 `star`만 사용한다. 이 누락/0 기준은 [백엔드 확인 요청](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/pull/61#issuecomment-5969111285)에 함께 정리했다.

**제목·기간 수정 — #186 확정, #219 활성화 검증 (2026-10-15)**

기존 `PATCH /cards/{id}/draft`의 STAR 저장 규약을 확장하거나 바꾸지 않는다. 확정 경로는 `PATCH /cards/{id}/metadata`이며 `{ title?: string, period?: string | null, themes?: Theme[] }`에서 전달한 필드만 수정한다. period는 자유 문자열이고, 빈 글 `""`이 기간 삭제다. FE는 비운 기간을 `""`로 보낸다(#420). 기간 생략이나 `null`은 기존 값 유지이고(둘 다 바꿀 것이 없으면 400) 검증 실패는 400 `INVALID_REQUEST`다. 응답은 `{ cardId, title, period: string | null, themes, updatedAt }`이며 상세 `GET /cards/{id}`에도 period가 반환된다. 제목은 공백만 있는 값을 거절하고 소유권·DRAFT를 검사한다. `themes`(경험 카드 문항 유형, #482)는 0~2개·겹침 없음·아는 값만 받고 `[]` 가 비우기이며, 기술 카드에 붙이면 400 이다. 유형만 보낸 요청은 확정 카드도 바꾸고 updatedAt 을 바꾸지 않으며, 사용자가 한 번 고른 뒤로 AI 는 덮어쓰지 않는다. AI 는 되묻기를 스스로 끝낼 때와, 유형이 빈 경험 카드를 확정할 때 유형을 붙인다(확정 때 AI 가 늦거나 실패하면 빈 유형으로 확정). STAR·근거·현재 버전·AI 원본은 변경하지 않는다.

Mock 구현과 실제 API adapter/화면은 준비돼 있다. 실제 API 모드에서는 `VITE_CARD_METADATA_ENABLED=true`를 명시하기 전 호출하지 않으며, 계약과 서버 구현 확인 후 활성화한다. 기존 기간이 누락돼도 제목만 수정하면 기간 삭제를 전송하지 않는다. 자유 입력 기간을 날짜로 억지 변환하지 않는다. [요청 댓글](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/pull/61#issuecomment-5969111285)과 [작업 문서](2026-10-03-CARD_WORKFLOW.md)를 참고한다.

**임시 저장 (`PATCH /cards/{id}/draft`)**

- 브라우저(localStorage)에 카드 입력을 저장하지 않는다. 직접 작성 카드도, 편집 중인 DRAFT 도 서버에 둔다.
- 프론트는 입력이 멈춘 뒤 **2.5초 디바운스**로 한 번만 보낸다.
- 네 키를 모두 보내고 비운 칸도 null로 명시합니다. 누락된 키, 문자열/null 이외의 값, 칸당 1000자 초과, U+0000은 400 `INVALID_REQUEST`입니다. blank-only는 null로 정규화하고 의미 있는 글의 공백·줄바꿈은 그대로 저장합니다. 공백 판정은 JS 공백과 Java CardText의 U+001C–001F를 포함합니다.
- **정규화한 네 칸이 현재 글과 같으면 no-op**입니다. 버전 번호·버전 시각·카드 수정 시각을 바꾸지 않으며 savedAt은 기존 버전 시각입니다.
- **확정되지 않은 USER_EDIT만 같은 버전을 덮어씁니다.** AI_DRAFT·INTERVIEW·MASK·RESTORE 또는 확정 후 다시 연 버전 위의 첫 실제 변경은 USER_EDIT 새 버전입니다. 그 뒤의 변경은 같은 미확정 USER_EDIT에 저장합니다.
- 각 칸의 글이 분기 직전 버전과 정확히 같으면 그 칸의 근거·확신도·되묻기 턴 연결을 복사합니다. 취소로 같은 원문을 다시 보내도 이 정보가 돌아옵니다. 달라진 칸은 USER_STATED이며 이전 커밋 근거·확인 필요 표시는 붙이지 않습니다. null은 문장과 그 칸의 근거를 제거합니다.
- 확정된 카드도 다시 열지 않고 PATCH 합니다(#568). 저장한 글로 `status` 를 다시 정합니다 — 작성 중 카드는 네 칸이 모두 차고 확인 필요 칸이 없으면 `CONFIRMED`, 확정 카드는 상황이나 행동이 비면 `DRAFT`(확정 시각 null)로 돌아가고 그 밖에는 `CONFIRMED` 를 유지합니다. 화면이 자동 저장하므로 이렇게 바뀐 확정은 확정본(이력의 ` · 확정`)을 남기지 않습니다. 확정 버튼으로 확정한 과거 버전은 고정된 채 남고, 새 버전은 이 확정 표시를 상속하지 않습니다.
- 목록 updatedAt은 마지막 실제 수정 시각이고 최근 수정순입니다. savedAt ACK의 글·버전·시각은 FE 캐시에 먼저 반영하고, ACK에 없는 근거를 추정하지 않고 상세를 다시 조회합니다.

**`evidence.authoredBy`**

`AI` | `USER`. **AI 문장은 근거가 1개 이상 필요하고, USER 문장은 커밋·되묻기 근거가 없어도 허용된다.**
실제 `EvidenceResponse`는 COMMIT이면 AI, USER_STATED/USER_SELECTED이면 USER로 authoredBy를 매핑합니다. 별도 DB 컬럼을 요구하지 않습니다. COMMIT 근거는 연결된 커밋마다 한 줄이고, `sha`는 앞 7자리, `url`은 카드 저장소의 GitHub 커밋 주소입니다. 저장소가 없는 카드나 커밋을 찾지 못한 근거는 `url`(또는 `sha`)이 null이고, 커밋 근거의 `snippet`은 커밋 메시지 첫 줄, `committedAt`은 커밋 작성 시각, `changedFiles`는 바뀐 파일 수, `summary`는 카드 초안 때 AI가 그 커밋의 diff를 읽고 쓴 한 줄 요약이고, 모르면 null입니다(이 기능 전에 만든 카드는 summary가 null이라 snippet을 대신 보여 줍니다). 사용자 근거는 넷 다 null입니다. 근거는 칸마다 AI가 인용한 순서로 내려갑니다. FE는 null인 근거 링크나 턴 번호를 만들어 내지 않습니다. Mock 시드의 알려진 링크·턴 정보는 데모 데이터입니다.
API 로 나가는 `evidence_type` 은 대문자 `COMMIT` · `USER_STATED` · `USER_SELECTED` 만 쓴다 —
`user_written`, `inferred` 같은 소문자 내부값은 외부로 내보내지 않는다.

**Card**
```jsonc
{ "id", "kind": "TECH|QUALITATIVE", "status": "DRAFT|CONFIRMED", "title",
  "areas": ["BACKEND|FRONTEND|MOBILE|DEVOPS|AI|DB|TEST|DOCS"],  // 최대 2개, 없으면 []
  "techs": ["C|CPP|CSHARP|JAVA|KOTLIN|PYTHON|GO|RUST|SWIFT|DART|TYPESCRIPT|JAVASCRIPT|REACT|SQL|DOCKER|GITHUB_ACTIONS"],  // 최대 4개, 없으면 []
  "themes": ["COLLABORATION|CONFLICT|PROBLEM_SOLVING|LEADERSHIP|CHALLENGE|FAILURE|GROWTH"],  // 경험 카드의 문항 유형 최대 2개, 기술 카드·없으면 []
  "period": "자유 글" | null,
  "repo": { "id", "owner", "name" } | null,
  "activity": { "id", "name", "type": "CLUB|EXTERNAL|CONTEST|INTERNSHIP|EDUCATION|PART_TIME|VOLUNTEER|STUDY|OTHER" } | null,  // repo 와 함께 값이 있는 카드는 없다
  "candidate": { "id", "type", "ref", "title" } | null,
  "version": { "versionNo", "source": "AI_DRAFT|USER_EDIT|INTERVIEW|MASK|RESTORE", "createdAt", "situation", "task", "action", "result" },  // 칸은 null 가능 = 비운 칸
  "evidence": [{ "field": "S|T|A|R", "type": "COMMIT|USER_STATED|USER_SELECTED", "authoredBy": "AI|USER", "sha": "7자리" | null, "url": "링크" | null, "snippet": "글" | null, "committedAt": "ISO" | null, "changedFiles": n | null, "summary": "글" | null, "turnNo": n | null }],
  "lowConfidenceFields": [{ "field", "why" }], "droppedFields": [{ "field", "reason": "NO_EVIDENCE|OVERCLAIM|UNSOURCED_NUMBER|TIMEOUT" }],
  "draftSteps": [{ "round", "actor", "kind", "field", "code", "value", "material", "reason" }],  // 아래 'AI 가 한 일', 없으면 []
  "maskRules": [{ "from", "to" }], "generation": { "jobId", "partial" } | null, "interviewTurns",
  "interviewEndReason": "글" | null,  // 아래 되묻기 'AI 판단 이유'
  "confirmedAt", "createdAt" }
```
`task: null`은 비운 칸입니다. 현재 실서버 CardResponse는 candidate/generation을 null, droppedFields를 빈 배열, interviewTurns를 0으로 반환합니다. Mock은 데모의 실제 후보·생성 Job·되묻기 횟수·빈 칸 이유를 추가로 보존합니다. `generation != null`이면 프론트는 D1(생성 중)을 그리고 Job을 폴링합니다.

**AI 가 한 일 (`draftSteps`, #479)** — 카드 상세에만 있고 목록에는 없다. AI 초안이 거친 단계를 순서대로 담고, 직접 쓴 카드·이 기능 전에 만든 카드는 `[]` 다. 사용자가 고쳐 버전이 늘어도 초안을 만든 과정이라 그대로 오고, 실패한 초안을 다시 만들면(`POST /cards/{id}/draft/retry`) 성공한 그 시도의 단계가 온다. 서버는 값만 보내고 문장은 화면이 만든다.

| 칸 | 값 |
|---|---|
| `round` | 0 = 첫 초안과 첫 검사, 1 부터 = 고쳐 쓴 바퀴(최대 5) |
| `actor` | `MODEL`(AI 가 함) · `CODE`(코드가 함) |
| `kind` | `DRAFT` · `FLAG` · `OPEN` · `RETRY` · `KEEP` · `REWRITE` · `PASS` · `REVERT` |
| `field` | `S·T·A·R`, 카드 전체 단계(`DRAFT`)는 null |
| `code` | `FLAG` 의 이유: `NO_EVIDENCE` · `UNSOURCED_NUMBER` · `REVERSED_MEANING` · `CODE_NAME`(프로젝트 안 이름) · `EMPTY`. `REVERT` 는 되돌리게 만든 새 문제이고 다시 쓴 칸이 비었으면 `EMPTY`(모르는 값이면 이유 없이 '문제 발견'으로) |
| `value` | 걸린 수치(예: `50%`)나 연 자료 제목(예: `이슈 #98 세션 만료 시 작성 글 사라짐`), 없으면 null |
| `material` | `OPEN` 일 때 `ISSUE`(PR 에 연결된 이슈) · `REVIEW`(다른 사람이 단 리뷰·줄 댓글) · `OWNER_REPLY`(카드 주인이 리뷰에 단 답 댓글), 그 밖에는 null |
| `reason` | AI 가 고른 행동(`OPEN`·`RETRY`·`KEEP`)의 이유 한 줄(60자 이내), `actor: CODE` 면 null |

문장 예시(그대로 써도 되고 다듬어도 된다):

| kind | 예시 |
|---|---|
| `DRAFT` | GitHub 기록으로 초안을 썼어요 — 서버는 PR 본문을 썼는지 알려 주지 않으니 후보 종류와 상관없이 같은 문장을 쓴다 |
| `FLAG` | {field}칸: 근거 없는 숫자({value}) / 근거 커밋 없음 / 원문과 뜻이 다를 수 있음 / 프로젝트 안 이름({value}) / 비어 있음 |
| `OPEN` | {field}칸: {value} 을(를) 열어 봤어요 — "{reason}" |
| `RETRY` | {field}칸: 자료 없이 고쳐 쓰기로 했어요 — "{reason}" |
| `KEEP` | {field}칸: 그대로 두기로 했어요 — "{reason}" |
| `REWRITE` | {field}칸을 다시 썼어요 |
| `PASS` | {field}칸 다시 검사: 통과 |
| `REVERT` | {field}칸: 다시 쓴 글이 더 나빠 앞 글로 되돌렸어요 |

`reason` 은 AI 가 쓴 글이라 카드 문장과 섞지 말고 'AI 의 판단' 으로 구분해 보여 준다. AI 쪽이 단계를 보내는 PR 이 머지되기 전까지는 늘 `[]` 다. 그 뒤 운영은 걸린 칸을 코드 검사로 고쳐 쓰므로 `DRAFT` · `FLAG` · `REWRITE` · `PASS` · `REVERT` 가 오고, `OPEN` · `RETRY` · `KEEP` 은 AI 가 칸마다 자료를 열지 고를 때 온다. `REVERT` 는 그것을 저장하는 서버 변경이 머지되기 전에는 그 단계만 빠진 채 온다. 나중에 초안을 만드는 동안 실시간으로 보여 줄 때도 같은 모양의 단계를 쓴다.

### 되묻기
되묻기는 AI 가 물을 칸을 골라 이어서 묻는 흐름으로 바뀐다(#321, 계약은 #363). 아래 표의 '바뀔 것' 은 구현(#360·#361·#362) 뒤에 실서버에 들어간다.

| | 요청 | 응답 |
|---|---|---|
| `GET /cards/{id}/interview` | | **구현됨(#172).** `InterviewTurn[]`, 질문 번호순. 없으면 빈 배열; 없는 카드·남의 카드·잘못된 UUID는 404 |
| `POST /cards/{id}/interview` | `{ field? }` | **구현됨 · 바뀔 것.** `InterviewTurn \| null`. `field` 를 비우면 AI 가 물을 칸을 고르고, 더 물을 게 없다고 판단하면 **200 + `null`** 이다. `field` 를 주면 그 칸을 묻는다(끝을 고르지 않는다). 상한 초과 409 `INTERVIEW_CAP`, 확정 카드 409 `CARD_CONFIRMED`, 더 물을 수 없는 칸을 지정하면 409 `INTERVIEW_FIELD_CLOSED`. 이렇게 되묻기가 끝나(`null`) 네 칸이 확인 필요 없이 모두 차 있으면 서버가 카드를 `CONFIRMED` 로 바꾸므로(#568) 화면은 카드를 다시 읽는다 |
| `POST /cards/{id}/interview/{turnNo}/answer` | `{ kind, text?, source? }` | **구현됨 · 바뀔 것.** `Card`. 답을 저장만 하고 다음 질문은 만들지 않는다 — 화면이 저장 직후 `POST /cards/{id}/interview`(field 없이)를 이어서 부른다. 답으로 네 칸이 확인 필요 없이 모두 차면 되묻기 끝을 기다리지 않고 답 저장과 함께 `CONFIRMED` 로 바꾼다(#624) — 화면은 응답 `status` 가 `CONFIRMED` 면 확정 안내를 보이고 더 묻지 않는다 |
| `POST /cards/{id}/interview/{turnNo}/polished-draft` | `{ text }` | **새로 만듦(#482).** `{ sentence, polished, unsourcedNumbers: string[] }` — 경험 카드 글 질문의 답 다듬기 미리 보기. **아무것도 저장하지 않고** 질문 상한에 세지 않는다. AI 는 표현만 다듬고, 다듬은 문장에 답에 없는 숫자가 생기면 한 번 고쳐 쓰게 한 뒤에도 남은 숫자를 서버가 견줘 `unsourcedNumbers` 로 알린다. AI 가 응답하지 못하면 오류가 아니라 `polished: false` 와 답 그대로의 `sentence` 다. 오류 순서는 `measured-draft` 와 같고, 경험 카드의 글 질문이 아니면 400 `BAD_FIELD` 다 |
| `POST /cards/{id}/interview/{turnNo}/measured-draft` | `{ text }` | **새로 만듦.** `{ sentence }` — '재 봤어요' 미리 보기. **아무것도 저장하지 않는다.** 입력에 수치(아라비아 숫자)가 없으면 AI 를 부르지 않고 `sentence: null` 이고, 화면은 "수치를 찾지 못했어요. 대략이라도 적어 주세요" 를 보인다. 질문 상한에 세지 않는다 |

**답 종류 `kind`**
| kind | 화면 | `text` · `source` | 카드 칸 |
|---|---|---|---|
| `TEXT` | 입력 칸 + [저장] | 필수 · `USER_SELECTED`(보기를 그대로) 또는 `USER_STATED`(고침) | 질문한 칸에만 새 버전(INTERVIEW)으로 반영 |
| `MEASURED` | [재 봤어요] → 미리 보기 → 확정 | 필수(확정 문장) · 위와 같은 규칙 | R 칸에 반영. R 이 커밋으로 확인된 문장이었으면 저장 전에 화면이 "이 칸의 근거가 커밋에서 내 답으로 바뀝니다" 를 알린다 |
| `NOT_MEASURED` | [측정 안 했어요] | 있어도 되고 없어도 됨(숫자 없는 R 문장 보기를 고르거나 고친 것) | R 이 커밋으로 확인된 문장이면 칸을 바꾸지 않고 기록만 한다. 빈 R·확인 필요 R 이면 `text` 가 있을 때 반영 |
| `SKIP` | [넘기기] | 없음 | 바꾸지 않는다 |

- `kind` 를 보내지 않으면 `TEXT` 로 본다(FE 가 바꾸는 동안의 하위 호환).
- 경험 카드의 다듬은 문장을 저장할 때는 `{ kind: 'TEXT', text, source: 'USER_STATED', original }` 로 다듬기 전 답을 함께 보낸다(1,000자까지, 공백뿐이면 남기지 않음). TEXT 답에만 남고, 지난 질문 목록의 `answer.original` 로 '내 답 → 다듬은 문장' 을 다시 보여 준다. 다듬지 않고 저장한 답은 `null` 이다.
- `kind` 가 질문의 버튼과 맞지 않으면(`answerMode: TEXT` 질문에 `MEASURED`·`NOT_MEASURED`) 400 `INVALID_REQUEST` 이고 아무것도 저장하지 않는다.

**오류 순서**
- `POST /cards/{id}/interview`: 404(카드 없음) → 409 `INVALID_STATE`(초안 만드는 중) → 409 `CARD_CONFIRMED` → 답을 기다리는 질문이 있으면 그 질문을 200 으로 돌려준다 → 409 `INTERVIEW_CAP` → 400 `BAD_FIELD` → 409 `INTERVIEW_FIELD_CLOSED`.
- `POST /cards/{id}/interview/{turnNo}/measured-draft`: 404(카드·질문 없음) → 409 `INVALID_STATE`(초안 만드는 중) → 409 `CARD_CONFIRMED` → 409 `INVALID_STATE`(이미 결과가 정해진 질문) → 400 `BAD_FIELD`(R 수치 질문이 아님) → 400 `INVALID_REQUEST`(빈 글·너무 긴 글) → 429 `RATE_LIMITED`.

`InterviewTurn`
`{ turnNo, field, askedBy:'USER_REQUEST'|'FOLLOW_UP', sourceType:'PR'|'COMMIT_CLUSTER'|'DIRECT_CARD',
   questionType:'EVIDENCE_GAP'|'FOLLOWUP'|'RECALL_AID', found[], missing[], question, reason|null, options[],
   answerMode:'TEXT'|'MEASURE', outcome:'ANSWERED'|'MEASURED'|'NOT_MEASURED'|'SKIPPED'|null,
   answer:{text, source, original}|null, remaining, maxTurns }`

- `answerMode` 는 보일 버튼이다. `MEASURE`(R 수치 질문) = [재 봤어요] [측정 안 했어요] [넘기기], `TEXT` = 입력 칸 + [저장] [넘기기].
- `outcome` 은 질문이 어떻게 끝났는지다(답 전 `null`). 지난 질문 목록에 '넘김'·'측정 안 함' 을 보일 때 쓴다.
- `options` 는 AI 가 쓴 **예상 답변**이다. 지금처럼 누르면 입력 칸에 채우고, 그대로 보내면 `USER_SELECTED`, 고치면 `USER_STATED` 다. 앞 답에 다른 칸에 쓸 내용이 있으면 그 칸 질문의 예상 답변이 된다. 현재 실서버 GET 응답의 found/missing/options 는 아직 빈 배열이다.

- **PR 번호가 없는 커밋 묶음과 직접 작성 카드도 되묻기 대상이다.** `sourceType` 이 어느 쪽인지 말한다.
- **물을 칸은 AI 가 고른다.** 첫 질문은 규칙(R 에 확인된 수치가 없으면 R)이고, 그 뒤는 앞 답·카드 상태를 읽은 AI 가 '같은 칸 다시 / 다른 칸 / 끝' 을 고른다. 답은 질문한 칸에만 들어간다.
- **서버가 지키는 규칙:** 같은 칸은 2번까지 · `NOT_MEASURED`·`SKIP` 으로 끝난 칸은 다시 묻지 않는다 · 넘긴 질문도 상한에 센다.
- **닫힌 칸:** 지난 질문 목록(`GET /cards/{id}/interview`)에서 같은 `field` 가 2번 있거나 `outcome` 이 `NOT_MEASURED`·`SKIPPED` 인 칸이다. 그 칸의 [이 칸 질문 받기]를 숨길지, 흐리게 하고 이유(넘김·측정 안 함·2번 물음)를 짧게 보일지는 화면 디자인을 고려해 FE 가 정한다. 서버는 닫힌 칸 지정을 409 `INTERVIEW_FIELD_CLOSED` 로 막는다(탭 두 개·오래 열어 둔 화면).
- **질문 상한은 서버 정책이다.** 응답의 `maxTurns` 로 내려주고(카드당 8, 네 칸을 칸마다 두 번까지), 프론트는 그 값만 쓴다.
- 답변 출처 이름(`USER_SELECTED`·`USER_STATED`)은 화면에 노출하지 않는다 — "보기에서 고른 것", "내가 말한 것"으로 번역해 보여 준다.

**AI 판단 이유 (`reason`·`interviewEndReason`, #480)** — AI 가 다음 칸을 고르거나 그만 묻기로 한 이유 한 줄(60자 이내)이다. AI 가 쓴 글을 비밀값만 가려 그대로 보내므로, 카드 문장과 섞지 말고 'AI 판단' 으로 구분해 보여 준다(#430).
- 질문의 `reason`: 그 칸을 묻는 이유. 앞 답이 모자라 같은 칸을 다시 물으면 무엇이 모자랐는지 적는다. 사용자가 `field` 로 칸을 골랐거나 AI 가 실패해 틀 질문을 썼으면 `null` 이고, 지난 질문 목록에도 같은 값이 온다.
- 카드의 `interviewEndReason`: AI 가 그만 묻기로 해 `POST /cards/{id}/interview` 가 `null` 을 돌려줬을 때의 이유다. 화면은 `null` 을 받으면 카드를 다시 읽어 보여 준다. 그 뒤 새 질문을 만들면 `null` 로 지운다. 고를 칸이 없거나 상한에 닿아 AI 를 부르지 않고 끝난 경우는 바뀌지 않는다.
- 화면 예: 질문 위 "AI 판단: 앞 답에 결과 수치가 없어 R 을 다시 물어요", 끝날 때 "AI 판단: 네 칸 모두 근거가 있어 질문을 마칠게요".

### 기업·직무 매칭 (구현됨 · 10/24 운영 켬)

> **개편 예정** — 레포 분석 뒤 그 레포에 맞는 기업, 공고 페이지의 '내 조건으로 매칭하기'(탑 기업 3~5곳)와 기업별 목록, 공고 상세의 '이 공고와 매칭하기', 공고에서 자소서로 이어지는 새 흐름은 [매칭 개편](2026-10-30-MATCHING.md)에 있다(흐름 확정 · API 미정). 그 문서의 API 절이 채워지기 전까지 아래 계약과 지금 화면은 그대로다.

[#199](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/issues/199)에서 정한 기준이다. 서버는 `GET /api/matches`·`GET /api/matches/{id}` 를 제공한다. FE 는 실제 API 모드에서 `VITE_MATCH_ENABLED=true` 일 때만 화면을 연다(운영은 #403 에서 켬).

| | 응답 `data` |
|---|---|
| `GET /matches` | `MatchTarget[]` — **AI 를 부르지 않는다.** 모든 공고의 `fit` 은 null, `required`·`preferred` 의 `met` 은 0 이고 `total` 은 공고의 조건 수다(#581). 아래 정렬 규칙대로. 마감이 지난 공고, 회사 채용 목록에서 사라진 공고, 등급을 셀 필수·우대 조건이 하나도 없는 공고는 넣지 않는다. `?companyId=` 를 주면 그 회사 공고만 오고(없는 회사면 빈 배열), 비어 있으면 거르지 않는다 |
| `GET /matches/{id}` | `MatchDetail` = `MatchTarget` + `experience.quote`·`status` · `needsJudgement` · `summary` · `conditions[]`. **AI 를 부르지 않고 저장된 판단만 읽는다.** 없는 id · 마감이 지난 공고 · 사라진 공고 · 필수·우대 조건이 없는 공고는 404 `NOT_FOUND` |
| `POST /matches/{id}/judgement` | 같은 `MatchDetail`. 아직 판단하지 않은 (카드, 조건) 짝만 AI 에게 묻고(최대 90초) 새 상세를 돌려준다. 물을 짝이 없으면 AI 를 부르지 않고 호출 한도도 세지 않는다 |

**MatchTarget**
```jsonc
{ "id", "company", "companyId", "logoUrl": "https://…" | null,   // companyId = 같은 회사의 공고를 묶는 id
  "role",                                          // role = 공고 원문의 직무명
  "category": "BACKEND|FRONTEND|MOBILE|DEVOPS|DATA|AI_ML|SECURITY|QA|ETC",
  "fit": "A|B|C|D" | null,                         // 목록·상세 모두 늘 null(등급을 매기지 않음)
  "required": { "total", "met" }, "preferred": { "total", "met" },
  "experience": { "level": "ENTRY|EXPERIENCED|UNSPECIFIED", "minYears": n | null },
  "source": { "url", "collectedAt", "expiresAt": "ISO" | null } }   // expiresAt null = 마감일 없음(아래 참고)
```

**MatchDetail** 에 더하는 칸
```jsonc
{ "experience": { "level", "minYears", "quote": "경력 원문" | null,
                  "status": "MET|NOT_MET|UNKNOWN" | null },        // 인적사항 경력과 견줌. 연수 요구가 없으면 null
  "needsJudgement": true | false,                                   // true = 아직 맞춰 보지 않은 조건이 있음
  "summary": { "requiredMet", "requiredTotal" } | null,             // 기술·경험 필수 조건만. needsJudgement 면 null
  "conditions": [{ "id", "type": "REQUIRED|PREFERRED|RESPONSIBILITY", "text", "quote",   // quote = 공고 원문 인용
                   "generalType": "EDUCATION|MILITARY|LANGUAGE|CERTIFICATE|LOCATION|OTHER" | null,   // null = 기술·경험 조건·담당 업무
                   "status": "MET|NOT_MET|UNJUDGED|UNKNOWN|CHECK" | null,
                   "metBy": [{ "cardId", "cardTitle", "cardKind": "TECH|QUALITATIVE", "repoId": "…" | null, "reason" }] }] }
```

- **매칭 기준은 공고 조건이다.** 인재상 태그는 쓰지 않는다. 조건은 필수(REQUIRED) → 우대(PREFERRED) → 담당 업무(RESPONSIBILITY) 순이고, 같은 종류 안에서는 원문 순서다.
- **기술·경험 조건의 `status` 는 확정 카드로 AI 가 판단한 결과다.** MET 이면 채운 카드가 `metBy` 에 오고 `reason` 은 그 카드가 조건을 채운 이유다(`repoId` 는 카드가 나온 레포, 직접 쓴 카드는 null). NOT_MET 은 채운 카드가 없음, UNJUDGED 는 아직 맞춰 보지 않았거나 그 뒤 카드 글이 바뀐 것이다. 판단은 (카드, 카드 글, 조건) 마다 저장해 레포 추천·전체 추천·상세가 같이 쓰므로, 카드 글이 바뀌기 전에는 다시 묻지 않는다.
- **일반 조건은 `generalType` 으로 구분한다.** 화면은 같은 NOT_MET 이어도 기술·경험 조건('맞는 카드가 없음')과 학력('인적사항과 맞지 않음')을 이 칸으로 나눠 쓴다.
- **학력은 인적사항과 견준다** — MET · NOT_MET · UNKNOWN(인적사항에 학력이 없음). '또는 그에 준하는 경력' 처럼 예외가 적힌 학력은 못 미쳐도 CHECK 다. 병역·어학·자격증·지역 같은 일반 조건은 CHECK(직접 확인)이다. `summary` 는 일반 조건을 세지 않는다.
- **등급(`fit`)은 상세에서도 null 이다.** `required`·`preferred` 의 `met` 은 상태가 MET 인 조건 수다.
- **확정 카드가 하나도 없으면** 기술·경험 조건은 모두 NOT_MET 이고 `needsJudgement` 는 false 다. 확정 카드 수는 내 정보 `stats.confirmedCards` 로 본다.
- **담당 업무는 상세에서 '하게 될 일' 로 보여 준다.** 판단하지 않는다(`status` null, `metBy` 빈 배열).
- **경력은 원문에 적혀 있을 때만 나온다.** `ENTRY` = 신입·경력 무관·인턴·최소 0년(상한만 있는 '1년 미만'·'3년 이하' 도 포함), `EXPERIENCED` = 최소 N년(`minYears`), `UNSPECIFIED` = 경력 얘기가 없거나 원문으로 확인할 수 없을 때. 경력 조건은 `conditions` 에 넣지 않는다.
- **정렬**: 목록은 등급이 없으므로 마감이 7일 안에 남은 공고를 마감이 가까운 순으로 앞에 두고, 나머지는 `expiresAt` 이 가까운 순을 지키며 회사마다 한 건씩 돌아가며 놓고, 마감일이 없는(null) 공고는 뒤로 내려준다. 화면의 '마감 순' 보기는 받은 목록을 다시 정렬한다.
- **필터(경력: 전체·신입 가능·경력·경력 표시 없음, 직무 분류)도 화면에서 한다.** '경력 표시 없음'은 `experience.level` 이 `UNSPECIFIED` 인 공고라, 경력 조건을 알 수 없는 공고를 따로 걸러 볼 수 있게 한다. 서버 쿼리 파라미터는 기업 페이지용 `companyId` 하나뿐이다. 정렬·필터를 바꿀 때마다 서버에 다시 물으면 매칭을 다시 계산하기 때문이다.
- **회사(`companyId` · `logoUrl`)**: `companyId` 는 서버 회사 설정의 키(예: `daangn`)이고, 여러 회사의 공고를 주는 출처(로켓펀치)는 `rocketpunch:회사이름` 이다. 기업별로 묶을 때 회사 이름 대신 이 값을 쓴다. `logoUrl` 은 회사 설정에 적은 공식 로고 https 주소이고, 없으면 null 이라 화면은 이니셜을 보인다. 이미지를 불러오지 못할 때도 이니셜로 되돌린다.
- **마감일(`source.expiresAt`)**: null 은 '마감일 없음' 이다. 출처가 상시 채용이라 마감일을 주지 않은 공고, 출처가 준 날짜를 읽지 못한 공고, 1년 넘게 먼 날짜를 준 공고가 함께 null 이라 화면은 '상시 채용' 보다 '마감일 없음' 이 맞다. 마감된 공고는 회사 채용 목록에서 사라지면 다음 가져오기에서 목록에서 빠진다.
- **회사 이름(`company`)**: 같은 `companyId` 면 이름도 늘 같다. 회사 설정 출처는 설정의 이름 하나를 쓰고, 로켓펀치는 회사 이름이 `companyId` 안에 들어 있다.
- **오류**: 목록은 401 `UNAUTHENTICATED` 뿐이다. 상세는 401 · 404 `NOT_FOUND` 이다. 맞춰 보기는 401 · 404 · 429 `RATE_LIMITED`(1분 10번 · 하루 100번) · 503 `AI_UNAVAILABLE`(AI 판단이 잠시 실패, 다시 누를 수 있다)이다.
- **응답 예시**: `frontend/docs/examples/matching/` — `list.json`(등급을 셀 때의 예시로, 지금 목록 응답과 값이 다르다) · `list-no-cards.json`(지금 목록 응답과 같은 모양 — `fit` null, `met` 0) · `detail.json`(맞춰 본 뒤의 상세) · `errors.json`(401·404·503). 회사·공고는 지어낸 예시다. 서버 API 가 생기기 전까지 목 데이터로 쓰고, 서버 목록·상세 API 를 만들 때 실제 응답이 이 예시와 같은 모양인지 시험으로 확인한다.
- 한 줄 소개(`summary`), 인재상 태그(`matchedTags`·`gapTags`), 카드 수 기준 칸(`supportedTagCount`·`independentTagCount`)은 없다. `source.verifiedAt` 대신 `source.collectedAt`(공고를 가져온 시각)이다.

### 아직 서버 쪽에 남은 일 (FE 기대 계약)
- 단계별 마지막 완료 지점을 Job 에 저장해, 재시도 때 처음부터 다시 돌리지 않고 이어서 처리하기.
- 카드 칸 다시 만들기(`POST /cards/{id}/regenerate`). 서버에 아직 없어 FE 는 실제 모드에서 capability 를 확인한다. (confirm·metadata·되묻기 생성/답변·매칭 목록·상세는 구현되었다.)

### 지표
`POST /events` `{ name, props, at, path }` → `{ ok }`. 이벤트 이름은 `src/lib/track.ts` 의 `EventName`. 실패해도 프론트는 무시한다(sendBeacon).

## 목 서버와 실서버의 차이 (알고 있는 것)
- 목은 Job 을 경과 시간으로 시뮬레이션(ANALYZE 12초 · DRAFT 8초). 실서버 목표는 30초 · 3분.
- 목의 `pollAfterMs` 는 1200 고정. 실서버는 부하에 따라 조절해도 된다 — 프론트는 값을 그대로 따른다.
- 목은 진행 중인 Job 을 sessionStorage 에 같이 담아 새로고침을 넘긴다. 실서버는 당연히 서버에 있다.
- 목은 직접 작성의 사용자별 재요청 키와 버전별 근거·확정 여부도 같은 스냅샷에 담습니다. 현재 버전의 알려진 근거만 보존하며, 과거 메타데이터가 없으면 빈 근거로 둡니다. 동일한 글이라는 이유로 현재 근거를 과거에 복사하지 않습니다.
- 목의 `E-7` 은 "카드감 낮음" 후보로 카드를 만들면 발생. 실서버는 3분 초과 시.
- 목은 `/dedicated` 없음 — 전부 단일 프로세스 인메모리. 재시작하면 초기화.

## 물려받은 초안(`api-spec.md`)에서 정리한 것

2026-09-07 프론트 스캐폴드와 함께 들어온 `docs/api-spec.md` 는 화면을 만들며 프론트가 제안한 초안이었다.
백엔드가 계약을 확정하면서 대부분이 대체됐고, 계약 문서가 둘이면 다음 사람이 어느 쪽을 믿을지 모르게 되므로
**이 문서 하나로 합치고 초안은 지웠다** (2026-09-15). 지우면서 아래 둘만 옮겨 왔다.

### 경로 표기가 갈렸던 것 — 지금 구현된 쪽이 기준이다

| 초안 | 확정 · 구현됨 |
|---|---|
| `GET /auth/github/login` | `GET /auth/github/start` |
| `GET /auth/session` | `GET /me` |
| `GET /repositories` · `/repositories/{id}/...` | `GET /repos` · `/repos/{id}/...` |
| `POST /cards/manual` · 미사용 `POST /cards` | `POST /cards/manual/draft` (빈 DRAFT 먼저) → `PATCH /cards/{id}/draft` (네 칸 전체 저장) |
| 목록 응답 커서 페이지네이션 `{ items, nextCursor }` | 평범한 배열. 레포·카드 모두 한 사용자 기준이라 아직 페이지네이션이 필요한 규모가 아니다 |

초안의 §7(라우트 ↔ 훅 매핑)은 `.jsx` 파일 기준이라 통째로 무효다 — 그 구조는 TypeScript + React Router 로 바뀌었다.

### 아직 안 정해진 것 (초안 §8 중 살아 있는 항목)

| # | 항목 | 지금 상태 |
|---|---|---|
| 1 | 크레딧 개념이 유효한가 | **프론트에서 뺐다.** `Me` 에 `plan: 'FREE'` 만 있고 크레딧 필드는 없다. 되살릴 거면 알려 달라 |
| 2 | `weak`(카드감 낮음) 판정 근거 | 규칙 기반인지 모델 점수인지 아직 모른다. 화면은 `weak: boolean` 만 쓰므로 어느 쪽이어도 돌아간다 |
| 3 | 내 커밋 / 팀 커밋 / 리뷰 수를 GitHub 에서 어떻게 세는가 | 응답 모양(`contribution:{mine,team,ratio,level}` · `prCount` · `reviewCount`)은 확정. **세는 방법**은 백엔드 판단이다. 봇·머지 커밋을 빼는지에 따라 화면의 "내 기여 3%" 경고가 달라진다 |

초안 §8 의 나머지는 해소됐다 — `needsReview` 는 서버가 `star` 로 판정하고, 버전 히스토리·복원 엔드포인트가 생겼고,
"다른 작업 하러 가기" 이후 상태 유지는 `GET /jobs?active=true` 로 푼다.

## 자소서 (#484, 계약만 · 서버 구현 전)

문항 입력 → AI 카드 고르기 → 카드 확인 → 모자란 재료 질문 → AI 쓰기·검사·검토 → 초안 고치기의 `/cover-letters` API 와 단계(`stage`)·폴링 규칙은 [자소서 계약](2026-10-28-COVER_LETTER.md)을 따릅니다. 기존 Job API(`/jobs`)는 쓰지 않고 자소서 조회를 폴링합니다.

## 카드 분야·기술 태그 (#338 → #339, 선행 구현 대기)

카드 목록·상세는 `areas: string[]`(최대 2), `techs: string[]`(최대 8)를 반환합니다. 허용 enum과 배포 전환 처리는 [태그 계약](2026-10-21-CARD_TAGS.md)을 따릅니다. 태그는 읽기 전용 카드 메타데이터이며 수정 요청에 포함하지 않습니다. FE 태그 PR은 BE #338 이후 머지합니다.

## 경험 카드 — 활동 묶음·문항 유형·다듬기 (#474)

모두 [경험 카드 계약](2026-10-28-EXPERIENCE_CARDS.md)을 따릅니다. 레포 밖 경험을 묶는 활동(`/activities`)과 카드의 `activity` 는 구현됐고(#477) 위 카드 표에 반영했습니다. 카드의 `themes`, 경험 카드 되묻기의 다듬기 미리 보기(`polished-draft`)와 답의 `original` 은 계약만 정했고, 위 카드·되묻기 표는 그 서버 구현이 머지될 때 고칩니다.

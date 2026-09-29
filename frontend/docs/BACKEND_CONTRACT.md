# 프론트 ↔ Spring 계약 (BE 인계용)

**단일 출처는 `src/api/schemas.ts`(zod)** 입니다. 이 문서는 그 파일을 사람이 읽기 좋게 풀어 쓴 것이고, 둘이 다르면 코드가 맞습니다.
프론트는 응답을 zod 로 파싱하므로, 여기 적힌 필드가 빠지거나 타입이 다르면 **첫 호출에서 `ContractError`** 로 드러납니다. 조용히 깨지지 않습니다.

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
| **에러가 아닌 것** | 후보 0개 · 부분 결과 · 작업 실패는 **200** + 본문 상태 (`verdict`/`partial`/`state`). 4xx/5xx 는 진짜 실패에만 |

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

### 레포
| | 요청 | 응답 `data` |
|---|---|---|
| `GET /repos` | | `RepoSummary[]` — `{ id, owner, name, contribution:{mine, team, ratio, level:'NONE'|'PARTIAL'|'SHARED'|'MAJOR'}, prCount, reviewCount, language, activeFrom, activeTo, lastAnalyzedAt, candidateCount, cardCount, recommended }` |
| `GET /repos/{id}` | | `RepoDetail` = `RepoSummary` + `disclosure:{ reads[], skips[], estimatedSeconds }` (B2 사전 고지 문구는 서버가 만든다) |
| `POST /repos/{id}/analyze` | 헤더 `Idempotency-Key: <UUID v4>` | `{ jobId, state, pollAfterMs }` — 같은 키·같은 저장소 재요청 또는 같은 저장소의 활성 Job은 기존 Job을 200/202로 반환. **같은 키를 다른 저장소에 사용하면 409** (PR #50: `INVALID_REQUEST`, 제안된 `IDEMPOTENCY_KEY_MISMATCH`도 FE 수용). |
| `GET /repos/{id}/candidates` | | `CandidateBoard` (아래) |
| `POST /repos/{id}/candidates` | `{ title, summary, shas[] }` | `Candidate` (type `MANUAL`) |
| `POST /repos/{id}/cards` | `{ candidateIds[] }` | `{ jobId, cardIds[] }` — 후보 1개 = 카드 1장, DRAFT Job 1개 |
| `GET /repos/{id}/commits?q=` | | `RepoCommit[]` — `{ sha, message, path, at, url, candidateId }` (C5 커밋 찾기, 최대 20) |
| `GET /repos/{id}/recall` | | `Recall` — `{ repoId, note, dirs:[{ path, filesChanged, myCommits, files[], question, options[] }] }` |
| `POST /repos/{id}/recall/answers` | `{ path, text }` | `Candidate` (type `MANUAL`, 근거 USER_STATED) |

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
  "steps": [{ "key": "COMMITS|PR_REVIEW|COMPRESS|REASON", "state": "QUEUED|RUNNING|DONE|SKIPPED",
              "done": 0, "total": 0 }],               // 늘 이 4개가 이 순서. 총량을 모르면 total: null
  "errorCode": "GITHUB_UNAVAILABLE|GITHUB_RATE_LIMITED|DRAFT_TIMEOUT|EVIDENCE_MISSING|INTERNAL_ERROR" | null,
  "retryable": bool | null,
  "retryAfterSec": n | null,                          // GITHUB_RATE_LIMITED 에서만 의미가 있다
  "startedAt", "updatedAt", "finishedAt": null,
  "result": { "repoId"?, "cardIds"?, "verdict": "OK|EMPTY|PARTIAL", "reasons": [] } | null,
  "pollAfterMs": 2000 }                               // 다음 폴링까지 기다릴 시간. 간격은 서버가 정한다
```

- **분석 실패는 HTTP 오류가 아니다.** 폴링 응답은 늘 200 이고, 실패는 `state` 로 말한다.
- **후보 0개도 실패가 아니다.** `SUCCEEDED` + `result.verdict: "EMPTY"` + `reasons` 3줄.
- **남의 Job 은 403 이 아니라 404** — 존재 여부조차 알려 주지 않는다.
- 자동 재시도는 없다. 사용자가 버튼을 눌렀을 때만 다시 시작한다.
  `GITHUB_RATE_LIMITED` 일 때만 `retryAfterSec` 을 쓰고, 그 시간이 지나기 전까지 프론트가 버튼을 막는다.

`GET /jobs?active=true` → `{ "jobs": [{ jobId, state, userRepositoryId, repoName, startedAt, pollAfterMs }] }`

진행 중(`QUEUED`·`RUNNING`)인 현재 사용자의 Job. 없으면 빈 배열 + 200.
**프론트는 Job ID 를 브라우저에 저장하지 않는다.** 새로고침 복구는 URL 쿼리(`?job=`) 아니면 이 목록이다 —
그래야 다른 기기·다른 탭에서 시작한 작업도 잡히고, 캐시를 지웠다고 진행 중인 작업을 잃지 않는다.

`POST /jobs/{id}/cancel` → `Job`. 이미 끝난 Job 을 취소해도 오류가 아니라 현재 상태를 200 으로.

### 카드
| | 요청 | 응답 |
|---|---|---|
| `GET /cards?status=DRAFT` | | `CardSummary[]` — `{ id, kind, status, title, versionNo, star:{S,T,A,R}, updatedAt }` + 부가 `{ sourceLabel, sourceType, period, evidenceCount, userStatedCount }` |
| `POST /cards` | `{ title, period, repoId|null, fields:{S?,T?,A?,R?} }` | `Card` (QUALITATIVE · 근거 USER_STATED) |
| `POST /cards/manual/draft` | `{ title, period, repoId|null }` | `Card` — 직접 작성 첫 진입. 제목만으로 빈 DRAFT 를 만들어 `cardId` 를 준다 |
| `PATCH /cards/{id}/draft` | `{ situation, task, action, result }` (null 허용) | `{ cardId, versionNo, situation, task, action, result, savedAt }` — 임시 저장 |
| `GET /cards/{id}` | | `Card` (아래) |
| `POST /cards/{id}/versions` | `{ situation?, task?, action?, result? }` (null 허용) | `Card` — USER_EDIT 새 버전. **윤문·병합 금지** |
| `POST /cards/{id}/regenerate` | `{ field }` | `{ jobId }` — E-7 칸 단위 재생성 |
| `POST /cards/{id}/mask` | `{ rules:[{from,to}] }` | `Card` — 원문 불변, 표시·내보내기용 |
| `POST /cards/{id}/confirm` | `{ edited, maskedFields[] }` | `ConfirmResult` `{ cardId, status:'CONFIRMED', versionNo, usedCandidateIds[], remainingCandidates, repoId }` — **유일하게 되돌릴 수 없음**. S·A 없으면 422 `NOT_CONFIRMABLE` |
| `POST /cards/{id}/reopen` | | `Card` (DRAFT 로) |
| `GET /cards/{id}/versions` | | `[{ versionNo, source, createdAt, summary, current }]` 최신순 |
| `POST /cards/{id}/versions/{no}/restore` | | `Card` — RESTORE 새 버전 (append-only) |

**카드 목록의 `star`**

`FILLED` · `EMPTY` · `NEEDS_REVIEW` 세 값. **현재 카드 버전의 실제 칸 상태를 서버가 판정해서 내려준다.**
프론트에 있던 "근거 수로 되짚어 추정하는" 로직은 지웠다 — 목록과 상세가 어긋나는 원인이었다.

**임시 저장 (`PATCH /cards/{id}/draft`)**

- 브라우저(localStorage)에 카드 입력을 저장하지 않는다. 직접 작성 카드도, 편집 중인 DRAFT 도 서버에 둔다.
- 프론트는 입력이 멈춘 뒤 **2.5초 디바운스**로 한 번만 보낸다.
- **DRAFT 상태에서는 같은 버전을 덮어쓴다.** 버전이 느는 건 확정 시점뿐이다.
- 단 하나의 예외: 현재 버전이 `AI_DRAFT` 면 AI 초안이 잠겨 있어야 하므로,
  **첫 PATCH 에서만 `USER_EDIT` 버전이 한 번 갈라지고** 그 뒤로는 그 버전을 계속 덮는다.
  (AI 초안 v1 은 히스토리에 그대로 남는다 — 제품 원칙이라 양보하지 않는다.)
- 확정된 카드에 PATCH 하면 409 `CARD_CONFIRMED`.

**`evidence.authoredBy`**

`AI` | `USER`. **AI 문장은 근거가 1개 이상 필요하고, USER 문장은 커밋·되묻기 근거가 없어도 허용된다.**
`authoredBy`는 프론트 계약에 남아 있는 필드이며, DB 컬럼 추가는 확정된 요구가 아닙니다. 백엔드 V2/PR #8은 `evidence_type`과 `USER_SELECTED`의 턴 제약으로 직접 작성 출처를 구분했습니다. 실제 응답 매핑을 백엔드와 확인해야 하며, 이 프론트 작업에서 DB 변경을 요구하지 않습니다.
API 로 나가는 `evidence_type` 은 대문자 `COMMIT` · `USER_STATED` · `USER_SELECTED` 만 쓴다 —
`user_written`, `inferred` 같은 소문자 내부값은 외부로 내보내지 않는다.

**Card**
```jsonc
{ "id", "kind": "TECH|QUALITATIVE", "status": "DRAFT|CONFIRMED", "title",
  "repo": { "id", "owner", "name" } | null, "candidate": { "id", "type", "ref", "title" } | null,
  "version": { "versionNo", "source": "AI_DRAFT|USER_EDIT|INTERVIEW|MASK|RESTORE", "createdAt", "situation", "task", "action", "result" },  // 칸은 null 가능 = 비운 칸
  "evidence": [{ "field": "S|T|A|R", "type": "COMMIT|USER_STATED|USER_SELECTED", "sha"(7), "url", "snippet", "turnNo" }],
  "lowConfidenceFields": [{ "field", "why" }], "droppedFields": [{ "field", "reason": "NO_EVIDENCE|OVERCLAIM|UNSOURCED_NUMBER|TIMEOUT" }],
  "maskRules": [{ "from", "to" }], "generation": { "jobId", "partial" } | null, "interviewTurns", "confirmedAt", "createdAt" }
```
`task: null` 은 버그가 아니다 — `droppedFields` 가 이유를 말한다. `generation != null` 이면 프론트는 D1(생성 중)을 그리고 Job 을 폴링한다.

### 되묻기
| | 요청 | 응답 |
|---|---|---|
| `GET /cards/{id}/interview` | | `InterviewTurn[]` |
| `POST /cards/{id}/interview` | `{ field }` | `InterviewTurn` — 상한 초과 409 `INTERVIEW_CAP`, 확정 카드 409 `CARD_CONFIRMED` |
| `POST /cards/{id}/interview/{turnNo}/answer` | `{ text, source:'USER_STATED'|'USER_SELECTED' }` | `Card` — 답변은 **가공 없이** 새 버전(INTERVIEW)에 반영 |

`InterviewTurn`
`{ turnNo, field, askedBy:'USER_REQUEST'|'FOLLOW_UP', sourceType:'PR'|'COMMIT_CLUSTER'|'DIRECT_CARD',
   questionType:'EVIDENCE_GAP'|'FOLLOWUP'|'RECALL_AID', found[], missing[], question, options[],
   answer:{text, source}|null, remaining, maxTurns }`

- **PR 번호가 없는 커밋 묶음과 직접 작성 카드도 되묻기 대상이다.** `sourceType` 이 어느 쪽인지 말한다.
- S→T→A→R 순서대로 기계적으로 묻지 않는다. **근거가 있는데 비었거나 확인이 필요한 칸을 먼저** 묻는다.
- **질문 상한은 서버 정책이다.** 응답의 `maxTurns` 로 내려주고, 프론트는 그 값만 쓴다
  (화면에 남아 있던 4회 상수는 응답 전 자리 지킴용 기본값으로만 남겼다).
- 답변 출처는 세 가지로 구분한다: AI 가 준 보기 / 사용자가 그 보기를 고름(`USER_SELECTED`) / 사용자가 직접 씀(`USER_STATED`).
  **이 내부 이름은 화면에 노출하지 않는다** — "보기에서 고른 것", "내가 말한 것"으로 번역해 보여 준다.

### 아직 서버 쪽에 남은 일 (프론트는 계약대로 이미 붙어 있음)
- 단계별 마지막 완료 지점을 Job 에 저장해, 재시도 때 처음부터 다시 돌리지 않고 이어서 처리하기.

### 지표
`POST /events` `{ name, props, at, path }` → `{ ok }`. 이벤트 이름은 `src/lib/track.ts` 의 `EventName`. 실패해도 프론트는 무시한다(sendBeacon).

## 목 서버와 실서버의 차이 (알고 있는 것)
- 목은 Job 을 경과 시간으로 시뮬레이션(ANALYZE 12초 · DRAFT 8초). 실서버 목표는 30초 · 3분.
- 목의 `pollAfterMs` 는 1200 고정. 실서버는 부하에 따라 조절해도 된다 — 프론트는 값을 그대로 따른다.
- 목은 진행 중인 Job 을 sessionStorage 에 같이 담아 새로고침을 넘긴다. 실서버는 당연히 서버에 있다.
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
| `POST /cards/manual` | `POST /cards` (한 번에 저장) · `POST /cards/manual/draft` (빈 DRAFT 먼저) |
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

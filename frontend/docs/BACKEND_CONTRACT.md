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
| `POST /repos/{id}/analyses` | | `{ jobId }` — ANALYZE Job 생성 (증분: ETag 기반) |
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
{ "id", "type": "ANALYZE|DRAFT", "state": "PENDING|RUNNING|SUCCEEDED|FAILED|PARTIAL",
  "progress": 0..1, "etaSeconds": n|null, "startedAt",
  "stages": [{ "key", "label", "detail", "status": "DONE|NOW|WAIT" }],
  "error": { "code": "E1_COLLECT_FAILED|E2_RATE_LIMIT|E7_TIMEOUT|UNKNOWN", "message", "retryAfterSeconds", "progressNote" } | null,
  "result": { "repoId"? , "cardIds"? } | null }
```
프론트는 터미널 상태(SUCCEEDED/FAILED/PARTIAL)까지 1.5→3초 간격으로 폴링하고, 화면을 떠나도 localStorage 에 기억해 끝나면 알린다.
`POST /jobs/{id}/cancel` → `{ ok }`

### 카드
| | 요청 | 응답 |
|---|---|---|
| `GET /cards` | | `CardSummary[]` — `{ id, kind, status, title, sourceLabel, sourceType, period, evidenceCount, userStatedCount, hasLowConfidence, hasDropped, updatedAt }` |
| `POST /cards` | `{ title, period, repoId|null, fields:{S?,T?,A?,R?} }` | `Card` (QUALITATIVE · 근거 USER_STATED) |
| `GET /cards/{id}` | | `Card` (아래) |
| `POST /cards/{id}/versions` | `{ situation?, task?, action?, result? }` (null 허용) | `Card` — USER_EDIT 새 버전. **윤문·병합 금지** |
| `POST /cards/{id}/regenerate` | `{ field }` | `{ jobId }` — E-7 칸 단위 재생성 |
| `POST /cards/{id}/mask` | `{ rules:[{from,to}] }` | `Card` — 원문 불변, 표시·내보내기용 |
| `POST /cards/{id}/confirm` | `{ edited, maskedFields[] }` | `ConfirmResult` `{ cardId, status:'CONFIRMED', versionNo, usedCandidateIds[], remainingCandidates, repoId }` — **유일하게 되돌릴 수 없음**. S·A 없으면 422 `NOT_CONFIRMABLE` |
| `POST /cards/{id}/reopen` | | `Card` (DRAFT 로) |
| `GET /cards/{id}/versions` | | `[{ versionNo, source, createdAt, summary, current }]` 최신순 |
| `POST /cards/{id}/versions/{no}/restore` | | `Card` — RESTORE 새 버전 (append-only) |

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
| `POST /cards/{id}/interview` | `{ field }` | `InterviewTurn` — 상한(4턴) 초과 409 `INTERVIEW_CAP`, 확정 카드 409 `CARD_CONFIRMED` |
| `POST /cards/{id}/interview/{turnNo}/answer` | `{ text, source:'USER_STATED'|'USER_SELECTED' }` | `Card` — 답변은 **가공 없이** 새 버전(INTERVIEW)에 반영 |

`InterviewTurn` `{ turnNo, field, askedBy:'USER_REQUEST'|'FOLLOW_UP', found[], missing[], question, options[], answer:{text, source}|null, remaining }`

### 지표
`POST /events` `{ name, props, at, path }` → `{ ok }`. 이벤트 이름은 `src/lib/track.ts` 의 `EventName`. 실패해도 프론트는 무시한다(sendBeacon).

## 목 서버와 실서버의 차이 (알고 있는 것)
- 목은 Job 을 경과 시간으로 시뮬레이션(ANALYZE 12초 · DRAFT 8초). 실서버 목표는 30초 · 3분.
- 목의 `E-7` 은 "카드감 낮음" 후보로 카드를 만들면 발생. 실서버는 3분 초과 시.
- 목은 `/dedicated` 없음 — 전부 단일 프로세스 인메모리. 재시작하면 초기화.

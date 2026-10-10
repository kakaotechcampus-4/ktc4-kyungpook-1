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
| `POST /repos/{id}/analyze` | 헤더 `Idempotency-Key: <UUID v4>` | `{ jobId, state, pollAfterMs }` — 같은 키·같은 저장소 재요청 또는 같은 사용자의 같은 연결 저장소에 활성 Job이 있으면 기존 Job을 200/202로 반환. **같은 키를 다른 저장소에 사용하면 409 + IDEMPOTENCY_KEY_MISMATCH** (확정). FE는 409에 새 키를 만들어 자동 재요청하지 않는다. |
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
- FAILED와 SUCCEEDED + partial 모두 **retryable === true**일 때만 재시도한다. false/null은 허용으로 추정하지 않는다. 오류 코드 이름만으로 재시도 여부를 추정하지 않는다.
- 2026-09-26 develop의 JobView는 GITHUB_UNAVAILABLE/GITHUB_RATE_LIMITED만 true이며 retryAfterSec/result는 아직 null이다. 향후 실제 대기 시간이 내려오면 finishedAt(없으면 updatedAt)부터 계산한다.
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
목록의 sourceLabel/sourceType/period/evidenceCount/userStatedCount는 부가 정보다. 생략/null은 모름으로 보존하고 숨긴다. `MANUAL`이나 0을 기본값으로 넣지 않는다. 서버가 명시한 0만 실제 0건이다. 빈칸·확인 필요 필터는 `star`만 사용한다. 이 누락/0 기준은 [백엔드 확인 요청](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/pull/61#issuecomment-5969111285)에 함께 정리했다.

**제목·기간 수정 제안 — 백엔드 확인 대기 (2026-10-03)**

기존 `PATCH /cards/{id}/draft`의 STAR 저장 규약을 확장하거나 바꾸지 않는다. 제안 경로는 `PATCH /cards/{id}/metadata`이며 `{ title?: string, period?: string }`에서 전달한 필드만 수정한다. period는 자유 문자열이고 `""`는 삭제다. 응답 제안은 `{ cardId, title, period: string | null, updatedAt }`이며 상세 `GET /cards/{id}`에도 실제 period 반환을 요청한다. 제목은 공백만 있는 값을 거절하고 소유권·DRAFT를 검사한다. STAR·근거·현재 버전·AI 원본은 변경하지 않는다.

Mock 구현과 실제 API adapter/화면은 준비돼 있다. 실제 API 모드에서는 `VITE_CARD_METADATA_ENABLED=true`를 명시하기 전 호출하지 않으며, 계약과 서버 구현 확인 후 활성화한다. 이 경로는 아직 확정된 서버 API가 아니다. 기존 기간이 누락돼도 제목만 수정하면 기간 삭제를 전송하지 않는다. 자유 입력 기간을 날짜로 억지 변환하지 않는다. [요청 댓글](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/pull/61#issuecomment-5969111285)과 [작업 문서](2026-10-03-CARD_WORKFLOW.md)를 참고한다.

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

### 기업·직무 매칭 · 자소서 초안 — **제안(미확정)**

> **서버·AI 에 구현이 없다.** PRD 7단계(기업 매칭)는 팀이 V1 범위에서 뺀 항목이고(`backend/ARCHITECTURE.md` "아직 정하지 않은 것"),
> 프론트는 **목 서버로만** 먼저 만들어 화면과 계약을 제안한다. 실서버 모드(`VITE_API_MOCK=false`)에서는 메뉴·화면이 "서버 연동 대기"로
> 막혀 있고, 계약이 확정돼 `VITE_MATCH_ENABLED=true` 가 되어야 열린다. 응답 모양은 `api/schemas.ts` 의 `MatchTarget` · `MatchDetail` · `CoverLetter*` 가 기준이다.

| 엔드포인트 | 요청 | 응답 |
|---|---|---|
| `GET /matches` | | `MatchTarget[]` — 근거 등급 순. 기업마다 공개 출처 `source:{ url, verifiedAt(기업 정보 확인일), expiresAt(만료일) }` 가 필수이고, **만료일이 지난 기업은 서버가 뺀다**. 인재상은 `matchedTags[]`(근거 있음) · `gapTags[]`(근거 없음)로 나눠 내려 목록에서도 빈 칸을 보여 준다. `supportedTagCount`(근거가 보이는 인재상 수)와 `independentTagCount`(서로 다른 카드 기준, 등급은 이 값으로 매긴다)는 다를 수 있다 |
| `GET /matches/{id}` | | `MatchDetail` = `MatchTarget` + `tags:[{ tag, supports:[{ cardId, cardTitle, cardKind, field, sentence }] }]`. `field` 는 `A`·`R` 만 온다. 같은 카드의 행동·결과가 둘 다 근거면 `supports` 에 둘 다 담는다. `supports` 가 비면 근거 없는 인재상(빈 칸) |
| `GET /cover-letters` | | `CoverLetterSummary[]` 최근 수정 순 (본문 없음) |
| `POST /cover-letters` | `{ matchId \| null, question, cardIds[], charLimit \| null }` | `CoverLetter`. `question` = `MOTIVATION` · `COLLABORATION` · `PROBLEM_SOLVING` · `GROWTH`. `charLimit` = 공백 포함 글자 수 제한(100~5000 정수), 제한 없으면 `null` |
| `GET /cover-letters/{id}` | | `CoverLetter` = 요약 + `paragraphs[]` · `text` · `cardIds[]` · `gaps[]`(확정 카드 어디에도 근거가 없는 인재상) · `notUsed[]`(근거 카드는 있는데 이번에 안 고른 인재상) · `stale`(아래 7) |
| `PATCH /cover-letters/{id}` | `{ text }` (≤10,000자) | `{ id, text, edited, updatedAt }` — 임시 저장. 처음 생성된 문장과 달라지면 `edited:true` |

오류: `400 BAD_QUESTION`/`BAD_TEXT`/`BAD_LIMIT`(글자 수 제한이 정수가 아니거나 범위 밖) · `404 MATCH_NOT_FOUND`(만료 포함)/`COVER_LETTER_NOT_FOUND` · `422 NO_EVIDENCE`(카드 미선택)/`CARD_NOT_CONFIRMED`(확정하지 않은 카드).

**제품 원칙 — 서버가 지켜야 하는 것** (프론트 화면과 목은 이미 이 전제로 만들었다)

1. **확정한(`CONFIRMED`) 카드만 근거이고, 카드 안에서는 행동(A)·결과(R) 칸 문장만 근거다.** 초안 카드는 키워드가 맞아도 세지 않는다.
   상황(S)·과제(T)는 "무슨 일이 있었는가"일 뿐 그 역량을 보여 준 증거가 아니라서(예: "의견이 갈렸다"는 협업의 증거가 아니다) 근거로 쓰지 않는다.
2. **등급(`fit` A~D)은 규칙이 매긴다.** 합격 가능성·점수·퍼센트는 응답에도 화면에도 없다 ("92% 기여" 류 수치화된 자기 주장을 만들지 않는 기존 결정과 같다).
   **카드 한 장은 인재상 하나의 근거로만 센다**(카드↔인재상 최대 매칭). 같은 카드·문장이 여러 인재상에 걸려 보여도 등급에는 한 번만 들어가, 카드 한두 장으로 "충분"이 나오지 않는다.
   서로 다른 카드로 뒷받침되는 인재상 수(`independentTagCount`)가 전체의 ≥75% 이고 **3개 이상**이면 A, ≥50% 면 B, 1개 이상이면 C, 0개면 D. 화면에는 둘 다 보여 주고(근거가 보이는 인재상 / 서로 다른 카드 기준) 같은 카드가 여러 인재상에 쓰였다는 사실도 알려 준다.
   AI `/matching`(`score`/`matched_keywords`/`rationale`)은 인재상 키워드를 뽑는 데 쓰고, 등급은 그 위에서 규칙으로 계산하길 제안한다.
3. **기업 정보는 `company_context` 모양을 따른다** — 공개 출처 URL · 직무 · 인재상 태그 · 확인일 필수, 만료되면 추천에서 제외. 출처 URL 은 `http(s)` 만 링크로 렌더링한다.
4. **근거가 없는 인재상은 지어내지 않는다.** 상세는 빈 칸으로 보여주고, 자소서 초안은 `gaps[]` 로 "넣지 않은 인재상"을 돌려준다.
5. **초안 = 확정한 카드 문장(마스킹 적용) + 연결 문장.** 연결 문장은 문항·지원 대상에서 이미 아는 사실만 잇고 새 경험·수치를 만들지 않는다. `paragraphs[].kind` 가 `EVIDENCE`/`CONNECTIVE` 로 둘을 구분해, 화면이 "내 카드 문장"과 "AI가 이은 문장"을 다르게 표시한다. 카드의 `maskRules` 는 서버가 적용한다(밖으로 나가는 글이다).
6. 사용자가 고치면 `paragraphs` 는 처음 생성 시점의 근거 구성으로 남고, 화면은 `edited:true` 일 때 문장별 출처 표시를 숨긴다.
7. **초안은 만든 시점의 스냅샷이다.** 쓴 카드가 그 뒤에 바뀌면(확정 해제 · 내용 수정 · **마스킹 변경**) 서버가 `stale:true` 로 돌려주고, 화면은 "다시 만들어 주세요"라고 알린다. 마스킹을 새로 걸었는데 옛 초안이 그대로 복사되는 일을 막으려는 것이다. 서버는 생성 시점의 마스킹 규칙을 보관해 비교한다.
8. **지원 동기는 지어내지 않고 직접 쓸 칸으로 남긴다.** `question == MOTIVATION` 이면 연결 문장 뒤에 `kind: SLOT` 문단(`cardId`·`cardTitle` 은 null, `text` 는 표식 `[지원 동기를 직접 적어 주세요]`)을 하나 넣는다.
   사용자가 표식을 지우고 직접 쓰면 칸이 채워진 것이고, 화면은 본문에 표식이 남아 있는 동안 "칸이 비어 있어요"라고 알린다. 다른 문항에는 SLOT 이 없다.
9. **글자 수 제한(`charLimit`)은 저장하고 돌려줄 뿐 본문을 자르지 않는다.** 글자 수(공백·줄바꿈 포함)는 화면이 현재 본문으로 세어 `현재 / 제한` 으로 보여 주고, 넘으면 몇 자 넘었는지 알린다. 카드 문장을 그대로 쓰기 때문에 제한보다 길게 나올 수 있다.
10. `paragraphs[].kind == EVIDENCE` 이면 `cardId`·`cardTitle` 이 반드시 있다(없으면 "확정한 카드 문장"이라는 표시가 거짓이 된다). 밖으로 나가는 `text` 의 연결 문장에는 앱 용어·의견을 넣지 않는다.

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

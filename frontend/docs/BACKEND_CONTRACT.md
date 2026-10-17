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
| `GET /cards` | 상태 쿼리 없음 | **구현됨.** 본인 카드 전체를 최근 수정순으로 반환하는 `CardSummary[]` — `{ id, kind, status, title, versionNo, star:{S,T,A,R}, updatedAt }` + 부가 `{ sourceLabel, sourceType, period, evidenceCount, userStatedCount }`. 상태 필터는 FE에서 적용 |
| `POST /cards/manual/draft` | `{ title, period, repoId|null }`, 헤더 `Idempotency-Key: <UUID v4>` | **구현됨.** `Card` — 빈 `USER_EDIT` v1 DRAFT. 같은 사용자·같은 키는 처음 만든 카드의 **현재** 상세를 반환 |
| `PATCH /cards/{id}/draft` | `{ situation, task, action, result }` **네 키 필수**, string/null | **구현됨.** `{ cardId, versionNo, situation, task, action, result, savedAt }` — 임시 저장 |
| `GET /cards/{id}` | | **구현됨.** `Card` (아래) |
| `POST /cards/{id}/mask` | `{ rules:[{from,to}] }` | **구현됨.** `Card` — 원문 불변, 표시·내보내기용 |
| `POST /cards/{id}/reopen` | | **구현됨.** `Card` (DRAFT로). 이미 DRAFT면 no-op |
| `GET /cards/{id}/versions` | | **구현됨.** `[{ versionNo, source, createdAt, summary, current }]` 최신순 |
| `POST /cards/{id}/versions/{no}/restore` | | **구현됨.** `Card` — 과거 버전을 복사한 RESTORE 새 버전. 현재 버전이면 no-op |
| `POST /cards/{id}/regenerate` | `{ field }` | **Mock 체험 전용.** 실서버 controller 없음. FE는 실제 API 모드에서 화면과 endpoint 모두 차단 |
| `POST /cards/{id}/confirm` | `{ edited, maskedFields[] }` | **FE 기대 계약·Mock. 실서버 controller 없음.** Mock `ConfirmResult` `{ cardId, status:'CONFIRMED', versionNo, usedCandidateIds[], remainingCandidates, repoId }`. S·A 없으면 422 `NOT_CONFIRMABLE`; 현재 버전을 확정 표시하고 같은 내용의 버전을 덧붙이지 않음 |

`POST /cards`와 `POST /cards/{id}/versions`는 실서버에 없는 미사용 경로여서 FE endpoint/hook과 Mock에서 제거했습니다. 직접 작성은 manual/draft → PATCH draft를 사용합니다. 이 계약 정리는 미구현 confirm·되묻기 생성/답변 등의 서버 경로를 추가하지 않습니다.

**직접 작성 시작 (`POST /cards/manual/draft`)**

- 제목은 공백만 있는 값을 거절하고 최대 256자, 기간은 최대 50자의 자유 글입니다. 빈 기간/null/공백만 있는 기간은 null입니다. 제목·기간의 U+0000은 400 `INVALID_REQUEST`입니다. 의미 있는 글의 앞뒤 공백은 보존합니다.
- `repoId`가 있으면 본인 연결 저장소를 먼저 확인합니다. 없거나 다른 사용자의 저장소는 404이고, 같은 키로 이미 만든 카드가 있어도 이 검사를 건너뛰지 않습니다.
- 키는 사용자 ID와 함께 식별합니다. 같은 키로 다른 제목·기간·저장소를 보내도 처음 카드의 현재 제목·기간·저장소·버전·상태를 반환합니다. FE는 화면 진입마다 UUID v4 하나를 만들고 실패 재요청에 재사용하며, 반환된 메타데이터를 미구현 PATCH metadata로 덮어쓰지 않습니다.
- 서버는 키가 없거나 공백이면 매번 새 카드를 만들 수 있습니다. 의미 있는 키는 최대 255자입니다. 분석 Job의 키와 직접 작성 키는 별도 범위입니다. Mock은 이 매핑을 Job과 함께 snapshot/hydrate에서 보존하고 reset에서 초기화합니다.

**가리기·이력 (`#175`)**

- `rules` 목록은 필수이며 최대 20개, from/to는 각 최대 100자입니다. from은 공백만 있으면 안 되고 to의 빈 문자열은 삭제 규칙으로 허용합니다. null 규칙·누락된 from/to·U+0000은 400 `INVALID_REQUEST`입니다. 원문을 수정하지 않습니다.
- 확정 카드의 mask는 입력 검사 전에 409 `CARD_CONFIRMED`입니다. 규칙 값과 순서가 기존 목록과 같으면 버전과 수정 시각을 바꾸지 않습니다. 달라지면 원문·근거를 복사한 MASK 버전을 추가합니다.
- 과거 버전 restore는 확정 카드도 다시 열어 RESTORE 버전을 추가합니다. 현재 버전 restore는 확정 상태·시각·버전을 그대로 두고, 없는 번호는 404입니다. 마스킹 규칙은 카드에 속하므로 과거 원문 복원으로 이전 규칙까지 되돌리지 않습니다.
- summary는 빈 버전이면 `채운 칸 없음`, 아니면 `S · T · A · R 채움` 중 실제 채운 칸입니다. 첫 AI_DRAFT에만 ` · 원본(잠김)`, 확정된 버전에는 ` · 확정`을 붙입니다.

**카드 목록의 `star`**

`FILLED` · `EMPTY` · `NEEDS_REVIEW` 세 값. **현재 카드 버전의 실제 칸 상태를 서버가 판정해서 내려준다.**
프론트에 있던 "근거 수로 되짚어 추정하는" 로직은 지웠다 — 목록과 상세가 어긋나는 원인이었다.
목록의 sourceLabel/sourceType/period/evidenceCount/userStatedCount는 부가 정보다. 생략/null은 모름으로 보존하고 숨긴다. `MANUAL`이나 0을 기본값으로 넣지 않는다. 서버가 명시한 0만 실제 0건이다. 빈칸·확인 필요 필터는 `star`만 사용한다. 이 누락/0 기준은 [백엔드 확인 요청](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/pull/61#issuecomment-5969111285)에 함께 정리했다.

**제목·기간 수정 — #186 확정, #219 활성화 검증 (2026-10-15)**

기존 `PATCH /cards/{id}/draft`의 STAR 저장 규약을 확장하거나 바꾸지 않는다. 확정 경로는 `PATCH /cards/{id}/metadata`이며 `{ title?: string, period?: string | null }`에서 전달한 필드만 수정한다. period는 자유 문자열이고 빈 입력은 FE에서 `null`로 정규화하여 삭제한다. 기간 생략은 기존 값 유지이고 검증 실패는 400 `INVALID_REQUEST`다. 응답은 `{ cardId, title, period: string | null, updatedAt }`이며 상세 `GET /cards/{id}`에도 period가 반환된다. 제목은 공백만 있는 값을 거절하고 소유권·DRAFT를 검사한다. STAR·근거·현재 버전·AI 원본은 변경하지 않는다.

Mock 구현과 실제 API adapter/화면은 준비돼 있다. 실제 API 모드에서는 `VITE_CARD_METADATA_ENABLED=true`를 명시하기 전 호출하지 않으며, 계약과 서버 구현 확인 후 활성화한다. 기존 기간이 누락돼도 제목만 수정하면 기간 삭제를 전송하지 않는다. 자유 입력 기간을 날짜로 억지 변환하지 않는다. [요청 댓글](https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/pull/61#issuecomment-5969111285)과 [작업 문서](2026-10-03-CARD_WORKFLOW.md)를 참고한다.

**임시 저장 (`PATCH /cards/{id}/draft`)**

- 브라우저(localStorage)에 카드 입력을 저장하지 않는다. 직접 작성 카드도, 편집 중인 DRAFT 도 서버에 둔다.
- 프론트는 입력이 멈춘 뒤 **2.5초 디바운스**로 한 번만 보낸다.
- 네 키를 모두 보내고 비운 칸도 null로 명시합니다. 누락된 키, 문자열/null 이외의 값, 칸당 1000자 초과, U+0000은 400 `INVALID_REQUEST`입니다. blank-only는 null로 정규화하고 의미 있는 글의 공백·줄바꿈은 그대로 저장합니다. 공백 판정은 JS 공백과 Java CardText의 U+001C–001F를 포함합니다.
- **정규화한 네 칸이 현재 글과 같으면 no-op**입니다. 버전 번호·버전 시각·카드 수정 시각을 바꾸지 않으며 savedAt은 기존 버전 시각입니다.
- **확정되지 않은 USER_EDIT만 같은 버전을 덮어씁니다.** AI_DRAFT·INTERVIEW·MASK·RESTORE 또는 확정 후 다시 연 버전 위의 첫 실제 변경은 USER_EDIT 새 버전입니다. 그 뒤의 변경은 같은 미확정 USER_EDIT에 저장합니다.
- 각 칸의 글이 분기 직전 버전과 정확히 같으면 그 칸의 근거·확신도·되묻기 턴 연결을 복사합니다. 취소로 같은 원문을 다시 보내도 이 정보가 돌아옵니다. 달라진 칸은 USER_STATED이며 이전 커밋 근거·확인 필요 표시는 붙이지 않습니다. null은 문장과 그 칸의 근거를 제거합니다.
- 확정된 카드에 PATCH 하면 입력 검사 전에 409 `CARD_CONFIRMED`입니다. 다시 열어도 확정된 과거 버전은 고정된 채 남습니다. 새 버전은 이 확정 표시를 상속하지 않습니다.
- 목록 updatedAt은 마지막 실제 수정 시각이고 최근 수정순입니다. savedAt ACK의 글·버전·시각은 FE 캐시에 먼저 반영하고, ACK에 없는 근거를 추정하지 않고 상세를 다시 조회합니다.

**`evidence.authoredBy`**

`AI` | `USER`. **AI 문장은 근거가 1개 이상 필요하고, USER 문장은 커밋·되묻기 근거가 없어도 허용된다.**
실제 `EvidenceResponse`는 COMMIT이면 AI, USER_STATED/USER_SELECTED이면 USER로 authoredBy를 매핑합니다. 별도 DB 컬럼을 요구하지 않습니다. 현재 카드 응답의 COMMIT 근거는 수만큼 반환하지만 sha/url/snippet/turnNo는 null일 수 있으므로 FE는 근거 링크나 턴 번호를 만들어 내지 않습니다. Mock 시드의 알려진 링크·턴 정보는 데모 데이터입니다.
API 로 나가는 `evidence_type` 은 대문자 `COMMIT` · `USER_STATED` · `USER_SELECTED` 만 쓴다 —
`user_written`, `inferred` 같은 소문자 내부값은 외부로 내보내지 않는다.

**Card**
```jsonc
{ "id", "kind": "TECH|QUALITATIVE", "status": "DRAFT|CONFIRMED", "title", "period": "자유 글" | null,
  "repo": { "id", "owner", "name" } | null, "candidate": { "id", "type", "ref", "title" } | null,
  "version": { "versionNo", "source": "AI_DRAFT|USER_EDIT|INTERVIEW|MASK|RESTORE", "createdAt", "situation", "task", "action", "result" },  // 칸은 null 가능 = 비운 칸
  "evidence": [{ "field": "S|T|A|R", "type": "COMMIT|USER_STATED|USER_SELECTED", "authoredBy": "AI|USER", "sha": "7자리" | null, "url": "링크" | null, "snippet": "글" | null, "turnNo": n | null }],
  "lowConfidenceFields": [{ "field", "why" }], "droppedFields": [{ "field", "reason": "NO_EVIDENCE|OVERCLAIM|UNSOURCED_NUMBER|TIMEOUT" }],
  "maskRules": [{ "from", "to" }], "generation": { "jobId", "partial" } | null, "interviewTurns", "confirmedAt", "createdAt" }
```
`task: null`은 비운 칸입니다. 현재 실서버 CardResponse는 candidate/generation을 null, droppedFields를 빈 배열, interviewTurns를 0으로 반환합니다. Mock은 데모의 실제 후보·생성 Job·되묻기 횟수·빈 칸 이유를 추가로 보존합니다. `generation != null`이면 프론트는 D1(생성 중)을 그리고 Job을 폴링합니다.

### 되묻기
| | 요청 | 응답 |
|---|---|---|
| `GET /cards/{id}/interview` | | **구현됨(#172).** `InterviewTurn[]`, 질문 번호순. 없으면 빈 배열; 없는 카드·남의 카드·잘못된 UUID는 404 |
| `POST /cards/{id}/interview` | `{ field }` | **FE 기대 계약·Mock. 실서버 controller 없음.** `InterviewTurn` — 상한 초과 409 `INTERVIEW_CAP`, 확정 카드 409 `CARD_CONFIRMED` |
| `POST /cards/{id}/interview/{turnNo}/answer` | `{ text, source:'USER_STATED'|'USER_SELECTED' }` | **FE 기대 계약·Mock. 실서버 controller 없음.** `Card` — 답변은 **가공 없이** 새 버전(INTERVIEW)에 반영 |

`InterviewTurn`
`{ turnNo, field, askedBy:'USER_REQUEST'|'FOLLOW_UP', sourceType:'PR'|'COMMIT_CLUSTER'|'DIRECT_CARD',
   questionType:'EVIDENCE_GAP'|'FOLLOWUP'|'RECALL_AID', found[], missing[], question, options[],
   answer:{text, source}|null, remaining, maxTurns }`

현재 실서버 GET 응답의 found/missing/options는 아직 생성되지 않아 빈 배열입니다. Mock의 질문 근거·보기는 데모 데이터이며 실서버의 누락 정보를 FE가 채워 넣지 않습니다.

- **PR 번호가 없는 커밋 묶음과 직접 작성 카드도 되묻기 대상이다.** `sourceType` 이 어느 쪽인지 말한다.
- S→T→A→R 순서대로 기계적으로 묻지 않는다. **근거가 있는데 비었거나 확인이 필요한 칸을 먼저** 묻는다.
- **질문 상한은 서버 정책이다.** 응답의 `maxTurns` 로 내려주고, 프론트는 그 값만 쓴다
  (화면에 남아 있던 4회 상수는 응답 전 자리 지킴용 기본값으로만 남겼다).
- 답변 출처는 세 가지로 구분한다: AI 가 준 보기 / 사용자가 그 보기를 고름(`USER_SELECTED`) / 사용자가 직접 씀(`USER_STATED`).
  **이 내부 이름은 화면에 노출하지 않는다** — "보기에서 고른 것", "내가 말한 것"으로 번역해 보여 준다.

### 아직 서버 쪽에 남은 일 (FE 기대 계약)
- 단계별 마지막 완료 지점을 Job 에 저장해, 재시도 때 처음부터 다시 돌리지 않고 이어서 처리하기.
- 카드 confirm·regenerate·metadata 및 되묻기 생성/답변 HTTP 경로. 이 문서에 기대 응답이 있다는 이유로 구현됨을 뜻하지 않습니다. regenerate와 metadata는 실제 모드에서 capability를 확인합니다.

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

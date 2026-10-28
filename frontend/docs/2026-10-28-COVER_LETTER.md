# 자소서 — 문항별 카드 고르기 · 모자란 재료 묻기 · 초안 쓰기 (#484)

계약만 정한 문서다. 서버 구현은 아직 없고, 화면은 이 문서와 Mock 으로 먼저 시작할 수 있다. #301 의 예전 계약(문항 하나·매칭 기반, `2026-10-19-COVER_LETTER.md`)을 대신한다.

## 흐름

| 화면 | 사용자가 하는 일 | `stage` |
|---|---|---|
| ① 입력 | 지원 직무·메모·문항(글자 수)을 넣고 시작 | (만들면 바로 ②) |
| ② 기다림 | AI 가 문항마다 쓸 카드를 고른다 | `ASSIGNING` |
| ③ 카드 확인 | 문항별 중심·보조 카드를 확인하거나 바꾸고 '다음' | `ASSIGNED` |
| ④ 질문 | AI 가 카드에 모자란 재료를 묻는다. 답하거나 넘기고 '쓰기' | `ASKING` → `ASKED` |
| ⑤ 기다림 | AI 가 문항마다 쓰고, 검사에 걸리면 다시 쓰고, 마지막에 전체를 검토한다 | `DRAFTING` |
| ⑥ 초안 | 고치고 저장·복사 | `DRAFTED` |

- 어느 단계든 실패하면 `FAILED` 와 `failedStep`(`ASSIGN` · `ASK` · `DRAFT`)이 온다. `POST …/retry` 로 그 단계만 다시 돈다.
- AI 가 도는 단계(`ASSIGNING` · `ASKING` · `DRAFTING`)에서는 응답의 `pollAfterMs`(1,500) 마다 `GET /cover-letters/{id}` 를 다시 부른다. 그 밖의 단계는 `pollAfterMs: null` 이다. 기존 Job API(`/jobs`)는 쓰지 않는다.
- 초안을 받은 뒤 카드·답을 바꿔 다시 쓰는 기능과 AI 가 쓰는 글을 한 줄씩 띄우는 기능은 이번 계약에 없다.

## API

| | 요청 | 응답 |
|---|---|---|
| `POST /cover-letters` | `{ jobRole, memo, questions: [{ text, charLimit }] }`, 헤더 `Idempotency-Key: <UUID v4>` | `CoverLetter`(`stage: ASSIGNING`). 같은 사용자·같은 키는 보낸 내용과 상관없이 처음 만든 자소서를 돌려준다. 화면은 입력을 고치면 새 키를 만든다(같은 키는 내용을 바꾸지 않은 재전송에만 쓴다) |
| `GET /cover-letters/{id}` | | `CoverLetter` |
| `GET /cover-letters` | | `CoverLetterSummary[]` 최근 만든 순 |
| `PUT /cover-letters/{id}/questions/{qid}/cards` | `{ primaryCardId, supportingCardId }` | `CoverLetter` — `ASSIGNED` 에서만 |
| `POST /cover-letters/{id}/asks` | | `CoverLetter`(`stage: ASKING`) — 배정을 확정하고 질문 만들기를 시작한다. `ASSIGNED` 에서만 |
| `PUT /cover-letters/{id}/questions/{qid}/answers` | `{ answers: [{ item, text, skipped }] }` | `CoverLetter` — 그 문항의 답 전체를 바꾼다. `ASKED` 에서만 |
| `POST /cover-letters/{id}/drafts` | | `CoverLetter`(`stage: DRAFTING`) — 답하지 않은 질문은 넘긴 것으로 본다. `ASKED` 에서만 |
| `PATCH /cover-letters/{id}/questions/{qid}` | `{ text }` | `CoverLetter` — 고친 글을 저장한다. AI 원본(`aiText`)은 그대로 남는다. `DRAFTED` 에서만 |
| `POST /cover-letters/{id}/retry` | | `CoverLetter` — 실패한 단계만 다시 돈다. `FAILED` 에서만 |
| `DELETE /cover-letters/{id}` | | `{ ok: true }` — 어느 단계에서든 지운다. AI 가 돌던 결과는 버린다 |

**요청 값**
- `jobRole` 1~100자. `memo` 1,000자 이하, 없으면 `null`(지원 동기 문항에서 '이 회사를 고른 이유'로 쓴다).
- `questions` 1~10개. `text` 1~500자, `charLimit` 공백 포함 100~5,000 정수이고 `null` 이면 700.
- `primaryCardId` · `supportingCardId` 는 카드 API 와 같은 공개 id(UUID)이고 `null` 이 될 수 있다. 내 확정 카드만 고를 수 있다. 다른 문항과 겹쳐도 막지 않는다. 중심과 보조가 같으면 400.
- `answers[].item` 은 그 문항 `asks` 에 있는 항목만. `skipped: true` 면 `text` 는 `null`.
- `PATCH` 의 `text` 는 10,000자 이하.
- 모든 글에서 U+0000 은 400.

**오류**
- 400 `INVALID_REQUEST` — 위 값 규칙을 어김.
- 404 `NOT_FOUND` — 없는 자소서·문항, 남의 자소서.
- 409 `INVALID_STATE` — 그 단계가 아닐 때(예: `ASKED` 가 아닌데 답 저장).
- 422 `NO_CONFIRMED_CARDS` — 확정 카드가 한 장도 없어 만들 수 없다. 화면은 "확정한 카드가 없어요" 와 카드 만들기로 안내한다.
- 429 `RATE_LIMITED` — 만들기와 다시 시도만 센다(1분 2번 · 하루 10번). AI 를 새로 돌리는 요청이 이 둘뿐이다.

## `CoverLetter`

```jsonc
{ "id": "UUID", "stage": "ASSIGNING|ASSIGNED|ASKING|ASKED|DRAFTING|DRAFTED|FAILED",
  "failedStep": "ASSIGN|ASK|DRAFT" | null, "errorCode": "AI_UNAVAILABLE|INTERNAL_ERROR" | null, "retryable": true,
  "pollAfterMs": 1500 | null,
  "jobRole": "백엔드 개발", "memo": "…" | null, "createdAt": "ISO", "updatedAt": "ISO",
  "questions": [{
    "id": "UUID", "seq": 1, "text": "지원 동기와 입사 후 포부를 적어 주세요", "charLimit": 700,
    "theme": "COLLABORATION|CONFLICT|PROBLEM_SOLVING|LEADERSHIP|CHALLENGE|FAILURE|GROWTH|MOTIVATION" | null,
    // ③ 카드 — ASSIGNED 부터
    "primaryCard": { "id", "title", "kind": "TECH|QUALITATIVE" } | null,
    "supportingCard": { "id", "title", "kind" } | null,
    "assignedBy": "SUPERVISOR|SUB_AGENT|CODE|FAILED|USER" | null,
    "pickReason": "…" | null,
    "considered": [{ "cardId", "title", "note": "…" | null }],
    "materials": ["SITUATION", "ACTION"],
    // ④ 질문 — ASKED 부터
    "asks": [{ "item": "RESULT", "question": "…", "required": true,
               "answer": { "text": "…", "reused": false } | null, "skipped": false }],
    // ⑥ 초안 — DRAFTED
    "draft": { "text": "…", "aiText": "…", "edited": false, "charCount": 642, "overBy": 0,
               "sentences": [{ "seq": 1, "text": "…" }],
               "marks": [{ "sentenceSeq": 2, "code": "UNSOURCED_NUMBER", "value": "30%" }],
               "blanks": ["LEARNING"], "source": "LLM|SKELETON" } | null
  }],
  "steps": { "assign": [ … ], "draft": [ … ] } }
```

- `theme`: AI 가 알아본 문항 유형. 카드의 문항 유형 7개에 `MOTIVATION`(지원 동기)을 더했다. 카드 고르기에 실패하면 `null`.
- `assignedBy`: 최종 배정을 누가 정했는지. `SUPERVISOR`(감독이 겹침·균형을 보고 정함) · `SUB_AGENT`(문항 하나뿐이거나 겹침이 없어 그 문항의 1순위) · `CODE`(감독 답을 못 받아 코드가 2순위로 겹침을 정리) · `FAILED`(그 문항 카드 고르기 실패 — 사용자가 골라야 함) · `USER`(사용자가 바꿈).
- `primaryCard: null` 이고 `assignedBy` 가 `FAILED` 가 아니면 '맞는 카드 없음'이다. 화면은 이 유형의 경험 카드를 만들도록 안내할 수 있다.
- `considered`: AI 가 견준 카드 최대 3장과 짧은 평. 화면 ②·③에서 'AI 가 무엇을 보고 골랐는지'를 보여 줄 때 쓴다.
- `materials`: 중심 카드의 상황·행동·결과 문장과 같은 카드로 전에 받은 답에 이미 있는 재료(`SITUATION` · `ACTION` · `RESULT` · `LEARNING` · `TRIGGER`). 화면 ③ '재료 점검'에 쓴다. 무엇이 꼭 필요한지는 ④의 `required` 가 정한다.
- `asks`: 문항마다 최대 3개. `required: true` 는 없으면 초안에 빈칸이 남는 항목이다. 같은 카드로 전에 답한 배운 점·계기는 `answer` 를 미리 채워 `reused: true` 로 온다(고쳐서 저장할 수 있다). 직무 연결·지원 이유는 회사마다 달라 미리 채우지 않는다.
- 항목(`item`): `SITUATION` 상황 · `ACTION` 행동 · `RESULT` 결과 · `LEARNING` 배운 점 · `TRIGGER` 계기 · `JOB_LINK` 지원 직무와 이어지는 점 · `REASON` 지원 이유.
- `draft.text` 는 지금 글(고쳤으면 고친 글), `aiText` 는 AI 원본이다. `sentences` · `marks` 는 `aiText` 의 문장 번호를 가리킨다. 고친 뒤 표시를 어떻게 보일지는 화면이 정한다.
- `draft.charCount` 는 공백 포함 글자 수이고 `[항목: …]` 빈칸은 세지 않는다. `overBy` 는 넘은 만큼(넘지 않으면 0). 2번 다시 써도 넘치면 자르지 않고 그대로 보낸다.
- `draft.blanks`: 재료가 없어 `[배운 점: 배운 점을 적어 주세요]` 처럼 빈칸으로 남긴 항목. 사용자가 채운다.
- `draft.marks[].code`: `UNSOURCED_NUMBER`(근거에 없는 수치) · `UNSOURCED_TERM`(근거에 없는 영문 낱말) · `REPEATED`(다른 문항과 같은 이야기) · `UNSUPPORTED_CLAIM`(카드·답에 없는 역할·성과) · `OFF_TOPIC`(문항이 묻는 것을 벗어남). `value` 는 걸린 수치·낱말 그대로, 감독이 찾은 것은 `null`.
- `draft.source: SKELETON` 은 AI 가 쓰지 못해 카드 문장과 빈칸으로 만든 뼈대다.

**`CoverLetterSummary`** — `{ id, stage, jobRole, questionCount, firstQuestion, createdAt, updatedAt }`

## AI 가 한 일 (`steps`)

서버는 값만 보내고 문장은 화면이 만든다. 순서는 `seq`(1부터)다. `questionId`·`cardId` 는 위와 같은 공개 id 다.

**`steps.assign`** — `{ seq, actor, kind, questionId, cardId, reason }`

| `actor` · `kind` | 뜻 | 문장 예시 |
|---|---|---|
| `SUB_AGENT` · `PICKED` | 문항 담당이 1순위를 골랐다(`reason` 한 줄) | "2번 문항: '결제 중복 승인 막기'를 골랐어요 — 이중 결제를 막은 경험이 직무와 이어져서" |
| `SUB_AGENT` · `NO_FIT` | 맞는 카드가 없다고 봤다 | "3번 문항: 맞는 카드를 찾지 못했어요" |
| `SUB_AGENT` · `FAILED` | 그 문항 고르기에 실패했다 | "3번 문항: 카드를 고르지 못했어요. 직접 골라 주세요" |
| `SUPERVISOR` · `CHECKED` | 감독이 문항 전체를 보고 확인했다 | "모든 문항을 함께 보고 겹치는 카드를 확인했어요" |
| `SUPERVISOR` · `SWITCHED` | 감독이 그 문항을 2순위 카드로 바꿨다(`reason`) | "1번 문항은 '배포 시간 단축'으로 바꿨어요 — 3번 문항이 같은 카드를 더 필요로 해서" |
| `SUPERVISOR` · `FAILED` | 감독 답을 받지 못했다 | "문항 전체를 함께 보지 못했어요" |
| `CODE` · `RESOLVED` | 코드가 겹친 문항을 2순위 카드로 정리했다(`cardId`) | "겹치는 카드를 2순위 '배포 시간 단축'으로 정리했어요" |

**`steps.draft`** — `{ seq, actor, kind, questionId, code, value, sentenceSeq, action, reason }`

| `kind` | 뜻 · `value` | 문장 예시 |
|---|---|---|
| `WROTE` | 초안을 썼다 · 글자 수 | "1번 문항 초안을 썼어요(642자)" |
| `CHECK_PASSED` | 검사를 통과했다 · 글자 수 | "근거와 글자 수 검사를 통과했어요" |
| `CHECK_FAILED` | 검사에 걸렸다 · 걸린 값(`code` 위와 같음 + `OVER_LIMIT` 넘은 글자 수 · `MISSING_BLANK` 빠뜨린 빈칸 항목) | "'30%'는 카드에 없는 수치예요" |
| `DECIDED` | 걸린 것을 고칠지(`action: FIX`) 둘지(`KEEP`, 검사기가 잘못 건 것) 정했다 · `reason` | "'Kubernetes'는 카드의 'k8s'와 같은 뜻이라 그대로 뒀어요" |
| `REWROTE` | 다시 썼다 · 글자 수 | "고쳐서 다시 썼어요(598자)" |
| `REVERTED` | 다시 쓴 글이 새 문제를 만들어 앞 글로 되돌렸다 | "다시 쓴 글이 새 문제를 만들어 앞 초안으로 돌렸어요" |
| `MARKED` | 고치지 못한 것을 '확인 필요'로 표시했다 · 걸린 값 | "'30%'에 확인 필요 표시를 남겼어요" |
| `SKELETON` | AI 가 쓰지 못해 뼈대로 대신했다 | "초안을 쓰지 못해 카드 문장으로 뼈대를 만들었어요" |
| `REVIEWED` | 감독이 모든 문항을 함께 검토했다 · 걸린 문장 수 | "모든 문항을 함께 읽고 2문장을 다시 보게 했어요" |
| `SENT_BACK` | 감독이 그 문장을 돌려보냈다 · `code`·`sentenceSeq`·`reason` | "2번 문항 3번째 문장이 1번 문항과 같은 이야기라 다시 쓰게 했어요" |
| `FAILED` | 그 단계가 실패했다 | "전체 검토를 하지 못했어요" |

`actor` 는 `SUB_AGENT`(문항 담당) · `SUPERVISOR`(감독) · `CODE`(코드) 중 하나다. 모르는 값이 오면 그 줄을 건너뛴다.

## 예시 문항 버튼 (서버 일 없음)

① 문항 칸 옆 예시 버튼의 문장. 모두에게 같다. 화면은 카드 목록의 문항 유형(`themes`)을 세어 많은 유형 3개와 지원 동기를 먼저 보이고, 나머지는 '더 보기'에 둔다. 카드가 없거나 유형이 모자라면 아래 순서로 채운다.

| 유형 | 문장 |
|---|---|
| `MOTIVATION` | 지원 동기와 입사 후 포부를 적어 주세요 |
| `PROBLEM_SOLVING` | 어려운 문제를 해결한 경험을 적어 주세요 |
| `COLLABORATION` | 팀으로 함께 성과를 낸 경험을 적어 주세요 |
| `CONFLICT` | 의견 차이를 조율해 해결한 경험을 적어 주세요 |
| `LEADERSHIP` | 주도적으로 일을 이끈 경험을 적어 주세요 |
| `CHALLENGE` | 가장 도전적이었던 경험을 적어 주세요 |
| `FAILURE` | 실패를 극복한 경험을 적어 주세요 |
| `GROWTH` | 스스로 성장한 경험과 배운 점을 적어 주세요 |

- 글자 수 칸을 비우면 700자로 본다.

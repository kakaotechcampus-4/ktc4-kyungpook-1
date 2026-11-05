# 자소서 — 문항별 카드 고르기 · 모자란 재료 묻기 · 초안 쓰기 (#484)

계약만 정한 문서다. 서버 구현은 아직 없고, 화면은 이 문서와 Mock 으로 먼저 시작할 수 있다. #301 의 예전 계약(문항 하나·매칭 기반, `2026-10-19-COVER_LETTER.md`)을 대신한다.

## 흐름

| 화면 | 사용자가 하는 일 | `stage` |
|---|---|---|
| ① 입력 | 지원 직무·지원 이유(필수)·강점(선택, 0~3개)·문항(글자 수)을 넣고 시작 | (만들면 바로 ②) |
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
| `POST /cover-letters` | `{ jobRole, memo, strengths, questions: [{ text, charLimit }], postingId }`, 헤더 `Idempotency-Key: <UUID v4>` | `CoverLetter`(`stage: ASSIGNING`). 같은 사용자·같은 키는 보낸 내용과 상관없이 처음 만든 자소서를 돌려준다. 화면은 입력을 고치면 새 키를 만든다(같은 키는 내용을 바꾸지 않은 재전송에만 쓴다) |
| `GET /cover-letters/{id}` | | `CoverLetter` |
| `GET /cover-letters` | | `CoverLetterSummary[]` 최근 만든 순 |
| `PUT /cover-letters/{id}/questions/{qid}/cards` | `{ primaryCardId, supportingCardId }` | `CoverLetter` — `ASSIGNED` 에서만 |
| `POST /cover-letters/{id}/asks` | | `CoverLetter`(`stage: ASKING`) — 배정을 확정하고 질문 만들기를 시작한다. `ASSIGNED` 에서만 |
| `PUT /cover-letters/{id}/questions/{qid}/answers` | `{ answers: [{ item, text, skipped }] }` | `CoverLetter` — 그 문항의 답 전체를 바꾼다. 보내지 않은 항목은 답이 없는 것으로 남으므로, 미리 채운 지난 답(`reused: true`)도 다시 보내야 남는다. `ASKED` 에서만 |
| `POST /cover-letters/{id}/drafts` | | `CoverLetter`(`stage: DRAFTING`) — 답하지 않은 질문은 넘긴 것으로 본다. `ASKED` 에서만 |
| `PATCH /cover-letters/{id}/questions/{qid}` | `{ text }` | `CoverLetter` — 고친 글을 저장한다. AI 원본(`aiText`)은 그대로 남는다. `DRAFTED` 에서만 |
| `DELETE /cover-letters/{id}/questions/{qid}/marks/{sentenceSeq}` | | `CoverLetter` — AI 추론 문장을 맞다고 확인해 그 문장의 `INFERRED` 표시만 지운다(#719). 글은 그대로. `DRAFTED` 에서만, 그 문장에 `INFERRED` 표시가 없으면 409 |
| `POST /cover-letters/{id}/questions/{qid}/polish` | | `CoverLetter` — 문항의 지금 글(`draft.text`)을 진단하고 고쳐 쓴 글을 받아 그 문항 `polish` 에 둔다. 앞 결과는 덮는다. AI 를 기다렸다가 답해 수십 초 걸릴 수 있다. 지금 글·문장·표시는 적용 전까지 그대로. `DRAFTED` 에서만, 지금 글이 비었으면 400 |
| `POST /cover-letters/{id}/questions/{qid}/polish/apply` | | `CoverLetter` — 첨삭한 글로 `draft` 의 `text`·`sentences`·`marks`·`blanks`·`charCount`·`overBy` 를 바꾸고 `edited: false`, `polishAppliedCount` +1, `polish: null`. `aiText` 는 그대로. `DRAFTED` 에서만, `polish` 가 없으면 409 |
| `DELETE /cover-letters/{id}/questions/{qid}/polish` | | `CoverLetter` — `polish` 를 비운다. 없어도 200. `DRAFTED` 에서만 |
| `GET /cover-letters/{id}/turns` | | `{ turns: Turn[] }` — 채우기 대화 전체, 오래된 말부터. 어느 단계에서든, 없으면 `[]` |
| `POST /cover-letters/{id}/questions/{qid}/turns/open` | | `TurnResult` — 그 문항 이야기를 시작해 AI 의 첫 말을 더한다. `DRAFTED` 에서만 |
| `POST /cover-letters/{id}/questions/{qid}/turns` | `{ text }` | `TurnResult` — 사용자의 말과 AI 의 다음 말을 더한다. AI 가 `FILLED` 라고 하면 정리한 답을 그 문항 답에 저장하고(같은 항목에 앞 답이 있으면 뒤에 덧붙이고 1,000자에서 자름, 상황·행동·결과·배운 점·계기면 중심 카드에도 기억) 그 답으로 문항 글만 다시 써 `draft` 의 `text`·`sentences`·`marks`·`blanks`·`charCount`·`overBy` 를 바꾼다(`edited: false`, `aiText` 그대로, `polish: null`). `DRAFTED` 에서만 |
| `POST /cover-letters/{id}/questions/{qid}/turns/skip` | | `TurnResult` — 지금 질문을 넘기고 AI 의 다음 말(다음 빈 재료 또는 마무리)을 더한다. `DRAFTED` 에서만 |
| `POST /cover-letters/{id}/retry` | | `CoverLetter` — 실패한 단계만 다시 돈다. `FAILED` 에서만 |
| `DELETE /cover-letters/{id}` | | `{ ok: true }` — 어느 단계에서든 지운다. AI 가 돌던 결과는 버린다 |

**요청 값**
- `jobRole` 1~100자.
- `memo`(지원 이유)는 **필수**이고 1,000자 이하, 아래 [지원 이유 규칙](#지원-이유-규칙--강점-738)을 지켜야 한다(지원 동기 문항에서 '이 회사를 고른 이유'로 쓴다). 이미 만든 자소서의 `memo: null` 은 그대로 읽고, 다시 시도(`POST …/retry`)는 memo 를 다시 검사하지 않는다.
- `strengths` 는 강점 코드 0~3개, 겹치지 않게. 빠지면 `[]`. 모르는 코드·4개 이상·중복은 400.
- `questions` 1~10개. `text` 1~500자, `charLimit` 공백 포함 100~5,000 정수이고 `null` 이면 700.
- `postingId` 는 공고 상세에서 '이 공고로 자소서 쓰기' 로 시작할 때만 그 공고 id(UUID), 아니면 `null`. 서버가 그때의 공고(회사·제목·주요 업무·기술 조건)와 맞춰 본 판단을 자소서에 남기고, 카드 고르기·질문·초안이 그 기준으로 돈다. 뒤에 카드나 공고가 바뀌어도 이 자소서의 기준은 그대로다. 맞춰 보기 전이어도 막지 않고 판단 없는 조건은 판단 없이 남긴다 — 버튼을 맞춰 보기 뒤에만 보이는 것은 화면 규칙이다.
- `primaryCardId` · `supportingCardId` 는 카드 API 와 같은 공개 id(UUID)이고 `null` 이 될 수 있다. 내 확정 카드만 고를 수 있다. 다른 문항과 겹쳐도 막지 않는다. 중심과 보조가 같으면 400.
- `answers[].item` 은 그 문항 `asks` 에 있는 항목만, 한 번씩. `skipped: true` 면 `text` 는 `null`. 넘기지 않은 답의 `text` 는 1~1,000자(공백뿐이면 400) — 화면은 입력 칸에 1,000자 제한을 건다.
- `PATCH` 의 `text` 는 10,000자 이하. 저장하면 서버가 고친 글로 `charCount` · `overBy` · `blanks` 를 다시 센다(빈칸은 AI 가 넣은 `[항목: …]` 문구가 글자 그대로 남아 있는 항목). `sentences` · `marks` 는 `aiText` 기준 그대로다. `edited` 는 고친 글이 `aiText` 와 다르면 `true`.
- 대화 `POST …/turns` 의 `text` 는 1~500자(공백뿐이면 400).
- 모든 글에서 U+0000 은 400.

**오류**
- 400 `INVALID_REQUEST` — 위 값 규칙을 어김.
- 404 `NOT_FOUND` — 없는 자소서·문항, 남의 자소서. 만들기에서는 `postingId` 가 UUID 가 아니거나 공고 상세에서 열 수 없는 공고("공고를 찾을 수 없습니다.").
- 409 `INVALID_STATE` — 그 단계가 아닐 때(예: `ASKED` 가 아닌데 답 저장, `DRAFTED` 가 아닌데 대화), 첨삭 결과 없이 적용.
- 422 `NO_CONFIRMED_CARDS` — 확정 카드가 한 장도 없어 만들 수 없다. 화면은 "확정한 카드가 없어요" 와 카드 만들기로 안내한다.
- 429 `RATE_LIMITED` — 만들기와 다시 시도를 합쳐 센다(1분 2번 · 하루 10번). 첨삭 받기는 따로 센다(1분 3번 · 하루 30번). 적용·버리기는 세지 않는다. 대화(이야기 시작·보내기·넘기기)는 합쳐 따로 센다(1분 6번 · 하루 60번), 대화 읽기는 세지 않는다.
- 503 `AI_UNAVAILABLE` — 첨삭 받기·대화에서 AI 가 잠시 응답하지 못함. 저장한 것이 없어(대화는 보낸 말도 저장하지 않음) 다시 요청해도 된다. 대화에서 답으로 다시 쓰기만 실패하면 503 이 아니라 200 + `rewriteFailed: true` 다. AI 응답이 계약과 달라 다시 해도 같으면 500 `INTERNAL_ERROR`.

## `CoverLetter`

```jsonc
{ "id": "UUID", "stage": "ASSIGNING|ASSIGNED|ASKING|ASKED|DRAFTING|DRAFTED|FAILED",
  "failedStep": "ASSIGN|ASK|DRAFT" | null, "errorCode": "AI_UNAVAILABLE|INTERNAL_ERROR" | null, "retryable": true | false | null,
  "pollAfterMs": 1500 | null,
  "jobRole": "백엔드 개발", "memo": "…" | null,
  "strengths": ["METICULOUS", "USER_FOCUS"],   // 고른 강점 코드, 없으면 []
  "memoUsable": true | false | null,            // 질문 단계가 지원 이유를 글로 쓸 만하다고 봤는지. 판정 전·memo 없음이면 null
  "posting": { "id": "UUID", "company": "토스", "title": "Server Developer" } | null,  // 공고에서 시작한 자소서만
  "createdAt": "ISO", "updatedAt": "ISO",
  "questions": [{
    "id": "UUID", "seq": 1, "text": "지원 동기와 입사 후 포부를 적어 주세요", "charLimit": 700,
    "theme": "COLLABORATION|CONFLICT|PROBLEM_SOLVING|LEADERSHIP|CHALLENGE|FAILURE|GROWTH|MOTIVATION" | null,
    // ③ 카드 — ASSIGNED 부터
    "primaryCard": { "id", "title", "kind": "TECH|QUALITATIVE" } | null, "primaryCardDeleted": false,
    "supportingCard": { "id", "title", "kind" } | null,
    "assignedBy": "SUPERVISOR|SUB_AGENT|CODE|FAILED|USER" | null,
    "pickReason": "…" | null,
    "considered": [{ "cardId", "title", "note": "…" | null }],
    "materials": ["SITUATION", "ACTION"],
    // ④ 질문 — ASKED 부터
    "asks": [{ "item": "RESULT", "question": "…" | null, "templates": ["___ 를 줄였어요"], "required": true,
               "answer": { "text": "…", "reused": false } | null, "skipped": false }],
    // ⑥ 초안 — DRAFTED
    "draft": { "text": "…", "aiText": "…", "edited": false, "charCount": 642, "overBy": 0,
               "sentences": [{ "seq": 1, "text": "…" }],
               "marks": [{ "sentenceSeq": 2, "code": "UNSOURCED_NUMBER", "value": "30%" }],
               "blanks": ["LEARNING"], "source": "LLM|SKELETON" } | null,
    // ⑥ 쓰는 중 — DRAFTING 동안만
    "writing": { "text": "…", "round": 0 } | null,
    // ⑥ 첨삭 미리 보기 — DRAFTED 에서 첨삭을 받은 뒤 적용·버리기 전까지
    "polish": { "summary": "…",
                "points": [{ "seq": 1, "kind": "GOOD|WEAK", "category": "FIT|EVIDENCE|READABILITY|OVERLAP|TECH_TERMS",
                             "title": "…", "detail": "…", "sentenceSeqs": [6] }],
                "draft": { "text": "…", "sentences": [{ "seq": 1, "text": "…" }], "charCount": 640, "overBy": 0,
                           "marks": [{ "sentenceSeq": 2, "code": "INFERRED", "value": null }], "blanks": [] },
                "createdAt": "ISO" } | null,
    "polishAppliedCount": 0
  }],
  "steps": { "assign": [ … ], "draft": [ … ] } }
```

- 응답 모양은 단계와 상관없이 같다. 아직 없는 값은 `null`, 목록은 `[]` 로 온다(`asks` · `draft` · `steps.draft` 포함). `retryable` 은 `FAILED` 일 때만 `true`·`false` 이고 그 밖에는 `null` 이다 — 화면은 `true` 일 때만 다시 시도를 연다.
- `theme`: AI 가 알아본 문항 유형. 카드의 문항 유형 7개에 `MOTIVATION`(지원 동기)을 더했다. 카드 고르기에 실패하면 `null`.
- `assignedBy`: 최종 배정을 누가 정했는지. `SUPERVISOR`(감독이 겹침·균형을 보고 정함) · `SUB_AGENT`(문항 하나뿐이거나 겹침이 없어 그 문항의 1순위) · `CODE`(감독 답을 못 받아 코드가 2순위로 겹침을 정리) · `FAILED`(그 문항 카드 고르기 실패 — 사용자가 골라야 함) · `USER`(사용자가 바꿈).
- `primaryCard: null` 이고 `assignedBy` 가 `FAILED` 가 아니며 `primaryCardDeleted` 가 `false` 면 '맞는 카드 없음'이다. 화면은 이 유형의 경험 카드를 만들도록 안내할 수 있다.
- `primaryCardDeleted: true` 는 배정된 중심 카드를 사용자가 지운 것이다(`primaryCard` 는 `null`). 화면은 "고른 카드가 지워졌어요. 다시 골라 주세요" 처럼 다시 고르게 안내한다.
- `considered`: AI 가 견준 카드 최대 3장과 짧은 평. 화면 ②·③에서 'AI 가 무엇을 보고 골랐는지'를 보여 줄 때 쓴다.
- `materials`: 중심 카드의 상황·행동·결과 문장과 같은 카드로 전에 받은 답에 이미 있는 재료(`SITUATION` · `ACTION` · `RESULT` · `LEARNING` · `TRIGGER`). 화면 ③ '재료 점검'에 쓴다. 무엇이 꼭 필요한지는 ④의 `required` 가 정한다.
- `asks`: AI 가 묻는 항목은 문항마다 최대 4개다. `templates` 는 질문마다 답을 돕는 빈칸 틀 0~2개로, 카드에 적힌 사실만 들어 있고 사용자가 채울 곳은 `___` 다. 지난 답으로 미리 채운 줄(`question: null`)은 `[]` 다. `required: true` 는 없으면 초안에 빈칸이 남는 항목이다. 같은 카드로 전에 답한 상황·행동·결과·배운 점·계기는 AI 가 다시 묻지 않고, 서버가 `asks` 끝에 `question: null` · `answer: { text, reused: true }` 로 붙인다 — 화면은 항목 이름으로 제목을 만들고("배운 점 (지난번에 답한 내용)") 사용자는 고쳐서 저장할 수 있다. 직무 연결·지원 이유는 회사마다 달라 미리 채우지 않는다.
- 항목(`item`): `SITUATION` 상황 · `ACTION` 행동 · `RESULT` 결과 · `LEARNING` 배운 점 · `TRIGGER` 계기 · `JOB_LINK` 지원 직무와 이어지는 점 · `REASON` 지원 이유.
- `draft.text` 는 지금 글(고쳤으면 고친 글), `aiText` 는 AI 원본이다. `sentences` · `marks` 는 `aiText` 의 문장 번호를 가리킨다. 고친 뒤 표시를 어떻게 보일지는 화면이 정한다.
- `draft.charCount` 는 공백 포함 글자 수이고 `[항목: …]` 빈칸과 `[더 쓸 거리: …]` 는 세지 않는다. `overBy` 는 넘은 만큼(넘지 않으면 0). 2번 다시 써도 넘치면 자르지 않고 그대로 보낸다.
- `draft.blanks`: 재료가 없어 `[배운 점: 배운 점을 적어 주세요]` 처럼 빈칸으로 남긴 항목. 사용자가 채운다.
- 글이 목표(글자 수 상한의 70%)보다 짧은데 카드·답에 더 쓸 사실이 없으면, AI 가 모자란 내용 자리에 `[더 쓸 거리: 무엇을 쓰면 되는지]` 를 한두 개 넣는다. 항목이 아니라 `blanks` 에는 없으므로, 화면은 글에서 이 모양을 찾아 강조한다.
- `draft.marks[].code`: `UNSOURCED_NUMBER`(근거에 없는 수치) · `UNSOURCED_TERM`(근거에 없는 영문 낱말) · `REPEATED`(다른 문항과 같은 이야기) · `UNSUPPORTED_CLAIM`(카드·답에 없는 역할·성과) · `OFF_TOPIC`(문항이 묻는 것을 벗어남) · `INFERRED`(AI 가 재료에 없는 해석 — 배운 점·판단 이유·직무 연결·포부 — 을 추론해 쓴 문장. 사용자가 확인하면 위 `DELETE` 로 지운다). `value` 는 걸린 수치·낱말 그대로, 감독이 찾은 것과 `INFERRED` 는 `null`.
- `draft.source: SKELETON` 은 AI 가 쓰지 못해 카드 문장과 빈칸으로 만든 뼈대다.
- `writing`: 초안을 쓰는 동안(`DRAFTING`) AI 가 그 문항에 지금까지 쓴 글이다. 첫 초안(`round` 0)은 문장이 끝날 때마다 늘어나고, 고쳐 쓸 때는 `round` 가 1씩 늘며 다 쓴 새 글로 한 번에 바뀐다(화면은 앞 글과 문장 단위로 견줘 바뀐 문장만 다시 그린다). 검토 뒤 고쳐 쓴 글이 새 문제로 되돌려지면 앞 글로 돌아온다. 아직 쓰기 전이거나 단계가 끝나면(`DRAFTED`·`FAILED`) `null` 이고, 다 쓰면 `draft` 가 채워진다. 빠뜨린 빈칸은 다 쓴 뒤에 붙으므로 `writing` 에는 없을 수 있다.

- `polish`: 첨삭 미리 보기. `points` 는 아쉬운 점(`WEAK`) 최대 4개 · 잘한 점(`GOOD`) 최대 3개이고, `category` 는 `FIT` 문항 적합성 · `EVIDENCE` 근거 · `READABILITY` 읽기 쉬움 · `OVERLAP` 겹침 · `TECH_TERMS` 기술 용어다. `sentenceSeqs` 는 **고친 글(`polish.draft.sentences`)의 문장 번호**다(없으면 `[]`). `polish.draft` 의 `marks`·`blanks`·`charCount` 는 `draft` 와 같은 규칙으로 고친 글 기준이다.
- 글을 직접 고쳐 저장하면(PATCH) 첨삭 미리 보기는 사라진다(`polish: null`). 앞뒤 공백만 다른 글은 그대로 둔다.
- `polishAppliedCount`: 첨삭을 적용한 횟수. 화면은 `n + 1` 판으로 보일 수 있다. 적용 뒤 `draft.sentences`·`marks` 는 적용한 글 기준이고 `aiText` 는 첫 AI 원본 그대로다.
- 화면(#727)은 모르는 `kind`·`category` 의 포인트만 버리고, 옛 응답에 `polishAppliedCount` 가 없으면 0 으로 읽는다.

**`Turn`** — 채우기 대화의 말 한 줄
```jsonc
{ "seq": 3, "role": "AI|USER", "questionId": "UUID", "text": "…",
  "item": "SITUATION|ACTION|RESULT|LEARNING|TRIGGER|JOB_LINK|REASON" | null, "status": "ASKING|FILLED|DONE|OFF_TOPIC" | null,
  "createdAt": "2026-11-03T01:02:03.456789Z" }
```
- 자소서 하나에 대화 하나라 `seq` 는 자소서 안 순번이고, 문항을 옮겨도 이어진다. `questionId` 는 그 말이 어느 문항 이야기인지다.
- `item` · `status` 는 AI 말에만 있고 사용자 말은 `null` 이다. `status`: `ASKING` 질문이 열림 · `FILLED` 이 답으로 문항을 다시 씀 · `DONE` 이 문항은 더 물을 게 없음 · `OFF_TOPIC` 자소서 재료가 아닌 말이라 돌려보냄.

**`TurnResult`** — `{ turns: Turn[], question: Question, rewriteFailed: boolean }` — `turns` 는 대화 전체, `question` 은 말한 문항(`CoverLetter.questions[]` 와 같은 모양, 다시 썼으면 바뀐 `draft`). `rewriteFailed: true` 는 답은 저장했지만 다시 쓰기에 실패했거나, AI 가 다시 쓴 글이 새 문제를 남겨 되돌렸거나, 그사이 글을 고쳐 글을 그대로 뒀다는 뜻이다.

**`CoverLetterSummary`** — `{ id, stage, jobRole, posting, questionCount, firstQuestion, createdAt, updatedAt }` — `posting` 은 `CoverLetter` 와 같은 모양이고 공고 없이 만들었으면 `null`

## AI 가 한 일 (`steps`)

서버는 값만 보내고 문장은 화면이 만든다. 순서는 `seq`(1부터)다. `questionId`·`cardId` 는 위와 같은 공개 id 다.

카드 고르기·초안 쓰기가 도는 동안(`ASSIGNING`·`DRAFTING`)에도 AI 가 거친 단계가 생기는 대로 채워져, 조회할 때마다 늘어난다. 카드는 문항마다 동시에 골라 앞 번호가 나중에 끼어들 수 있으니 늘 `seq` 차례로 그린다. 단계가 끝나면 저장한 과정과 같고, 그 단계가 실패하면 그때까지 보이던 단계는 사라진다.

**`steps.assign`** — `{ seq, actor, kind, questionId, cardId, cardTitle, reason }`. `cardTitle` 은 그 카드의 지금 제목이고, 지운 카드면 `null` 이다.

| `actor` · `kind` | 뜻 | 문장 예시 |
|---|---|---|
| `SUB_AGENT` · `PICKED` | 문항 담당이 1순위를 골랐다(`reason` 한 줄) | "2번 문항: '결제 중복 승인 막기'를 골랐어요 — 이중 결제를 막은 경험이 직무와 이어져서" |
| `SUB_AGENT` · `NO_FIT` | 맞는 카드가 없다고 봤다 | "3번 문항: 맞는 카드를 찾지 못했어요" |
| `SUB_AGENT` · `FAILED` | 그 문항 고르기에 실패했다 | "3번 문항: 카드를 고르지 못했어요. 직접 골라 주세요" |
| `SUPERVISOR` · `CHECKED` | 감독이 문항 전체를 보고 확인했다 | "모든 문항을 함께 보고 겹치는 카드를 확인했어요" |
| `SUPERVISOR` · `SWITCHED` | 감독이 그 문항을 2순위 카드로 바꿨다(`reason`) | "1번 문항은 '배포 시간 단축'으로 바꿨어요 — 3번 문항이 같은 카드를 더 필요로 해서" |
| `SUPERVISOR` · `FAILED` | 감독 답을 받지 못했다 | "문항 전체를 함께 보지 못했어요" |
| `CODE` · `RESOLVED` | 코드가 겹친 문항을 2순위 카드로 정리했다(`cardId`) | "겹치는 카드를 2순위 '배포 시간 단축'으로 정리했어요" |

**`steps.draft`** — `{ seq, actor, kind, questionId, code, value, sentenceSeq, sentence, action, reason }`. `sentence` 는 `sentenceSeq` 가 가리키는 문장의 글(200자까지)로, 고쳐 쓰기 전 글의 문장이라 걸린 문장을 검토 기록으로 보여 줄 때 쓴다. `CHECK_FAILED`·`DECIDED`·`SENT_BACK`·`MARKED` 에만 있고, 문장을 가리키지 않는 것(`OVER_LIMIT` 등)은 `null` 이다.

| `kind` | 뜻 · `value` | 문장 예시 |
|---|---|---|
| `WROTE` | 초안을 썼다 · 글자 수 | "1번 문항 초안을 썼어요(642자)" |
| `CHECK_PASSED` | 검사를 통과했다 · 글자 수 | "근거와 글자 수 검사를 통과했어요" |
| `CHECK_FAILED` | 검사에 걸렸다 · 걸린 값(`code` 위와 같음 + `OVER_LIMIT` 넘은 글자 수 · `UNDER_LENGTH` 목표에 모자란 글자 수 · `MISSING_BLANK` 빠뜨린 빈칸 항목) | "'30%'는 카드에 없는 수치예요" |
| `DECIDED` | 걸린 것을 고칠지(`action: FIX`) 둘지(`KEEP`, 검사기가 잘못 건 것) 정했다 · `reason` | "'Kubernetes'는 카드의 'k8s'와 같은 뜻이라 그대로 뒀어요" |
| `REWROTE` | 다시 썼다 · 글자 수 | "고쳐서 다시 썼어요(598자)" |
| `REVERTED` | 다시 쓴 글이 새 문제를 만들어 앞 글로 되돌렸다 | "다시 쓴 글이 새 문제를 만들어 앞 초안으로 돌렸어요" |
| `MARKED` | 고치지 못한 것을 '확인 필요'로 표시했다 · 걸린 값. `code: INFERRED` 면 작성 AI·감독이 추론 문장에 단 표시다 | "'30%'에 확인 필요 표시를 남겼어요" · "추론한 문장이라 확인 필요 표시를 남겼어요" |
| `SKELETON` | AI 가 쓰지 못해 뼈대로 대신했다 | "초안을 쓰지 못해 카드 문장으로 뼈대를 만들었어요" |
| `REVIEWED` | 감독이 모든 문항을 함께 검토했다 · 걸린 문장 수 | "모든 문항을 함께 읽고 2문장을 다시 보게 했어요" |
| `SENT_BACK` | 감독이 그 문장을 돌려보냈다 · `code`·`sentenceSeq`·`reason` | "2번 문항 3번째 문장이 1번 문항과 같은 이야기라 다시 쓰게 했어요" |
| `FAILED` | 그 단계가 실패했다 | "전체 검토를 하지 못했어요" |

`actor` 는 `SUB_AGENT`(문항 담당) · `SUPERVISOR`(감독) · `CODE`(코드) 중 하나다. 모르는 값이 오면 그 줄을 건너뛴다.

## 채우기 대화 — 화면이 읽는 것 (#733)

초안(`DRAFTED`) 옆 패널의 채우기 탭이 부른다. 자소서 하나에 대화 하나이고, 말마다 어느 문항 얘기인지(`questionId`)가 붙는다. 화면은 `src/api/coverLetters.ts` 의 `useTurns` · `useOpenTurn` · `useReplyTurn` · `useSkipTurn` 으로 읽는다.

| | 요청 | 응답 |
|---|---|---|
| `GET /cover-letters/{id}/turns` | | `{ turns: Turn[] }` 오래된 것부터. 화면은 패널이 열릴 때 한 번 읽고, 뒤로는 아래 응답의 `turns` 로 바꾼다 |
| `POST /cover-letters/{id}/questions/{qid}/turns/open` | | `TurnReply` — 그 문항 이야기를 시작한다(AI 말 하나) |
| `POST /cover-letters/{id}/questions/{qid}/turns` | `{ text }` 1~500자 | `TurnReply` — 내 말 + AI 말. `FILLED` 면 서버가 그 문항을 다시 써서 `question.draft` 가 바뀌어 온다. 수십 초가 걸릴 수 있어 화면은 100초를 기다린다 |
| `POST /cover-letters/{id}/questions/{qid}/turns/skip` | | `TurnReply` — 지금 질문을 넘긴다 |

```jsonc
// Turn
{ "seq": 3, "role": "AI", "questionId": "<문항 id>", "text": "알려 준 대로 그 자리를 다시 썼어요.\n…", "item": "ACTION", "status": "FILLED", "createdAt": "…" }
// TurnReply
{ "turns": [Turn], "question": Question, "rewriteFailed": false }
```
- `role` 은 `AI` · `USER`. `status` 는 AI 말에만 있다: `ASKING`(질문이 열림) · `FILLED`(이 답으로 그 문항을 다시 씀) · `DONE`(이 문항은 더 묻지 않음) · `OFF_TOPIC`(재료가 아닌 말이라 돌려보냄, 질문은 그대로 열림). 모르는 `status`·`item` 은 `null` 로 읽고, 모양이 다른 말은 그 말만 버린다.
- 화면은 마지막 AI 말이 `DONE` 이 아니면 입력을 연다. `FILLED` 말에는 다음 질문이 붙어 오지 않는다 — 빈칸이 남았으면 화면이 그 문항(없으면 다음 문항)을 다시 열어(`turns/open`) 이어서 묻는다.
- `question` 은 `CoverLetter.questions[]` 와 같은 모양이고 화면은 자소서 캐시의 그 문항 자리에 넣는다. `rewriteFailed: true` 면 답은 저장됐지만 글은 그대로다.
- 오류: 400(글 규칙) · 409 `INVALID_STATE`(초안 단계가 아님, 열린 질문 없음) · 429 `RATE_LIMITED` · 503 `LLM_UNAVAILABLE`. 화면은 429(1분)·503·연결 실패를 '잠시 뒤 다시 보내 주세요' 한 줄과 다시 보내기로, 하루 한도는 그 말로 알린다.

## 지원 이유 규칙 · 강점 (#738)

지원 이유(`memo`)는 화면(`src/lib/reasonCheck.ts`)·서버(`ReasonCheck`)·Mock 이 같은 규칙, 같은 순서로 검사한다. 화면은 처음 걸린 규칙 하나를 칸 아래 한 줄로 보이고, 다 지켜야 시작 버튼을 켠다. 서버는 400 `INVALID_REQUEST` 와 처음 걸린 규칙의 한 문장을 준다(1,000자를 넘으면 그보다 먼저 "지원 이유는 1000자를 넘을 수 없습니다.").

| 순서 | 규칙 | 화면 안내 | 서버 메시지 |
|---|---|---|---|
| 1 | 공백(줄바꿈 포함)을 뺀 길이 20자 이상. 빠지면(`null`) 여기서 걸린다 | 20자 이상 적어 주세요 · 지금 N자 | 지원 이유를 20자 이상 적어 주세요. |
| 2 | 한글(가-힣)이 하나 이상 | 한국어로 적어 주세요 | 지원 이유는 한국어로 적어 주세요. |
| 3 | 공백·숫자가 아닌 같은 글자가 5번 이상, 또는 공백·숫자가 없는 같은 2~3자 조각이 5번 이상 이어짐('ㅋㅋㅋㅋㅋ' 막음, 'ㅋㅋㅋㅋ' 둠). 숫자는 세지 않는다('10000000원'·'2024' 는 둔다) | 같은 글자를 반복하지 말아 주세요 | 지원 이유에 같은 글자를 5번 이상 이어 쓸 수 없습니다. |
| 4 | URL 꼴 — `http://`·`https://`·`www.` 가 들어 있음(대소문자 무시). 글 속 'http' 낱말이나 'naver.com' 같은 이름은 둔다 | URL 은 넣을 수 없어요 | 지원 이유에는 URL 을 넣을 수 없습니다. |
| 5 | 이메일 꼴 — 공백 없는 글자 + `@` + 글자 + `.` + 글자(`\S+@\S+\.\S+` 토큰) | 이메일은 넣을 수 없어요 | 지원 이유에는 이메일 주소를 넣을 수 없습니다. |
| 6 | 한글(가-힣, 자모 ㄱ-ㅎ·ㅏ-ㅣ)·영문·숫자·공백과 문장부호 `. , ! ? · ' " ( ) - : ; / % & ~ … [ ] + # 「 」` 와 백틱만. U+0000·이모지·다른 나라 문자는 여기서 막는다 | 쓸 수 없는 글자가 있어요 | 지원 이유에는 한글·영문·숫자·흔한 문장부호만 쓸 수 있습니다. |

- 위 검사는 코드 가드다. 질문 단계(AI)가 한 번 더 '지원 이유로 읽히는 글인가' 를 보고 결과를 `memoUsable` 에 남긴다. `false` 면 서버는 그 뒤 AI 요청(쓰기·첨삭·대화·채우기)에 memo 를 보내지 않아 지원 동기 문항에 `[지원 이유: …]` 빈칸이 남고, 화면은 초안 알림 줄에 '지원 이유가 글로 쓰기 어려워서 채팅에서 다시 물어요 → 채우기' 를 띄운다(누르면 지원 동기 문항, 없으면 첫 문항의 채우기 대화를 연다). 판정 전이거나 memo 가 없는 옛 자소서는 `null` 이다. Mock 은 memo 에 '무시하고' 가 있으면 `false`, 아니면 질문 단계가 끝날 때 `true` 로 둔다.

강점은 12개 중 최대 3개. API 는 코드만 주고받고(응답 `strengths` 는 고른 순서) 한국어 이름은 화면(`src/lib/coverLetterCopy.ts` 의 `STRENGTHS`)에만 둔다. AI 요청(카드 고르기·질문·쓰기·첨삭·대화·채우기)에도 `strengths` 로 함께 보낸다.

| 묶음 | 코드 → 화면 |
|---|---|
| 일하는 방식 | `METICULOUS` 꼼꼼함 · `PERSISTENT` 끈기 · `PROACTIVE` 주도성 · `FAST_LEARNER` 빠른 학습 · `RESPONSIBLE` 책임감 · `PROBLEM_SOLVING` 문제 해결 |
| 사람과 일할 때 | `COMMUNICATION` 소통 · `COLLABORATION` 협업 · `PERSUASION` 설득 · `CONSIDERATE` 배려 · `USER_FOCUS` 사용자 관점 · `DOCUMENTING` 기록과 공유 |

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

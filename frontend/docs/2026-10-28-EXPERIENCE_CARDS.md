# 경험 카드 — 활동 묶음 · 문항 유형 · 다듬기 (#474)

계약만 정한 문서다. 서버 구현은 아직 없고, 화면은 이 문서와 Mock 으로 먼저 시작할 수 있다. 실서버 연결은 BE 구현이 머지된 뒤다.

## 무엇이 바뀌나

- 레포 밖 경험(동아리·대외활동 등)을 **활동**으로 묶고, 그 안에 경험 카드(`kind: QUALITATIVE`)를 쌓는다.
- 경험 카드에 **문항 유형** 태그(`themes`, 최대 2개)를 붙인다. 자소서를 쓸 때 문항에 맞는 카드를 고르는 데 쓴다.
- 경험 카드 되묻기는 S 부터 묻고, 사용자의 답을 AI 가 **다듬은 문장으로 미리 보여 준 뒤** 저장한다.
- 기술 카드, 레포 분석, 지금의 되묻기 흐름은 바뀌지 않는다.

## 1. 활동

**Activity**
```jsonc
{ "id": "UUID 문자열",
  "name": "교내 개발 동아리",          // 1~50자, 필수
  "type": "CLUB|EXTERNAL|CONTEST|INTERNSHIP|EDUCATION|PART_TIME|VOLUNTEER|STUDY|OTHER",
  "startMonth": "2025-03",             // YYYY-MM, 필수
  "endMonth": "2025-12" | null,        // null 은 진행 중
  "role": "백엔드 파트장" | null,       // 0~50자, 빈 글은 null
  "cardCount": 3,
  "createdAt": "ISO" }
```

| type | 화면 이름(예) | 예시 |
|---|---|---|
| `CLUB` | 동아리 | 교내 개발 동아리, 학회 |
| `EXTERNAL` | 대외활동 | 서포터즈, 기자단, 연합 동아리 |
| `CONTEST` | 공모전·해커톤 | 해커톤, 아이디어 공모전 |
| `INTERNSHIP` | 인턴 | 기업 인턴, 현장 실습 |
| `EDUCATION` | 교육 과정 | 카카오테크캠퍼스, 부트캠프 |
| `PART_TIME` | 아르바이트 | 카페, 과외, 학원 조교 |
| `VOLUNTEER` | 봉사 | 교육 봉사, 지역 봉사 |
| `STUDY` | 스터디 | 알고리즘 스터디, CS 스터디 |
| `OTHER` | 기타 | 위에 없는 경험 |

| | 요청 | 응답 |
|---|---|---|
| `GET /activities` | | `Activity[]` — 시작 월 최신순 |
| `POST /activities` | `{ name, type, startMonth, endMonth?, role? }`, 헤더 `Idempotency-Key: <UUID v4>` | `Activity`. 같은 사용자·같은 키는 처음 만든 활동을 돌려준다(직접 작성 카드와 같은 규칙) |
| `PATCH /activities/{id}` | 보낸 필드만 바꾼다. 카드 제목·기간 수정과 같이 생략·`null` 은 그대로이고, `endMonth`·`role` 은 빈 글 `""` 이 비우기(진행 중·역할 없음)다. 바꿀 것이 없으면 400 | `Activity` |
| `DELETE /activities/{id}` | | `{ ok: true }`. **안의 카드도 모두 지운다**(카드 `DELETE` 와 같은 완전 삭제). 화면은 지우기 전에 `cardCount` 로 "카드 N장도 함께 지워집니다" 확인을 받는다 |

- 오류: 이름·형식이 틀리거나 `endMonth` 가 `startMonth` 보다 앞서면 400 `INVALID_REQUEST`, 없는 활동·남의 활동은 404.
- 종류는 활동을 만들 때 한 번 고르고, 안의 카드가 모두 따른다. AI 는 종류와 역할을 보고 첫 질문을 고른다.

## 2. 카드에 붙는 활동

- `Card` · `CardSummary` 에 `activity: { id, name, type } | null` 을 더한다.
- 카드는 `repo`, `activity` 중 하나에만 속하거나 둘 다 null(어디에도 묶이지 않은 직접 작성 카드)이다. 둘 다 값이 있는 카드는 없다.
- `POST /cards/manual/draft` 요청에 `activityId: string | null` 을 더한다. `repoId` 와 함께 보내면 400 `INVALID_REQUEST`, 없는 활동·남의 활동은 404 다. 레포·활동 안에서 직접 추가한 카드는 `kind: QUALITATIVE` 다.
- 카드를 다른 활동·레포로 옮기는 기능은 이번 계약에 없다.

## 3. 문항 유형 `themes`

| 값 | 화면 이름(예) | 맞는 자소서 문항 예 |
|---|---|---|
| `COLLABORATION` | 협업·소통 | 팀으로 함께 성과를 낸 경험 |
| `CONFLICT` | 갈등 해결 | 의견 차이를 조율한 경험 |
| `PROBLEM_SOLVING` | 문제 해결 | 어려운 문제를 해결한 경험 |
| `LEADERSHIP` | 리더십 | 주도적으로 이끈 경험 |
| `CHALLENGE` | 도전·목표 달성 | 가장 도전적이었던 경험 |
| `FAILURE` | 실패·극복 | 실패를 딛고 일어난 경험 |
| `GROWTH` | 성장·배움 | 스스로 성장한 경험, 배운 점 |

- `Card` · `CardSummary` 에 `themes: Theme[]` 를 더한다. 최대 2개이고 더 잘 맞는 것부터 오며, 없으면 `[]` 다. 분야 태그(`areas`)와 같은 모양이다.
- 경험 카드(`QUALITATIVE`)에만 값이 있고 기술 카드는 늘 `[]` 다. '지원 동기'·'직무 역량' 문항은 유형에 넣지 않는다(지원 동기는 카드에서 나오지 않고, 직무 역량은 기술 카드가 맡는다).
- **누가 붙이나**: AI 가 되묻기를 스스로 끝낼 때 붙인다. 되묻기 없이 네 칸을 직접 채웠거나 질문 상한에 걸려 끝난 카드는, 확정할 때 비어 있으면 붙인다. AI 가 실패하면 `[]` 로 두고 저장·확정을 막지 않는다.
- **사용자가 바꾸기**: `PATCH /cards/{id}/metadata` 에 `themes?: Theme[]` 를 더한다(0~2개, `[]` 는 비우기, 같은 값 두 번·모르는 값은 400). 사용자가 한 번 바꾼 뒤에는 AI 가 덮어쓰지 않는다. 다른 메타데이터와 같이 작성 중(DRAFT) 카드만 바꿀 수 있다.

## 4. 경험 카드 되묻기

[되묻기 계약](BACKEND_CONTRACT.md#되묻기)을 그대로 쓰고, 아래만 다르다.

- `InterviewTurn.sourceType` 에 `ACTIVITY`(활동 안 카드)를 더한다. 레포 안에서 직접 추가한 경험 카드는 지금처럼 `DIRECT_CARD` 다.
- 빈 경험 카드의 첫 질문은 S 다. 그 뒤는 지금처럼 AI 가 물을 칸을 고르고 스스로 끝낸다.
- `options`(예상 답변)는 지금과 같은 `string[]` 이다. 레포 안 카드는 레포 기록을 읽고 만든 후보가, 활동 카드는 빈칸을 채우는 틀이 온다.
- 질문 상한은 지금처럼 응답의 `maxTurns` 만 쓴다. 경험 카드는 더 크게 둘 예정이다.

**다듬기 미리 보기** — `POST /cards/{id}/interview/{turnNo}/polished-draft`

| 요청 | 응답 |
|---|---|
| `{ text }` — 사용자가 쓴 답 | `{ sentence, polished, unsourcedNumbers: string[] }` |

- **아무것도 저장하지 않는다.** 질문 상한에도 세지 않는다.
- `sentence` 는 AI 가 다듬은 문장이다. AI 는 표현만 다듬고 내 답에 없는 사실을 더하지 않도록 지시받는다.
- `unsourcedNumbers` 는 다듬은 문장에는 있는데 내 답에는 없는 숫자다. 서버 코드가 견줘 찾는다. 화면은 이 숫자를 강조해 사용자가 지우거나 고친 뒤 저장하게 한다.
- AI 가 응답하지 못하면 오류가 아니라 `polished: false` 와 내 답 그대로의 `sentence` 를 돌려준다(수치 미리 보기 `measured-draft` 와 같은 방식). 화면은 "다듬지 못해 내 답을 그대로 보여 줘요" 처럼 알릴 수 있다.
- 경험 카드의 글 질문(`answerMode: TEXT`)에서만 부른다. 오류 순서는 `measured-draft` 와 같다: 404(카드·질문 없음) → 409 `INVALID_STATE`(초안 만드는 중) → 409 `CARD_CONFIRMED` → 409 `INVALID_STATE`(이미 결과가 정해진 질문) → 400 `BAD_FIELD`(경험 카드의 글 질문이 아님) → 400 `INVALID_REQUEST`(빈 글·1,000자 초과·U+0000) → 429 `RATE_LIMITED`.

**다듬은 문장 저장** — 기존 `POST /cards/{id}/interview/{turnNo}/answer`

- `{ kind: 'TEXT', text, source: 'USER_STATED', original? }` — `text` 는 칸에 저장할 문장(다듬은 문장 그대로, 고친 것, 또는 내 답 그대로), `original` 은 다듬기 전 내 답이다.
- `InterviewTurn.answer` 에 `original: string | null` 을 더한다. 지난 질문 목록에서 '내 답 → 다듬은 문장' 을 다시 보여 줄 때 쓴다. 다듬지 않고 저장한 답은 `null` 이다.

## 이번에 정하지 않은 것

- 카드를 다른 활동·레포로 옮기기
- 레포 기록에서 경험 후보를 제안하는 기능(작업 순서 9번) — 따로 정한다.
- 자소서의 지원 동기 문항 처리 — 자소서 계약에서 정한다.

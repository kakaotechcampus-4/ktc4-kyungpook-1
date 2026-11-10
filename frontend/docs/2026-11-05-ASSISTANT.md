# 홈 한 줄 명령 — 한 문장으로 맡기면 서버가 걸음마다 실행한다 (2026-11-05)

홈에서 사용자가 한 문장(예: `토스 백엔드 공고에 맞춰 자소서 써 줘`)을 적으면 서버가 실행(run) 하나를 만들고, 백그라운드에서 AI 가 다음 행동을 고르며 도구를 걸음마다 실행한다(공고 찾기 → 내 카드와 맞춰 보기 → 모자란 조건 채우기 · 카드 빈 칸 질문 · 자소서 쓰기·첨삭 · 레포 정리). 화면은 그 실행을 다시 읽어 대화로 보인다.

계약만 정한 문서다. 서버 구현은 아직 없고, 화면은 이 문서와 Mock 으로 먼저 만들었다. 스위치(`VITE_ASSISTANT_ENABLED`)가 꺼져 있어 서버가 열리기 전까지 사용자에게는 달라지는 것이 없다.

## 흐름

| 화면 | 하는 일 | `state` |
|---|---|---|
| `/ask` 로 바로 들어옴 | 무엇을 적으면 되는지와 예시 세 줄을 보인다. 예시를 누르면 입력 칸에 그 문장이 들어가고(회사 이름을 고칠 수 있게), 보내야 실행을 만든다 | 없음 |
| 홈에서 적고 `/ask?q=…` 로 들어옴 | 그 말로 `POST /assistant/runs` 를 한 번 부르고 `/ask/{id}` 로 바꾼다(새로고침해도 같은 실행을 이어 본다) | `RUNNING` |
| `/ask/{id}` | 도는 동안 입력을 잠그고 `pollAfterMs` 마다 `GET` 을 다시 부른다. 걸음은 하는 중으로 먼저 보이다 결과가 붙는다 | `RUNNING` |
| 물음 | 마지막 줄이 `ASK` 이면 입력을 열고(`CHOICE` 면 선택지 버튼도), 답을 `POST …/messages` 로 보낸다 | `WAITING_USER` → `RUNNING` |
| 끝 | 결과로 가는 버튼(자소서 열기, 정리 보기, 첨삭 보기, 질문에 답하기)이 보인다. 끝난 뒤에 적은 말은 새 실행이다 | `DONE` |

- 응답은 다른 API 와 같은 `{ data, error }` 형식이다. 아래 JSON 은 `data` 만 적는다.
- 도는 동안(`RUNNING`)에는 화면이 말을 보내지 않는다.
- 도구 걸음이 끝날 때마다 화면은 이 실행 밖의 캐시(공고 판단, 자소서 목록, 정리 상태 등)를 낡은 것으로 표시한다. 서버가 바꾼 값을 다른 화면이 새로 읽게 하려는 것이다.

## API

| | 요청 | 응답 |
|---|---|---|
| `POST /assistant/runs` | `{ text }` | `AssistantRun`(`state: RUNNING`, `USER` 줄 하나). 접수하면 바로 돌려주고 일은 백그라운드에서 한다 |
| `GET /assistant/runs/{id}` | | `AssistantRun` — 지금까지 쌓인 줄 전체 |
| `POST /assistant/runs/{id}/messages` | `{ text }` | `AssistantRun` — 사용자의 말을 `USER` 줄로 더하고 이어서 돈다(`RUNNING`). `WAITING_USER` 에서만 |

- `text` 는 1~500자이고 공백뿐이면 400 이다. 화면은 앞뒤 공백을 뗀 글을 500자에서 잘라 보낸다.
- 실행은 만든 사용자만 읽고 말을 더할 수 있다.

```jsonc
// POST /assistant/runs  { "text": "가온소프트 공고에 맞춰 자소서 써 줘" } → 접수 직후
{ "id": "UUID", "state": "RUNNING", "stepsUsed": 0, "stepLimit": 6, "pollAfterMs": 1000, "errorCode": null,
  "items": [{ "seq": 1, "kind": "USER", "text": "가온소프트 공고에 맞춰 자소서 써 줘" }],
  "createdAt": "ISO", "updatedAt": "ISO" }

// GET /assistant/runs/{id} → 공고 찾기와 맞춰 보기를 마치고 문항을 묻는 중
{ "id": "UUID", "state": "WAITING_USER", "stepsUsed": 2, "stepLimit": 6, "pollAfterMs": null, "errorCode": null,
  "items": [
    { "seq": 1, "kind": "USER", "text": "가온소프트 공고에 맞춰 자소서 써 줘" },
    { "seq": 2, "kind": "STEP", "tool": "FIND_POSTINGS", "ok": true, "find": { "company": "가온소프트", "keywords": [] },
      "postings": [{ "postingId": "UUID", "company": "가온소프트", "title": "백엔드 엔지니어 (Java·Spring)", "daysLeft": 2 }], "totalFound": 1 },
    { "seq": 3, "kind": "STEP", "tool": "MATCH_POSTING", "ok": true,
      "posting": { "postingId": "UUID", "company": "가온소프트", "title": "백엔드 엔지니어 (Java·Spring)", "daysLeft": 2, "requiredMet": 2, "requiredTotal": 3 },
      "match": { "requiredMet": 2, "requiredTotal": 3, "met": ["관계형 데이터베이스 설계 경험"], "missing": ["Docker 배포 경험"] } },
    { "seq": 4, "kind": "ASK", "askKind": "QUESTIONS", "text": "자소서를 쓰려면 문항이 필요해요. 지원서에 있는 문항을 그대로 붙여 넣어 주세요." } ],
  "createdAt": "ISO", "updatedAt": "ISO" }

// POST /assistant/runs/{id}/messages  { "text": "지원 동기를 적어 주세요\n가장 어려웠던 경험을 적어 주세요" } → 끝난 뒤의 GET
{ "id": "UUID", "state": "DONE", "stepsUsed": 3, "stepLimit": 6, "pollAfterMs": null, "errorCode": null,
  "items": [
    /* … 앞의 네 줄 … */
    { "seq": 5, "kind": "USER", "text": "지원 동기를 적어 주세요\n가장 어려웠던 경험을 적어 주세요" },
    { "seq": 6, "kind": "STEP", "tool": "START_COVER_LETTER", "ok": true, "questionCount": 2, "coverLetterId": "UUID" },
    { "seq": 7, "kind": "SAY", "text": "자소서를 쓰기 시작했어요. 열어서 초안이 나오는 걸 볼 수 있어요." } ],
  "createdAt": "ISO", "updatedAt": "ISO" }
```

## `AssistantRun`

| 칸 | 뜻 |
|---|---|
| `state` | 아래 표 |
| `stepsUsed` · `stepLimit` | 지금까지 쓴 도구 걸음 수와 한 실행이 쓸 수 있는 상한(6). 화면은 두 값을 쓰지 않는다 |
| `pollAfterMs` | `RUNNING` 일 때만 값이 있다(1000). 화면은 이 간격(최소 500)으로 `GET` 을 다시 부르고, `null` 이면 멈춘다 |
| `errorCode` | `FAILED` 일 때만 값이 있다. 화면은 값을 보이지 않고 "다시 적어 달라"고만 안내한다 |
| `items` | 줄 목록. `seq` 는 1부터 오래된 줄 순서다 |

| `state` | 뜻 | 화면 |
|---|---|---|
| `RUNNING` | 서버가 다음 행동을 고르거나 도구를 실행하는 중 | 입력을 잠그고 "확인하고 있어요" 또는 걸음을 보인다 |
| `WAITING_USER` | 마지막 줄이 `ASK` 다. 사용자의 답을 기다린다 | 입력을 열고 보내면 `POST …/messages` |
| `DONE` | 끝났다. 마지막 줄은 보통 `SAY` 이고, 걸음 상한에 닿았거나 시작 뒤에 끝맺음 말을 받지 못했으면 `STEP` 이다 | 결과 버튼을 보이고, 적으면 새 실행 |
| `FAILED` | 실행을 이어 가지 못했다(AI 가 응답하지 못함 등) | "지금은 답을 받지 못했어요. 할 일을 다시 적어 주세요." |

## 줄(`items`)

| `kind` | 뜻 | 값 |
|---|---|---|
| `USER` | 사용자의 말 | `text` |
| `STEP` | 도구 한 걸음 | `tool`, `ok`, 아래 표 |
| `ASK` | AI 가 사용자에게 묻는 말 | `text`, `askKind`, `options` |
| `SAY` | AI 의 끝맺음 말 | `text` |

- 걸음의 문장("가온소프트 공고 1건을 찾았어요")은 화면이 만든다(`features/assistant/askCopy.ts`). 서버가 보내는 글은 AI 가 쓴 `ASK`·`SAY` 의 `text` 뿐이다. 걸음의 숫자는 서버가 준 값만 쓴다.
- `STEP` 의 `ok` 가 없거나 `null` 이면 하는 중, `true` 면 끝, `false` 면 실패(`error`)다. 같은 걸음은 `seq` 를 바꾸지 않고 `ok` 와 결과 칸만 채워 같은 줄로 온다.
- `askKind` 는 `QUESTIONS`(자소서 문항을 붙여 넣어 달라), `CHOICE`(`options` 중 하나를 고르거나 직접 적는다), `FREE`(자유 답)다. `QUESTIONS` 의 답은 문항을 줄마다 하나씩 담은 글이고, 서버가 줄을 나눠 자소서 문항(1~10개, 하나 500자 이하)으로 쓴다.
- 화면은 모르는 `kind`·`tool` 이나 모양이 다른 줄을 버리고 나머지 대화를 그대로 그린다. 새 종류를 더해도 옛 화면이 깨지지 않는다.

**`STEP` 의 값**

| `tool` | 하는 중에도 오는 값 | 끝난 뒤(`ok: true`)에 더해지는 값 |
|---|---|---|
| `FIND_POSTINGS` | `find: { company, keywords }` — 찾는 조건. `company` 는 `null` 일 수 있고 `keywords` 는 `[]` 일 수 있다 | `postings`(공고 타일, 5건까지), `totalFound`(맞은 공고 수) |
| `MATCH_POSTING` | `posting` — 맞춰 볼 공고 | `posting`(`requiredMet`·`requiredTotal` 이 채워진다), `match: { requiredMet, requiredTotal, met[], missing[] }` |
| `FILL_POSTING_GAPS` | `posting` — 모자란 조건을 채울 공고 | `posting`, `match`(채운 뒤의 값), `gapsFilled`(새로 맞게 된 조건 수) |
| `ASK_CARD_GAP` | 없음 | `cardId`, `cardTitle`, `question: { field: S\|T\|A\|R, text }` — 물을 게 없으면 `question` 이 `null` |
| `POLISH_COVER_LETTER` | 없음 | `letterId`, `polished: [{ seq, points }]`(첨삭한 문항과 포인트 수), `remainingQuestions`(첨삭이 안 된 문항 수) |
| `START_COVER_LETTER` | 없음 | `questionCount`, `coverLetterId` |
| `START_REPO_ANALYSIS` | `repoName` | `repoId`, `jobId`(분석을 시작했을 때만) |

- 공고(`posting`, `postings[]`)는 `postingId`·`company`·`title`·`daysLeft`(마감까지 남은 날, 마감일이 없으면 `null`)와, 이미 맞춰 본 공고에만 `requiredMet`·`requiredTotal` 이 온다. 타일을 누르면 `/match/{postingId}` 로 간다.
- `match.missing` 은 모자란 필수 조건의 이름이다. 화면은 이름만 한 줄로 보인다.
- 실패한 걸음은 `ok: false` 와 `error` 가 온다. 화면이 문장으로 바꾸는 코드는 `NOT_FOUND`(공고나 레포를 찾을 수 없음), `NO_CONFIRMED_CARDS`(확정 카드가 없음), `RATE_LIMITED`, `NOT_MATCHED`(먼저 공고와 맞춰 봐야 함), `GITHUB_DISCONNECTED`(GitHub 연결이 끊김), `CAP_REACHED`(카드 질문을 다 씀), `NOT_READY`(자소서 초안이 아직 끝나지 않음)이고, 그 밖의 코드는 "이 일을 끝내지 못했어요" 로 보인다.
- 결과 버튼은 끝난 실행에서 마지막으로 성공한 시작 걸음을 따른다. `START_COVER_LETTER` 는 `/cover-letters/{coverLetterId}`, `START_REPO_ANALYSIS` 는 `/repos/{repoId}/run?job={jobId}`, `POLISH_COVER_LETTER` 는 `첨삭 보기` 로 `/cover-letters/{letterId}`, 질문을 연 `ASK_CARD_GAP` 은 `질문에 답하기` 로 `/cards/{cardId}/interview` 로 간다.
- 마지막으로 끝난 걸음이 `MATCH_POSTING` 이나 `FILL_POSTING_GAPS` 이면 맞춰 본 공고 카드를 보인다. 채운 뒤에는 `FILL_POSTING_GAPS` 가 준 `match` 의 새 값을 쓴다.

## 오류

- 400 `INVALID_REQUEST` — `text` 가 없거나 공백뿐이거나 500자를 넘음.
- 404 `NOT_FOUND` — 없는 실행, 남의 실행.
- 409 `INVALID_STATE` — `WAITING_USER` 가 아닌 실행에 말을 더함(도는 중이거나 끝남). 화면은 이 경우를 보내지 않으므로 새로고침하면 최신 상태를 읽는다.
- 429 `RATE_LIMITED` — 실행 만들기와 말 더하기를 합쳐 센다. 한도 값은 서버가 정하고, `details.retryAfterSec` 를 주면 화면이 기다릴 시간을 말한다. 보낸 말은 입력 칸에 남는다.

## 스위치와 Mock

- `/ask`, `/ask/:runId` 는 `VITE_ASSISTANT_ENABLED=true` 일 때만 열린다(`assistantSupported()`, 홈의 한 줄 입력도 같은 값을 따른다). 기본은 꺼져 있고 Mock 에서도 꺼져 있다. 서버가 배포된 뒤 `Dockerfile` 에서 켠다. 꺼진 빌드에서는 `/ask` 를 홈으로 돌려보내고 어떤 요청도 보내지 않는다.
- Mock(`src/mock/assistant.ts`)은 첫 말로 대본을 고른다. 일을 고르는 데 1초, 걸음은 하는 중으로 1초 보인 뒤 결과가 붙는다.

| 첫 말 | 대본 |
|---|---|
| `자소서` 가 들어 있다 | `FIND_POSTINGS` → `MATCH_POSTING` → `ASK`(`QUESTIONS`, `WAITING_USER`). 문항을 받으면 실제 Mock 자소서를 만드는 `START_COVER_LETTER` → `SAY` → `DONE` |
| `정리` 나 `레포` 가 들어 있다 | `ASK`(`CHOICE`, 레포 이름 3개, `WAITING_USER`). 이름을 받으면 Mock 분석을 시작하는 `START_REPO_ANALYSIS` → `SAY` → `DONE`. 이름을 못 찾으면 `SAY` 만 하고 `DONE` |
| `첨삭` 이 들어 있다 | `POLISH_COVER_LETTER`(Mock 자소서 첫 번째, 없으면 하나 만든다, 문항 두 개 · 포인트 3개와 2개) → `SAY` → `DONE` |
| `채워` 가 들어 있다 | `FIND_POSTINGS` → `MATCH_POSTING`(모자란 조건 두 개) → `FILL_POSTING_GAPS`(`gapsFilled` 2) → `SAY` → `DONE` |
| `빈 칸` 이나 `질문` 이 들어 있다 | `ASK_CARD_GAP`(확정한 첫 카드, `question` 은 결과 칸 `R`) → `SAY` → `DONE` |
| 그 밖 | `FIND_POSTINGS`(첫 세 낱말 중 두 글자 이상인 것이 공고의 회사·직무·기술에 들어 있는 공고) → `SAY` → `DONE`. 맞는 공고가 없으면 `totalFound: 0` 과 `SAY` |

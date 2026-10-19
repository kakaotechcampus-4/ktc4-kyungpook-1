# 자소서 초안 (#116 에서 분리)

## 범위
#116 의 자소서 초안 화면(설정 `/cover-letter` · 초안 `/cover-letter/:id`)만 #300 공고 조건 매칭 위로 옮겼다. #116 의 인재상 태그 매칭(목록·상세·등급 계산·가상 기업 목)은 #300 이 대체하므로 가져오지 않았다. 서버·AI 구현은 없고, 매칭과 같은 `VITE_MATCH_ENABLED` 로 실서버 모드에서 요청을 보내지 않는다.

## #116 과 달라진 점
- **추천 카드**: 매칭 상세 `conditions[]` 중 필수(REQUIRED)·우대(PREFERRED) 조건의 `supports` 카드를 먼저 고른다. 담당 업무(RESPONSIBILITY)는 '하게 될 일'이라 추천·빈 칸 기준에 넣지 않는다.
- **빈 칸 안내**: `gaps`·`notUsed` 는 인재상 이름이 아니라 필수·우대 **조건 문장**이다. `gaps` = 확정 카드 어디에도 근거가 없는 조건, `notUsed` = 근거 카드는 있지만 이번에 고르지 않은 조건.
- **연결 문장**: 고른 카드가 뒷받침하는 조건이 있을 때만 "이상의 경험은 '…' 요건과 관련이 있습니다." 를 붙인다.
- **매칭 실패와 분리**: 공고 목록 요청이 실패해도(503 `AI_UNAVAILABLE` 등) 일반 자소서는 만들 수 있다. #116 은 화면 전체를 오류로 막았다.
- **진입**: 공고 상세의 "이 공고로 자소서 초안 만들기" 와 사이드바 "자소서 초안". 모바일 하단 메뉴는 이미 5칸이라 늘리지 않았다.
- **코드 위치**: 스키마·API·훅은 `api/coverLetter.ts`, 목은 `mock/coverLetter.ts`(store ↔ matching 순환 import 를 피하려고 분리). 404 코드는 #207 과 같은 `NOT_FOUND` 로 맞췄다.
- **목 복원 버그 수정**: #116 의 `restore()` 는 같은 줄 주석이 `db.seq = …` 를 덮어 데모 새로고침 뒤 초안 id 가 겹쳤다. 시험(`데모를 새로고침해도 … id 순번이 이어진다`)으로 막았다.

## 제안 계약 (서버 미구현)
| | 요청 | 응답 `data` |
|---|---|---|
| `GET /cover-letters` | | `CoverLetterSummary[]` 최근 수정 순, 본문 없음 |
| `POST /cover-letters` | `{ matchId \| null, question, cardIds[], charLimit \| null }` | `CoverLetter` |
| `GET /cover-letters/{id}` | | `CoverLetter` |
| `PATCH /cover-letters/{id}` | `{ text }` (≤10,000자) | `{ id, text, edited, updatedAt }` |

- `question` = `MOTIVATION` · `COLLABORATION` · `PROBLEM_SOLVING` · `GROWTH`. `charLimit` = 공백 포함 100~5000 정수, 없으면 `null`. 제한은 저장만 하고 본문을 자르지 않는다.
- `CoverLetter` = 요약(`id, matchId, company, role, question, edited, charLimit, createdAt, updatedAt`) + `paragraphs[]{ kind: EVIDENCE|CONNECTIVE|SLOT, text, cardId, cardTitle }` · `text` · `cardIds[]` · `gaps[]` · `notUsed[]` · `stale`.
- **확정 카드 문장만 쓴다.** `EVIDENCE` 는 `cardId`·`cardTitle` 이 반드시 있고, 카드의 가리기 규칙을 서버가 적용한다. `CONNECTIVE` 는 문항·공고에서 아는 사실만 잇고 새 경험·수치를 만들지 않는다.
- **지원 동기는 지어내지 않는다.** `MOTIVATION` 이면 첫 연결 문장 뒤에 `SLOT` 문단(`text` = `[지원 동기를 직접 적어 주세요]`)을 하나 넣는다.
- **초안은 만든 시점의 스냅샷이다.** 쓴 카드가 그 뒤 확정 해제·내용 수정·가리기 변경되면 `stale: true`. 서버는 생성 시점의 가리기 규칙을 보관해 비교한다.
- 오류: 400 `BAD_QUESTION`·`BAD_LIMIT`·`BAD_TEXT` · 404 `NOT_FOUND`(없는 공고·초안) · 422 `NO_EVIDENCE`(카드 미선택)·`CARD_NOT_CONFIRMED`.

## 검증
- 단위: 목 규칙 16개(`cover-letter-mock.test.ts`), 화면 24개(`cover-letter-pages.test.tsx`). 전체 318개 통과, 타입 검사·운영 빌드 통과.
- 정적 빌드 브라우저: 자소서 6개(공고 상세 → 초안 생성 → 고치고 새로고침, 글자 수·지원 동기 칸, 320/390/768/1440px 가로 넘침). 전체 116개 통과.
- 실제 Spring/AI 연동 검사는 아니다(서버 구현 없음).

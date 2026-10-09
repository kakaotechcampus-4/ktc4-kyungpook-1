FE·BE·AI 세 파트가 같은 사례를 보고 있는지 테스트로 고정해 두는 자리. 멘토 3주차 리뷰(#57) 제안.

각 파일은 "이 요청이 오면 이렇게 응답한다"는 대표 사례 하나를 담는다. 파트마다 이 파일을 읽어서 자기 쪽 테스트에 쓴다 — 세 파트가 한 번에 실행되는 통합 테스트는 아니고, 각자 "같은 약속을 보고 있는지"만 지금은 확인한다.

- `interview/direct-card.json` — 후보 없이 직접 작성한 카드의 되묻기 사례.
  - FE: 목 응답의 `sourceType`이 파일 값과 같은지 (`frontend/src/test/`)
  - AI: `springToAi.expectedRequest`를 라우터가 받아들이는지 (`ai/tests/`)
  - BE: `POST /api/cards/{id}/interview` 엔드포인트가 생기면, `givenCard`를 DB에 넣고 그 요청으로 실제로 이 값을 AI에 보내는지 (엔드포인트 미구현 — 아직 테스트 없음)

- `ingest/repository-activity.json` — Spring이 AI의 `/internal/collect`를 호출하고
  AI가 A의 후보 그룹화에 필요한 GitHub 활동을 정규화해 돌려주는 대표 사례.
  - BE: 선택 저장소·사용자 문맥을 요청하고 확정된 MVP 필드만 DB에 저장
  - AI: 요청 범위에서만 OAuth 토큰을 사용해 commit/PR/review/comment/issue를 수집
  - A: commit 메시지·변경 파일·PR 연결·리뷰 상태·본문 excerpt로 후보를 그룹화
  - 재수집: Spring은 DB에 저장된 `known_commit_shas`를 보내고, AI는 commit
    목록을 다시 확인하되 저장된 SHA의 상세 조회를 생략한다.
  - AI는 현재 기본 브랜치의 `head_sha`를 응답하고 Spring이 이를 저장한다.
  - OAuth 토큰 값은 JSON 계약·응답·로그에 포함하지 않는다.

- `ingest/candidate-details.json` — A가 선택한 후보의 SHA·PR만
  `/internal/collect/candidate-details`로 다시 조회하는 사례.
  - AI GitHub adapter가 선택된 commit의 제한된 patch만 반환한다.
  - 바이너리·GitHub 미제공 patch는 `null`로 두고, 상한으로 잘리면
    `patch_truncated: true`로 표시한다.
  - 이 응답은 A의 diff 해석용이며 원본 patch를 DB에 영속 저장하지 않는다.

이 JSON은 **AI → Spring 전송 계약**이며 모든 필드를 그대로 영속 저장하라는 DB
스키마는 아니다. 초기 수집에서는 전체 본문과 diff patch를 보내지 않고 제한된
`body_excerpt`만 전달한다. A가 만든 STAR 상태·근거 연결·부족 항목을 Spring이
저장한 뒤, Spring이 B 계약에 맞게 `missing_slots`와 필요한 근거 문맥만 조립한다.
이후 화면 표시나 재사용 요구가 생기면 새 마이그레이션으로 ERD를 확장한다.

이 파일을 바꾸면 세 파트 테스트가 동시에 깨질 수 있다. 바꾸기 전에 팀 채널에 알린다.

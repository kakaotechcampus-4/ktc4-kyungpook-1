# #183 카드 API 계약 반영

## 기준과 범위

최신 develop 333c109과 이슈 #183, 병합된 #162/#163/#175/#172의 실제 Java controller/service를 기준으로 한다. API/Mock/계약 문서와 직접 작성·편집·마스킹 화면을 맞춘다. 미병합 기업 매칭 PR #116이나 백엔드 구현을 이번 작업에 추가하지 않는다. 기존 저장 직렬화·StrictMode·실패/이탈·취소 복원·확정 보호와 UI 디자인은 유지한다.

## 직접 작성

NewCardPage 진입마다 UUID v4 하나를 생성하고 같은 화면의 실패 재요청에 그대로 사용한다. endpoints.createManualDraft(input, idempotencyKey), useCreateManualDraft의 변수는 `{ input, idempotencyKey }`로 명시한다. 성공 응답의 title/period/repo가 기준이며 미구현 metadata PATCH로 재요청 내용을 덮어쓰지 않는다. 로컬 STAR 입력은 유지하고, 사용자가 건드리지 않은 칸은 재접속 응답의 현재 글을 보존한다. 서버가 확정 카드를 돌려주면 확정본을 PATCH하지 않도록 보호한다.

## 저장·캐시

단일 CONFIG에 칸 1000자·제목 256자·기간 50자·마스킹 규칙 20개/각100자 상한을 둔다. DraftFields는 네 키가 모두 필요하고 비운/공백-only 칸은 null, 의미 있는 글은 앞뒤 공백/줄바꿈 그대로 보낸다. JS 공백과 Java CardText의 추가 공백 U+001C–001F를 함께 처리한다. U+0000과 상한 위반을 차단한다.

성공 ACK의 cardId를 검증하고 캐시에 실제 저장 글/버전 번호/버전 시각을 먼저 반영한다. 고친 칸의 오래된 근거·확인 필요 표시는 유지하지 않는다. ACK에는 버전 출처·목록 상태·카드 수정 시각이 없으므로 추정하지 않으며 client-only metadata-pending 표시와 일반 문구를 사용한다. 전체 GET으로 출처/복원 근거를 확정하고 더 오래된 ACK는 무시한다. 직접 작성의 canonical 입력은 선택형 acknowledgedSnapshot으로 저장 queue의 기준과 맞춘다. 서버의 최신 수정순 목록을 사용하고 status 쿼리는 보내지 않는다.

## Mock와 버전

manual draft 키는 사용자별로 격리하고 snapshot/hydrate에서 보존한다. 같은 키는 처음 카드의 현재 응답을 반환한다. PATCH는 네 키/1000자/null 규칙과 오류 우선순위를 따르고 동일 본문은 no-op다. 미확정 USER_EDIT만 덮어쓴다. AI_DRAFT/INTERVIEW/MASK/RESTORE/확정 후 다시 연 버전 위의 첫 변경은 USER_EDIT 새 버전이다. 직전 분기 버전과 글이 같으면 근거/확신도/턴을 복원하고 다르면 USER_STATED로 처리한다. 변경 시 updatedAt으로 정렬한다.

미사용 POST /cards와 POST /cards/{id}/versions 및 훅/Mock 정의는 제거한다. 실서버 regenerate는 capability로 화면과 endpoint 양쪽에서 차단하고 Mock 체험에만 남긴다. #175의 mask 20/100 상한, 확정 카드 409, 같은 규칙/현재 버전 복원 no-op도 맞춘다.

## 검증·출시

API/Mock 규칙·재요청 메타데이터·입력/취소·근거/버전 회귀를 실패 재현 후 구현한다. 실제 HTTP 모드 브라우저 검사에서 헤더/네 키/null/1000자/미지원 버튼을 검증한다. 전체 타입·단위·개발/정적 브라우저와 두 테마/모바일을 확인한다. docs/README·새 팀 PR(Closes #183)/리뷰어·개인 동기화·Vercel 재배포를 마무리한다. 환경 파일·API 키는 커밋하지 않는다.

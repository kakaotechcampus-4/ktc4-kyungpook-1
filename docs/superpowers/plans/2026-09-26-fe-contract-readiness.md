# FE 계약 정합성·후보 보드 준비

**목표:** 확정 오류 계약을 적용하고 #57의 FE 영향을 검증한다. 후보 보드는 Mock을 유지하며 랭킹 완료 전 실제 연동을 완료했다고 표시하지 않는다.
**구조:** API 스키마·요청 계층은 유지하고 Job 재시도 판단을 공통 함수/훅으로 분리한다. 후보 변경은 성공 응답 이후에만 UI에 반영한다.
**환경:** React 19, TanStack Query, Vite, Vitest, Playwright. 팀 기준 Node 22.

- [x] 오류 계약: 실제 Mock 충돌 코드, 409 무자동재시도, GITHUB_RATE_LIMITED 문서·테스트.
- [x] Job 재시도: FAILED/부분 완료에서 retryable === true, 시간 제한, 최초 렌더 및 Job 교체 회귀 검사.
- [x] 공유 계약: contracts/interview/direct-card.json을 FE 테스트가 직접 읽고 실제 Mock 응답과 대조. 계약 파일 변경 시 FE CI 실행.
- [x] 후보 화면: 실패 시 선택 보존, 중복 제출/변경 방지, 제외 성공 후 실행 취소, 검색 초기화·모바일 동작. 랭킹 순서는 서버 배열 그대로 유지.
- [x] #57 리뷰: Job DTO/DB 변경, MeView 미확정, #55/#58 내부 AI 계약과 FE 경계, #60 중복 범위 기록.
- [x] Node 22 빌드·단위·정적 E2E·화면 검증. README/트러블슈팅 갱신. 70개 단위·35개 E2E·최종 반응형 12개 통과, 별도 읽기 전용 리뷰에서 중요 회귀 없음.
- [x] develop 대상 PR #61 생성. TaeHuiKKIM 담당자 / ganggang-0605, taehun0208, Grow22 리뷰어 지정 확인. 개인 PR #1 동기화 및 Vercel Ready 확인.

## 완료 기록
- 팀 구현 커밋 c2055af, 개인 배포 커밋 4c21e4b.
- #57에 FE 영향 검토 COMMENTED 리뷰 등록.
- 필수 Node 22 CI 통과: https://github.com/kakaotechcampus-4/ktc4-kyungpook-1/actions/runs/36225907648
- 운영: https://gitory-prototypes.vercel.app
- 아티팩트: https://gitory-prototypes-b30ka17ij-kim-tae-huis-projects.vercel.app
- Node 24는 기존 비차단 AbortSignal 실패. 개인 Actions는 계정 결제/사용 한도 차단이며 로컬 검증·Vercel 빌드는 통과.

## 경계
기존 #60의 저장/취소 변경은 가져오지 않는다. 실제 후보 랭킹·수집, MeView 응답 분리, B-2 Spring 엔드포인트는 담당 파트 구현·계약 확정 후 연결한다. 환경변수/키/세션은 커밋하지 않는다.

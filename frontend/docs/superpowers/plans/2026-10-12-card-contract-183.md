# Card Contract 183 Implementation Plan

> 실행: subagent-driven-development로 API/Mock 파일 소유권을 분리하고, UI 연결은 부모가 구현한다. 각 구현 후 읽기 리뷰, 마지막 전체 리뷰와 실제 HTTP 검증을 수행한다.

**Goal:** 병합된 카드 API 계약으로 실제 서버 요청이 성공하고 Mock도 같은 경계/버전 규칙을 따르게 한다.

**Architecture:** api 스키마/endpoint와 CONFIG는 공유 계약, Mock store는 서버 상태 모델, UI는 훅과 저장 queue를 소비한다. unsupported 경로는 제거/기능 차단하며 서버 응답과 ACK를 기준으로 입력과 캐시를 맞춘다.

**Tech Stack:** 기존 React 19/TypeScript/Vite/TanStack Query/zod/Vitest/Playwright. 새 의존성 없음.

## Global Constraints
- 기준 develop 333c109, branch codex/fe-card-contract-183. Backend/미병합116 변경 금지.
- 칸1000/title256/period50/mask20개·각100자. 네 STAR 키 필수, blank-only=null, 원문 보존.
- UUID는 진입당 하나, 실패 재전송에 같은 키. 서버 메타데이터를 replay 뒤 자동 덮어쓰기 금지.
- 기존 직렬 저장/취소/이탈/StrictMode/확정/근거 원칙 유지. 환경 파일·키·배포 설정 제외.

## Task 1 — API foundation (전담)
- [x] api/{schemas,endpoints,queries,capabilities}.ts, lib/{config,useDraftAutosave,cacheSavedDraft}.ts와 독립 계약 테스트를 담당한다.
- [x] createManualDraft(input,key), 훅 `{input,idempotencyKey}`; 4키/1000자/blank/null/NUL 검증과 mask constants, 미사용 createManualCard/saveVersion 훅/endpoint 제거, real regenerate guard.
- [x] cache ACK 검증/글/버전/버전 시각과 stale evidence 방지, 재조회 실패에도 성공 글 유지. ACK로 출처/목록 상태/카드 시각은 추정하지 않는다. 테스트 RED/GREEN·diff·읽기 리뷰.

## Task 2 — Mock/계약 (별도 전담)
- [x] mock/{router,store}.ts·독립 Mock 회귀·BACKEND_CONTRACT.md 소유. Task1 파일 수정 금지.
- [x] 사용자별 durable manual-key replay, 잘못된 repo404, missing key400/confirmed409, 4키/null/max1000, 동일본문 no-op, USER_EDIT 및 frozen/version별 분기·근거 복원/제거·updatedAt.
- [x] POST /cards·POST versions 제거, mask20/100·confirmed409·same rules/current restore no-op·version summary 계약. RED/GREEN·읽기 리뷰.

## Task 3 — UI 연결 (부모)
- [x] NewCardPage의 entry UUID·canonical replay·dirty STAR 보존·확정 replay 보호. 필요하면 작은 순수 helper로 분리한다.
- [x] CardModes 입력1000/mask20/100, CardPage/StarBlock regenerate real hide. UI 실패/StrictMode/취소 테스트 갱신.
- [x] real-api Playwright 모드와 실제 HTTP shape 테스트를 추가한다. 기존 Mock e2e와 구분한다.

## Task 4 — 검증/출시 (부모)
- [x] 모든 관련 회귀·타입·전체 unit/dev/static/real-api·두 테마/모바일, 최종 리뷰 지적 해결.
- [x] docs 작업/트러블슈팅·README 최신화와 개인 저장소 동기화·출시 준비.
- 팀 PR의 Closes #183·리뷰어, 개인 PR 동기화, Vercel 배포·운영 확인과 각 CI의 실제 결과는 PR 본문에 실행 링크로 기록한다.

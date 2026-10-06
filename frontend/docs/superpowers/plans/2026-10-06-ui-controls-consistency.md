# UI Controls Consistency Implementation Plan

> **Execution:** 현재 세션에서 subagent-driven-development로 구현·검토를 분리한다. 출시와 최종 검증은 부모가 순서대로 수행한다.

**Goal:** 검색과 선택 메뉴를 실제로 편하게 조작하고 PC/모바일의 글자·경계·정렬이 일관되게 보이도록 한다.

**Architecture:** 공통 SearchField/Select/StarRow/StarEditorField와 기존 PageTitle을 사용한다. tokens/ui.css가 조작·타입 규칙을 담당하고 responsive.css가 화면별 배치를 담당한다. 충돌하는 이전 규칙은 원래 파일에서 정리한다.

**Tech Stack:** React 19, TypeScript, Vite, TanStack Query, Radix Select, Vitest, Playwright.

## Global Constraints
- Control radius 10px, minimum height 44px; body 400, control 500, heading 600; tracking -0.03em.
- 기존 그린/다크와 API enum·서버 저장·Job·선택/확정/이탈 보호를 유지한다.
- 환경 파일·API 키는 커밋/푸시하지 않는다.
- 최신 develop 8125917의 연결 worktree와 새 codex/ui-controls-consistency branch를 사용한다.

## Task 1 — 재현·검색·선택 메뉴
- [x] e2e/ui-controls.spec.ts에서 겹 focus·메뉴·키보드·지우기 문제를 실패시킨다.
- [x] components/ui/SearchField.tsx·Select.tsx를 구현하고 Toolbar·CardsListPage·ReposPage·NewCardPage에 연결한다.
- [x] 공통 control tokens/ui.css를 적용하고 chip/select/search 중복 override를 제거한다.
- [x] 기존 필터 검사를 사용자 메뉴 조작으로 갱신하고 실제 빈 레포 선택·disabled도 검증한다.

## Task 2 — 홈·STAR·타입·스크롤
- [x] PC 홈 경계·제목 굵기·STAR 입력 시작선 검사를 실패시킨다.
- [x] StarRow/StarEditorField로 NewCardPage·CardModes·CardPage·InterviewPage의 행 구조를 공유한다.
- [x] Home 정렬과 레포 자동완성의 키보드/ARIA를 개선한다. PageTitle을 목록 화면에서 재사용한다.
- [x] Pretendard tracking/weight·모바일 입력 크기와 얇은 scrollbar를 적용한다.

## Task 3 — 검증·출시
- [x] Node 22/24 단위·타입·정적 E2E와 두 테마 화면을 검증하고 코드 리뷰 지적을 해결한다.
- [x] docs/TROUBLESHOOTING.md·README·작업/검증 기록을 갱신한다.
- [ ] 팀 새 PR과 개인 기존 PR을 양식대로 올리고 리뷰어를 지정한다. 환경 파일 제외 및 두 저장소 파일 일치를 확인한다.
- [ ] Vercel production 재배포 후 운영에서 검색·메뉴·입력·반응형 동작을 확인한다.

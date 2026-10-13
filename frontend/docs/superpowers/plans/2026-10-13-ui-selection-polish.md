# UI Selection Polish Implementation Plan

> 실행: subagent-driven-development. 공통 체크와 화면 검토를 분리하고 부모는 후보 화면을 구현한다.

**Goal:** 선택 조작과 콘텐츠 정렬을 모바일/PC에서 일관되게 개선한다.
**Architecture:** 공통 Check의 접근성과 표시를 공유하고 후보/커밋은 명시적 grid로 배치한다. 기존 데이터 훅과 API는 유지한다.
**Tech Stack:** 기존 React/TypeScript/CSS/Lucide/Vitest/Playwright, 새 의존성 없음.

## Global Constraints
- 기준441b7fa. 44px 조작/20px 체크 표시. 기존 색상·글자·API·선택·저장 규칙 유지.
- 다른 화면은 실제 관찰된 UI 문제만 수정. 숨김/글자 잘림으로 넘침을 감추지 않는다.
- env/키/배포 설정 업로드 금지. 문서·README·PR·개인동기화·Vercel 확인.

## Task 1 — 공통 Check
- [x] ui/index.tsx·ui.css와 단위 검사 소유 분리. 44px 조작,20px SVG 표시,키보드·disabled·전파·포커스·강제 색상. 브라우저 크기 RED를 확인 후 구현한다.
## Task 2 — 후보/커밋 정렬
- [x] 후보·커밋 헤더/메타/행동/상세 배치,44px 행동,모바일 긴 글·검색 폭. 회귀 검사 RED/GREEN, 기존 선택·제외·하위 클릭 유지.
## Task 3 — 전체 화면 검토
- [x] 12개 화면×3폭 검토와 공통 문제 개선, 두 테마 검토. 변경 근거를 작업 문서에 남긴다.
## Task 4 — 검증/출시
- [x] 타입·단위·개발/정적/HTTP 회귀, 최종 코드 리뷰 및 docs/README 최신화.
- PR·리뷰어·개인 동기화·배포/운영·CI 결과는 PR 본문에 기록한다.

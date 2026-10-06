# Sidebar Text Contrast Implementation Plan

> 실행: 기존 연결 worktree에서 inline으로 수행한다. 사용자가 요청한 먹색/짙은 색 범위로 진행한다.

**Goal:** 왼쪽 메뉴와 최근 카드 글자를 더 선명하게 표시한다.

**Architecture:** `tokens.css`의 `--sidebar-text`를 `shell.css`와 `layout-tio.css`의 메뉴/그룹/최근 카드에 연결한다. 라이트는 `var(--ink-900)`, 다크는 `var(--text-primary)`이며 배치·굵기·모바일 메뉴·API는 유지한다.

**Tech Stack:** 기존 CSS semantic tokens, Vite, Playwright.

- [x] `tokens.css`에 라이트/다크 `--sidebar-text`를 정의하고 두 sidebar CSS의 색 참조를 교체한다.
- [x] 문서/README를 갱신하고 실제 브라우저에서 글자색·hover·선택·접힘·다크·모바일을 확인한다. 기존 theme-contrast/responsive 검사를 실행한다. 낮은 영향의 색 변경이므로 구현을 그대로 따라 하는 새 테스트는 추가하지 않는다.
- [x] 검증 후 팀/개인 파일 내용을 맞춰 커밋하고 기존 PR을 갱신한다. `.env`·배포 설정·토큰은 커밋하지 않는다.
- [x] Vercel production build/deploy 후 운영에서 같은 sidebar 색·대비를 확인한다.

타입/빌드·기존 정적 검사 6개와 운영 두 테마의 실제 색·대비·hover·선택·접힘·모바일 전환을 확인했다. Ready 배포는 [운영 후속 기록](../../2026-10-06-UI_CONSISTENCY.md)에 연결했다.

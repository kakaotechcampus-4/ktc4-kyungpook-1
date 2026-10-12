# Rail Scrollbar Implementation Plan

> 실행: 원인 재현과 구현을 순서대로 수행하고 별도 읽기 리뷰를 받는다. 기존 연결 worktree와 codex/sidebar-scrollbar branch를 사용한다.

**Goal:** 접힌 메뉴의 가로 넘침을 없애고 세로 스크롤과 대기 단계 표시를 읽기 쉽게 한다.

**Architecture:** responsive.css가 축소 폭/스크롤 방향을 담당하고 layout-tio.css가 조작 영역과 단계 모양을 담당한다. tokens.css와 base.css가 두 테마의 스크롤바 표현을 공유한다. DOM/API/임시 저장 로직은 이번 수정에서 변경하지 않는다.

- [x] 기존 구현에서 e2e/sidebar-scroll.spec.ts 6개 실패를 확인한다. 가로 넘침 4개와 단계 경계 대비 2개가 실패했다.
- [x] responsive.css의 sidebar overflow-x:hidden과 데스크톱 rail padding-inline:4px, layout-tio.css의 rail 메뉴 최소 44px 조작 영역을 적용한다. 단순히 넘친 버튼을 잘라 숨기지 않는다.
- [x] base.css의 Chromium/WebKit 표현은 feature gate로 표준 scrollbar 속성과 분리한다. 8px 트랙/6px 손잡이·투명 트랙·둥근 thumb·화살표 제거·hover와 고대비 fallback을 적용한다. 단계 토큰과 1px border로 대기 표시를 개선한다.
- [x] 신규 검사·전체 타입/단위/정적 빌드·두 테마 화면·짧은 창을 확인하고 리뷰 지적을 해결한다. docs/TROUBLESHOOTING.md·README를 갱신한다.
- [x] 새 팀 PR 양식·담당/리뷰어 지정, 개인 #1 동기화, Vercel 배포와 운영 확인을 완료한다. 환경 파일·키·배포 설정은 푸시하지 않는다.

팀 #115와 개인 #1, Ready production 배포를 [검증 기록](../../2026-10-07-SCROLLBAR_STEP_CONTRAST.md)에 연결했다. 최종 단위 117개·정적 E2E 84개, 운영 15개 화면·휠/키보드 이동·고대비를 확인했다.

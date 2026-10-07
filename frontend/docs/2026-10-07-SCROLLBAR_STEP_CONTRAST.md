# 접힌 메뉴 스크롤·대기 단계 개선

## 범위와 기준

최신 develop abca4d2에서 codex/sidebar-scrollbar를 시작했다. 이전 팀 #106은 병합됐으며 새 팀 PR을 만든다. 개인 #1은 최신 프론트와 동기화한다. 변경은 CSS와 브라우저 회귀 검사이며 새 라이브러리·API/DB 변경은 없다. 최신 develop의 편집 취소·마스킹 저장 보호와 테스트는 유지한다.

## 재현과 수정

- 접힌 sidebar의 64px 폭에서 padding 24px/경계를 뺀 안쪽은 39px, 펼치기 버튼은 44px였다. scrollWidth 66px/clientWidth 63px로 가로 스크롤이 생겼다. 데스크톱 접힘 padding을 4px로 바꾸고 가로 넘침을 없앴다. 세로 스크롤과 메뉴의 44px 조작 영역을 유지한다.
- rail의 키보드 outline은 왼쪽 -1px까지 넘어가 잘렸다. 안쪽 outline으로 바꾸고 실제 외곽 경계를 검사한다.
- 768–820px의 기존 row 규칙 때문에 tablet scrollWidth 306px/clientWidth 76px였다. 최종 tablet 계층에서 sidebar와 menu-group의 column 방향을 명시한다.
- 대기 단계의 배경 경계 대비는 라이트 1.03:1·다크 1.20:1였다. 진한 표면/숫자와 1px 경계로 표시하며 현재/완료 표현을 구분한다. 완료 체크는 추가 테두리를 투명으로 유지한다.
- 스크롤바는 8px 조작 트랙 안에 6px 둥근 손잡이, 투명 트랙/코너, hover 강조를 사용한다. Chromium/WebKit의 화살표를 제거하고 Firefox/high-contrast는 native 동작을 유지한다.

표준 non-auto 속성과 WebKit 부품 스타일의 우선순위는 [MDN](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Selectors/::-webkit-scrollbar), 표시 방식과 gutter는 [Chrome 공식 문서](https://developer.chrome.com/docs/css-ui/scrollbar-styling)를 참고했다.

## 검증

- 원래 코드에서 신규 6개 모두 실패를 확인했다. 추가 리뷰의 tablet/포커스 문제도 실패하는 검사로 재현했다.
- 최종 개발 신규 8개 통과: 라이트/다크의 가로 넘침·44px 조작·포커스 외곽, tablet 768/800/820/1024px 전 메뉴·실제 이동, 280px 짧은 창의 휠/키보드, 대기 숫자/경계 대비.
- 최신 develop을 포함한 단위 117개·타입/프로덕션 빌드·전체 정적 E2E 84개 통과.
- 두 테마의 1440×960/280·768×600·390×844 레포 화면·rail·단계 확인 모달 및 고대비 등 15개 화면을 확인했다. 가로 넘침·콘솔/실행 오류 0건.
- 읽기 리뷰의 tablet 방향·완료 단계 경계·rail 포커스 지적을 수정하고 재검토에서 Critical/Important 없음 확인.

## 적용 위치

`tokens.css`: 단계/스크롤 의미 토큰. `base.css`: 브라우저별 scrollbar 표현. `layout-tio.css`: rail 조작/포커스·단계 모양. `responsive.css`: 데스크톱/태블릿 배치와 스크롤 방향. `e2e/sidebar-scroll.spec.ts`: 실제 사용 경계와 탐색 검사. 수정 전후 메모에는 환경 파일·API 키·OAuth 토큰을 넣지 않는다.

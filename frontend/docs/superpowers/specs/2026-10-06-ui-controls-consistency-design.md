# UI 조작·정렬 일관성 개선

## 요청과 적용 기준
검색 겹테두리, 필터의 과한 pill 모양, OS 기본 선택 메뉴, PC 홈 좌우 정렬, STAR 입력 들여쓰기, 제목 두께와 스크롤바를 전체 화면에서 개선한다. 기존 그린/다크 테마와 API·서버 저장·Job·선택 보존 규약은 유지한다. 사용자가 구체적으로 지정한 범위와 이전의 확인 질문 없이 진행 요청에 따라 구현한다.

## 확인한 원인
- 전역 focus-visible의 box-shadow와 뒤 파일의 outline, 검색 컨테이너의 outline이 겹친다.
- layout-tio.css가 공통 chip을 radius 999px로 덮어쓴다. 정렬/필터는 native select라 펼친 OS 메뉴를 일관되게 스타일링하지 못한다.
- 반환 사용자 홈 prompt의 16px 내부 여백과 620px 최대 폭이 이어쓰기 섹션의 정렬선과 다르다.
- STAR 행이 아이콘+본문 flex 구조라 textarea/문장도 아이콘 너비만큼 들여쓴다.
- PageTitle과 목록 h1이 서로 다른 weight/size를 선언한다. Pretendard 1.3.9 dynamic subset은 이미 로드한다.

## 선택한 설계
CSS 값만 조정하면 OS 메뉴와 검색 키보드 동작은 남는다. 선택 메뉴를 직접 구현하면 포커스·키보드·화면 충돌 처리를 중복하게 된다. 따라서 Radix Select를 얇은 공통 컴포넌트로 감싸고 그린 테마에 맞게 스타일링한다. 필요한 화면에서만 직접 import하여 첫 화면에 불필요하게 포함하지 않는다.

- 공통 control radius 10px, 최소 조작 높이 44px, control weight 500, heading weight 600, body 400. Pretendard의 letter-spacing은 -0.03em이며 코드·SHA·단일 STAR 글자는 추적 간격을 분리한다.
- SearchField는 하나의 focus 경계와 검색 지우기/Escape 지우기·입력 포커스 복원을 가진다. Toolbar와 카드/저장소 목록이 같은 컴포넌트를 쓴다.
- Select는 동일한 trigger/menu·선택 표시·키보드/타이핑 탐색·Escape·외부 클릭·화면 가장자리 처리를 제공한다. 기존 선택값과 빈 레포 값은 API에 그대로 전달한다.
- Home의 새 경험 영역은 이어쓰기와 같은 좌우 폭을 사용한다. 레포 자동완성은 키보드와 접근성 상태를 정확히 전달한다.
- StarRow/StarEditorField로 제목과 본문을 분리한다. 본문/textarea/evidence의 왼쪽 시작선은 STAR 글자와 같고, 글자 수는 우측에 둔다. 편집 focus/ref·길이 제한·원문·저장 callback은 유지한다.
- PageTitle을 공유하고 모바일에서도 긴 레이블과 입력이 가로로 넘치지 않게 한다. 스크롤은 유지하고 app/textarea에 테마별 얇은 scrollbar를 적용한다.

## 검증과 범위 제한
검색·선택 메뉴·PC 정렬·STAR 시작선의 기존 실패를 실제 브라우저 검사로 먼저 확인한다. 새 라이브러리의 실제 조작과 빈 값 roundtrip도 검사한다. Node 22/24 단위·타입·프로덕션 빌드·정적 E2E 및 320/390/768/1440/1920px의 두 테마를 확인한다. API enum·멱등키·서버 임시 저장·Job 복구/재시도·확정 보호는 변경하지 않는다. 환경 파일·API 키를 커밋하지 않는다.

# 용어집과 문구 규칙

기준은 develop `65a3fe5d`(2026-10-29)다. 경로는 `frontend/src` 기준이고 `ai/`, `backend/`로 시작하는 것만 레포 루트 기준이다. 줄 번호는 이 시점의 것이라 다른 PR이 머지되면 밀린다. 고칠 때는 줄 번호 대신 문구로 검색하고, 남은 수는 7절의 명령으로 다시 센다.

#520 화면 점검에서 나온 용어 통일표를 develop 코드로 다시 확인해 한 곳에 모았다. 그동안 용어 정리가 PR마다 따로 이뤄져(예: #461) 같은 정리가 거듭 필요했다. 새 문구를 쓰기 전에 이 문서를 먼저 본다.

정한 것은 1절부터 4절이다. 아직 정하지 못한 것은 6절에 따로 모았다. 6절 항목은 새 문구에 쓰지 말고 팀이 정한 뒤 1절 표에 올린다. 이 문서는 문구 기준만 정하고 화면은 바꾸지 않는다. 5절의 어긋난 곳은 각 PR에서 정리한다.

## 0. 적용 범위

- 대상은 화면에 나가는 글이다. 버튼, 제목, 안내, 오류, 문서 title, 복사와 내보내기 글이 모두 들어간다. 서버, AI, 목이 만들어 화면에 그대로 나가는 글도 같다(2.2).
- API 필드, enum, 코드 식별자는 영어 그대로 둔다(`ANALYZE`, `DRAFT`, `StarField`의 `S`). 화면에 보일 때만 한국어 이름을 쓴다.
- 사용자의 카드 본문(상황, 과제, 행동, 결과 칸의 문장)은 자소서 재료라 "-다" 평서체다. AI 프롬프트가 그렇게 정했고(`ai/services/star_generator.py:82` "모든 칸은 '~다' 평서체") 목 데이터도 같다(`mock/fixtures.ts:214` "인증 모듈의 세션 처리를 담당했다"). 2절의 해요체는 카드 본문에 적용하지 않는다.
- 코드 주석, 테스트 이름, 개발자용 메시지는 대상이 아니다. 서버 오류의 `message`는 오류 상세 접힘(`app/ErrorBoundary.tsx:27` "문의할 때 보낼 정보")을 빼면 화면에 나가지 않는다(`api/errorView.ts:12` "Never display raw server internals").

## 1. 용어 통일표

같은 뜻은 한 단어로 쓴다. "근거" 칸의 줄 수는 테스트, 목, 주석을 뺀 UI 코드 기준이다(세는 법은 7절).

| 개념 | 쓰는 말 | 쓰지 않는 말 | 근거 (develop) |
|---|---|---|---|
| 코드 저장소 | 레포 | 저장소 | "레포" 33줄, "저장소" 17줄. 메뉴와 제목은 이미 레포다(`components/layout/AppShell.tsx:14` "레포 정리", `features/repos/ReposPage.tsx:25` "레포 선택"). 한 줄에 둘이 섞인 곳도 있다(`features/repos/ReposPage.tsx:80`, `features/repos/AnalyzePage.tsx:131`). |
| AI가 레포를 읽어 후보를 만드는 일 | 정리. 세부 단계 이름에서만 "읽기" | 분석, 작업(서버 Job을 뜻할 때) | `features/repos/AnalyzePage.tsx`가 이미 "정리"다(`features/repos/AnalyzePage.tsx:30`, `:96`, `:101`, `:112`, `:148`, `:175`). 단계 이름은 "읽기"다(`lib/labels.ts:38` "커밋 읽기"). "분석"이 17줄 남았고, 같은 파일에 "정리 시작"(`features/repos/AnalyzePage.tsx:101`)과 "분석 취소"(`features/repos/AnalyzePage.tsx:210`)가 같이 있다. 서버 Job을 "작업"이라 부르는 곳은 `features/jobs/JobStatusPage.tsx:23`, `features/jobs/ActiveJobsList.tsx:15` 등이다. 이 행은 6절 1번과 함께 읽는다. |
| 내가 한 일 | 한 일 | 작업 | 같은 글자 "작업"이 사용자의 일도 가리킨다(`features/auth/LandingPage.tsx:40` "내가 한 작업", `features/candidates/CandidateBoardPage.tsx:329` "무엇을 한 작업인가요"). "한 일"은 이미 쓴다(`features/cards/NewCardPage.tsx:29` "내가 한 일을 적어 주세요", `lib/siteMetadata.ts:4`). |
| 후보, 카드, 경험 | 1.1 참고 | | |
| 증거 | 근거. 커밋 쪽은 "근거 커밋 N개", 사용자 쪽은 "내 답변 N개" | 증거. 사용자 쪽을 "내가 말한 것", "직접 쓴 답변", "직접 작성한 내용"으로 부르는 것 | "근거" 32줄, "증거" 0줄. 한 곳은 이미 맞다(`components/ui/index.tsx:153` "근거 커밋", "내 답변"). 사용자 쪽 이름이 다섯 가지로 갈린다(5절). "내가 쓴 문장"은 문장을 누가 썼는지 말할 때만 쓴다(`lib/labels.ts:33` `authoredByLabel`). |
| 칸 상태 | 확인 필요(확신이 낮은 칸), 비어 있음 | 보완 필요, 확정 대기 | `features/cards/StarBlock.tsx:34` "확인 필요", `features/cards/InterviewPage.tsx:163` "비어 있음". "보완 필요"는 탭 이름인데(`features/cards/CardsListPage.tsx:73`) 뜻은 작성 중이면서 비었거나 확인 필요인 칸이 있는 카드다(`features/cards/CardsListPage.tsx:19`). 그래서 칸 상태 "확인 필요"와 다른 말처럼 읽힌다. "확정 대기"는 한 곳이고(`features/home/HomePage.tsx:72`) 정의가 없다. 이 둘은 쓰지 않거나, 쓰려면 먼저 뜻을 정한다(6절 3번). |
| 카드 상태 | 작성 중, 확정됨 | 확정 대기 | `lib/labels.ts:14` `cardStatusLabel`(`DRAFT: '작성 중'`, `CONFIRMED: '확정됨'`), `features/cards/CardsListPage.tsx:107` "작성 중". |
| 첫 글과 저장 | 초안(AI가 처음 만든 글만), 작성 중(카드 상태) | 임시 저장 | "초안"은 AI의 첫 글에만 붙는다(`lib/labels.ts:16` "첫 초안", `features/cards/CardPage.tsx:102` "카드 초안을 쓰고 있어요"). "임시 저장"은 `features/cards/NewCardPage.tsx:179` 버튼 한 곳이고, 누르면 작성 중 카드가 만들어진다(`:76` `createDraft`). |
| 답변 | 행동은 "답변하기", 내가 한 것은 "내 답변" | 답변 작성, 답변으로 보완, 보완하기 | `lib/labels.ts:22` `interviewActionLabel = '답변하기'`를 `features/cards/StarBlock.tsx:49`가 쓴다. |
| 내가 쓰는 행동 | 새로 쓰기는 "직접 작성", 있는 문장을 고치기는 "수정", 후보를 내가 넣기는 "후보 직접 추가" | 버튼과 제목의 "직접 쓰기", "직접 입력", "직접 수정", "수정하기" | 이미 맞는 곳은 "직접 작성"(`features/cards/NewCardPage.tsx:143` 제목, `features/home/HomePage.tsx:140`)과 "후보 직접 추가"(`features/candidates/CandidateBoardPage.tsx:325` 제목)다. 같은 행동에 이름이 여럿이라 정리한다. 버전 출처 이름(`lib/labels.ts:16` `USER_EDIT`의 "직접 수정")은 버튼이 아니므로 이 행의 대상이 아니다. |
| 개인정보 가리기 | 가리기, 가린 정보 | 마스킹, 정보 가림 | 이미 맞는 곳은 `features/cards/CardPage.tsx:186` "공유할 정보 가리기", `features/cards/CardModes.tsx:160` "회사명 가리기"다. "마스킹"이 8줄 남았다. 코드 이름(`maskRules`, `MaskMode`)은 그대로 둔다. |
| 버전 기록 | 작성 이력(목록), v3(항목) | 히스토리, 버전 기록 | `lib/labels.ts:23` `historyActionLabel = '작성 이력'`, `features/cards/CardDialogs.tsx:73` 제목 "작성 이력". "히스토리"는 `features/cards/CardPage.tsx:137` 한 줄이다. "수집 이력"(`features/settings/GithubSettingsPage.tsx:58`)은 레포 수집 기록이라 다른 말이다. |
| 내 정보 화면 | 마이페이지 | 설정 | `components/layout/AppShell.tsx:20`, `components/layout/ProfileMenu.tsx:45`, `features/settings/MyPage.tsx:28`이 "마이페이지"다. 사이드바 묶음 제목(`components/layout/AppShell.tsx:18` "설정")은 화면 이름이 아니라 묶음 이름이라 이 행에서 다루지 않는다. 경로 `/settings`는 그대로 둔다. |
| 메뉴 이름 | 레포, 경험 카드, 공고 매칭. 모바일 탭은 앞 단어만(레포, 카드, 매칭) | 같은 메뉴를 부르는 "레포 정리", "레포 고르기", "레포 선택" | 레포 메뉴 하나에 이름이 넷이다(`components/layout/AppShell.tsx:14` "레포 정리", `components/layout/AppShell.tsx:33` "레포", `features/repos/ReposPage.tsx:69` "레포 고르기", `features/repos/ReposPage.tsx:25` "레포 선택"). |
| 공고 | 기능은 "공고 매칭", 항목은 "공고", 조건은 "공고 조건" | 채용, 채용공고(원문 표현은 예외) | `features/match/MatchListPage.tsx:23` "공고 매칭", `:142` "공고 N건", `:119` "공고 조건". "채용"은 "상시 채용"처럼 공고 원문을 옮길 때만 쓴다(`features/match/model.ts:58`). |
| 파일로 떠올리기 | 파일 보며 찾기 | 회상 도우미, 회상 | `features/candidates/CandidateBoardPage.tsx:141`과 `features/home/HomePage.tsx:139`가 "파일 보며 찾기"인데 화면 자체는 "회상 도우미"다(`features/candidates/RecallPage.tsx:35`, `:76`). 경로 `/recall`과 `RecallPage`는 그대로 둔다. |
| 상황, 과제, 행동, 결과 | 한국어 이름만. 묶어 부를 때는 풀어 쓴다("상황, 과제, 행동, 결과 형식") | STAR(화면에서), S/T/A/R, "상황 (Situation)" | `lib/labels.ts:26` `starFieldShort`, `components/ui/index.tsx:12`는 한국어뿐이다. AI도 질문에는 한국어 이름을 쓴다(`ai/services/interview_agent.py:38` "사용자는 S·T·A·R 글자를 모르므로"). 영어 병기와 글자가 남은 곳은 5절. AI 프롬프트 설명은 T를 "책임"이라 부르지만(`ai/services/star_generator.py:43`) 화면 이름은 "과제"다. |
| 자소서 | 자소서 | 자기소개서 | develop의 화면 문구는 모두 "자소서"이고(`features/auth/LandingPage.tsx:38`, `features/candidates/CandidateBoardPage.tsx:155`, `lib/siteMetadata.ts:3`) "자기소개서"는 0줄이다. BE도 주석에 "자소서"를 쓴다(`backend/src/main/java/com/gitory/backend/coverletter/package-info.java:1`). "자기소개서"는 조사 결과를 옮긴 한 곳뿐이다(`docs/overview/service-plan.md:20`, 레포 루트 기준). 패키지와 API 이름(`coverletter`, `/cover-letters`)은 영어 그대로 둔다. |
| 말투 | 해요체 | 합니다체 | 2절 |

### 1.1 후보, 카드, 경험

고르는 대상인지, 결과물인지, 내가 한 일 자체인지로 가른다.

| 말 | 뜻 | 쓰는 자리 | 쓰지 않는 자리 |
|---|---|---|---|
| 후보 | AI가 레포 기록에서 골라 둔, 아직 카드가 되지 않은 항목(PR, 이슈, 커밋 묶음, 직접 추가) | 화면 이름은 "후보 보드"(`features/candidates/CandidateBoardPage.tsx:46`, `:67`), 항목은 "후보 N개"(`:155`), 고르기는 "후보 선택"(`:173`), 넣기는 "후보 직접 추가"(`:325`), 카드가 된 뒤는 "이미 카드로 만든 후보"(`:195`) | 이미 카드가 된 것, 카드 안의 내용 |
| 추천 후보 | 후보 중 점수 순으로 위에 오는 것 | 선택 단축 버튼 한 곳(`features/candidates/CandidateBoardPage.tsx:176` "추천 후보 선택") | 제목, 소제목, 안내 |
| 카드, 경험 카드 | 후보를 골라 만든 결과물. 종류는 기술 카드와 경험 카드(`lib/labels.ts:12`, API 값은 `TECH`·`QUALITATIVE`) | 목록, 메뉴, 제목은 "경험 카드"(`components/layout/AppShell.tsx:15`, `features/cards/CardsListPage.tsx:29`, `features/home/HomePage.tsx:147`). 문장 속에서는 "카드"(`features/cards/CardsListPage.tsx:107` "카드 N장", `features/candidates/CandidateBoardPage.tsx:205` "카드 만들기") | 후보를 가리킬 때 |
| 경험 | 카드 안에 담기는, 내가 한 일 자체 | "이 경험과 맞는 공고 보기"(`features/cards/CardPage.tsx:132`), "파일을 보며 경험을 떠올려 보세요"(`features/candidates/RecallPage.tsx:78`), "자소서 경험으로"(`features/auth/LandingPage.tsx:38`) | 체크박스로 고르는 항목 |

"정성 카드"는 쓰지 않는다. 직접 쓴 카드(`QUALITATIVE`)의 종류 이름은 "경험 카드"다(#625, #647. 후보 보드는 `features/candidates/CandidateBoardPage.tsx:326` "경험 카드로 생성됩니다"). 메뉴 이름 "경험 카드"(카드 전체)와 종류 이름이 같은 말인 것은 시안도 같아서 그대로 둔다. 둘을 가려야 하는 문장에서는 종류를 한 번 풀어 쓴다(예: 경험을 직접 쓴 카드).

## 2. 말투 규칙

### 2.1 해요체 하나

- 끝맺음은 해요체다("정리를 시작할까요", "읽고 있어요"). 합니다체("-습니다", "-합니다", "-됩니다" 같은 끝맺음)는 쓰지 않는다.
- 같은 화면, 같은 블록 안에서 말투를 섞지 않는다. 지금은 합니다체가 63줄 남아 있다(파일 목록은 부록 A, 대표 사례는 5절). 법적 고지처럼 읽히는 토큰 파기 안내(`features/settings/GithubSettingsPage.tsx:80`)도 예외를 두지 않는다. 예외가 필요하면 팀이 정해 여기에 적는다.
- 존칭 "-시겠어요", "-셨네요", "-시면"은 쓰지 않는다. 사용자에게는 "-해 주세요", "-했어요"로 말한다.
- 명령형 "-하세요", "-고르세요", "-확인하세요"는 쓰지 않는다. 부탁은 "-해 주세요", 가능은 "-할 수 있어요", 상태 설명은 "-해요"로 쓴다. "-해 보세요"가 이 수에 들어 있다(6절 4번).
- 보조용언은 띄운다. "알려 드릴게요", "보여 드려요"가 다수다(`features/repos/AnalyzePage.tsx:207`, `features/cards/DraftProgress.tsx:47`, `lib/labels.ts:62`). "까지"는 앞말에 붙인다.

### 2.2 서버, AI, 목이 만드는 글

화면이 그대로 보여 주는 서버 문장은 FE가 고칠 수 없으므로 서버와 AI 쪽에 같은 규칙을 요청한다. 화면에 그대로 나가는 길은 코드에서 확인했다.

| 문장 | 만드는 곳 | 화면에 나가는 곳 |
|---|---|---|
| 칸 확인 이유(`why`) | `ai/services/star_generator.py:93`, `:94`, `ai/services/star_check.py:19` | `features/cards/StarBlock.tsx:58` `low.why`(백엔드는 `backend/src/main/java/com/gitory/backend/card/api/CardResponse.java:101`에서 그대로 내보낸다) |
| 작성 이력 한 줄 요약(`summary`) | `backend/src/main/java/com/gitory/backend/card/domain/CardHistoryService.java:118`, `:122`, `:126` | `features/cards/CardDialogs.tsx:79`, `:93` |
| 후보 0개일 때 이유(`emptyReasons`) | `backend/src/main/java/com/gitory/backend/recommend/domain/CandidateEmptyReasons.java:64` | `features/candidates/CandidateBoardPage.tsx:138` |
| 후보 추천 이유(`reason`), 읽는 범위 고지(`disclosure`), 회상 안내(`note`) | 실서버 문장의 출처는 확인하지 못했다. 목은 `mock/fixtures.ts:166`, `:21`, `:306` | `features/candidates/CandidateBoardPage.tsx:238`, `:300`, `features/candidates/RecallPage.tsx:78` |

- 서버, AI, 목의 글도 해요체로 쓴다. 예외는 0절의 카드 본문뿐이다. 이미 지키는 곳이 있다(`ai/services/interview_llm.py:42` 질문 "한국어 해요체").
- 가운뎃점으로 이어 붙이지 않고 쉼표를 쓴다. 칸 이름은 S, T, A, R 글자가 아니라 "상황, 과제, 행동, 결과"로 쓴다. 이미 지키는 곳이 있다(`ai/services/interview_agent.py:38`).
- 개수에는 3.5의 단위를 쓴다.
- 목 데이터는 실제 서버가 이럴 것이라는 가정으로 만든 것이라, 규칙에 어긋나면 데모와 e2e가 어긋난 말을 굳힌다. 목도 같은 규칙으로 쓴다.

## 3. 장식 규칙

### 3.1 가운뎃점

- 항목을 나열하거나 정보 조각을 이어 붙이는 데 가운뎃점을 쓰지 않는다. 쉼표, "와/과", 줄바꿈, 괄호로 바꾼다("검색·필터"는 "검색과 필터", "상황·과제·행동·결과"는 "상황, 과제, 행동, 결과").
- 현재 59줄이다. 줄 끝 주석에만 걸린 5줄(`features/repos/ReposPage.tsx:116`, `features/cards/CardModes.tsx:127`, `api/schemas.ts:207`, `:288`, `:344`)과 개발자 메시지 1줄(`api/schemas.ts:86`)을 빼면 화면, 문서 title, 복사본에 나가는 53줄이다.
- 문서 title의 구분자(`lib/useDocumentTitle.ts:8`)와 사이트 title(`lib/siteMetadata.ts:3`)도 같은 규칙이다. 사이트 title은 공유 미리보기 메타에도 들어가므로(`frontend/tooling/siteMetadataPlugin.ts:31`) 바꿀 때는 미리보기 문구와 `frontend/e2e/seo.spec.ts`를 같이 본다.

### 3.2 화살표

- 문구 안에 글자로 쓴 화살표(→, ←, ↗)를 쓰지 않는다. 방향은 버튼 이름과 위치로 말한다. 현재 6줄이고, 5줄은 5절에 있다.
- 나머지 1줄인 입력 칸 사이의 방향 표시(`features/cards/CardModes.tsx:152`)와 아이콘 화살표는 이 규칙으로 판단하지 않았다(6절 6번).

### 3.3 강조 밑줄 단어

- 문장 속 한 단어에 색과 밑줄을 주는 강조(`.hl`)를 새로 만들지 않는다. 쓰는 곳은 `features/home/HomePage.tsx:87` 한 곳이고, 스타일 정의는 세 파일에 있다(`styles/product.css:7`, `styles/layout-tio.css:12`, `styles/guidance.css:12`). 강조가 필요하면 굵기나 배치로 한다.

### 3.4 영문 약어 S/T/A/R

- 화면에는 "상황, 과제, 행동, 결과"만 쓴다. "상황 (Situation)" 같은 영어 병기, 글자 S/T/A/R, "STAR"는 화면에 쓰지 않는다. STAR는 내부 용어다.
- 스크린 리더 이름도 같다. 지금은 점 네 개의 이름이 "STAR 채움: STA"처럼 글자로 읽힌다(`components/ui/index.tsx:94`).
- 사이트 설명(`lib/siteMetadata.ts:4`)은 공유 미리보기에도 들어가므로(`frontend/tooling/siteMetadataPlugin.ts:31`) 바꿀 때 미리보기 문구를 같이 본다.

### 3.5 숫자 단위

현재 코드에서 실제로 쓰는 단위를 세어 정했다. 한 화면에서 "3장", "2개"처럼 단위가 달라 보이는 것은 세는 대상이 달라서다(`features/settings/MyPage.tsx:57`). 같은 대상의 단위가 곳곳에서 갈리는 것만 정리 대상이다.

| 세는 대상 | 단위 | 지금 쓰는 곳 | 어긋난 곳 (정리 대상) |
|---|---|---|---|
| 카드 | 장 | `features/cards/CardsListPage.tsx:107`, `features/settings/GithubSettingsPage.tsx:66`, `features/settings/MyPage.tsx:57`, `features/cards/DraftProgress.tsx:22` | `features/repos/ReposPage.tsx:130` "카드 N건", `features/cards/CardsListPage.tsx:78` "N개 보기", `:107`의 "확정 N, 작성 중 N"(단위 없음) |
| 커밋, 근거 커밋 | 개 | `components/ui/index.tsx:153`, `features/candidates/CandidateBoardPage.tsx:240`, `features/candidates/RecallPage.tsx:89`, `features/auth/LandingExample.tsx:44`, `lib/labels.ts:43` | `features/cards/CardPage.tsx:167`, `features/cards/CardDialogs.tsx:41`, `features/candidates/CandidateBoardPage.tsx:326`가 "건" |
| PR | 건 | `features/repos/ReposPage.tsx:129` "PR 0건", `lib/labels.ts:43`(`PR_REVIEW: '건'`) | 없음 |
| 리뷰 코멘트 | 건 | `features/candidates/CandidateBoardPage.tsx:242` | 없음 |
| 공고 | 건 | `features/match/MatchListPage.tsx:142` | 없음 |
| 후보, 레포, 답변, 변경 파일 | 개 | `features/candidates/CandidateBoardPage.tsx:155`, `features/settings/MyPage.tsx:57`, `features/candidates/CandidateBoardPage.tsx:241` | 없음 |
| 칸 | 칸 | `features/home/HomePage.tsx:193`, `features/cards/CardModes.tsx:100` | `features/home/HomePage.tsx:72`, `lib/cardSummaryView.ts:13`의 "빈 칸 N개", `features/cards/CardPage.tsx:126`의 "비어 있는 항목 N개" |
| 근거(대상 없이) | 쓰지 않는다. "근거 커밋"과 "내 답변"으로 가른다 | `components/ui/index.tsx:153` | `features/cards/CardPage.tsx:126` "근거 N건", `lib/cardSummaryView.ts:15` "근거 N개" |

- 모르는 숫자는 0으로 쓰지 않는다. 숨기거나 "—"로 쓴다(`features/cards/CardsListPage.tsx:57`은 이미 `?? '—'`다. `features/repos/ReposPage.tsx:69`는 `?? 0`이라 어긋난다).
- 읽은 범위는 "9/12개까지"처럼 단위 뒤에 "까지"를 붙여 쓴다(`features/repos/AnalyzePage.tsx:233`은 "까지"를 띄었다).

## 4. 새 문구를 쓸 때 확인할 것

1. 1절 표에 있는 개념이면 그 말만 쓴다. 없는 개념이면 같은 PR에서 표에 한 줄 더한다.
2. 끝맺음이 해요체인지 본다. "-니다", "-시겠어요", "-셨네요", "-세요"(해 주세요는 제외)가 없어야 한다. 서버나 AI가 만드는 글이면 프롬프트와 응답 쪽에도 같은 규칙을 요청한다.
3. 가운뎃점, 글자 화살표, 강조 밑줄 단어, S/T/A/R 영어 병기 없이도 읽히는지 본다. 나열은 쉼표나 "와/과"로 쓴다.
4. 숫자에는 단위를 붙이고 3.5의 단위를 쓴다(카드 장, 커밋 개, PR 건, 칸 칸). 모르는 값은 0이 아니라 "—"다.
5. 고친 뒤 7절 명령으로 해당 파일의 줄 수가 줄었는지 확인한다.

## 5. 현재 코드와 어긋나는 대표 사례

develop 기준이다. 줄 번호와 문구는 develop 코드와 대조했다. 방향은 정리 때 따를 말이고, 문구를 새로 쓰는 일은 각 PR에서 한다.

| 분류 | 위치 | 지금 문구 | 방향 |
|---|---|---|---|
| 레포 | `features/auth/LandingPage.tsx:40` | "저장소를 고르면 내가 한 작업을 찾아 상황·과제·행동·결과로 정리해요." | 레포, 한 일, 쉼표 |
| 레포 | `features/jobs/JobStatusPage.tsx:42` | "저장소 목록" | 레포 목록 |
| 레포 | `lib/labels.ts:8` | "저장소 분석", "카드 초안 생성" | "레포 정리". 둘째는 6절 2번 |
| 정리 | `features/jobs/JobStatusPage.tsx:23` | "일부 분석 완료", "작업 완료", "작업 실패", "작업 진행" | 6절 1번을 정한 뒤 "정리 완료" 계열로 |
| 정리 | `features/repos/AnalyzePage.tsx:210`, `:186` | "분석 취소", "분석 진행률" | 정리 취소, 정리 진행률 |
| 정리 | `features/candidates/CandidateBoardPage.tsx:157`, `:297` | "분석 기준", "이 보드의 분석 기준" | 정리 기준 |
| 후보 | `features/candidates/CandidateBoardPage.tsx:133` | "자동으로 찾은 경험이 없어요" | 후보 |
| 후보 | `features/candidates/CandidateBoardPage.tsx:156`, `api/errorView.ts:46` | "카드로 만들 경험을 선택하세요.", "카드로 만들 경험을 먼저 선택해 주세요." | 고르는 대상은 후보 |
| 후보 보드 | `features/cards/CardPage.tsx:201`, `components/cards/CardDeleteAction.tsx:50` | "후보 목록", "이 저장소의 후보 목록에서" | 후보 보드 |
| 상태 | `features/cards/CardsListPage.tsx:73`, `features/home/HomePage.tsx:72` | "보완 필요", "확정 대기" | 6절 3번 |
| 저장 | `features/cards/NewCardPage.tsx:179` | "임시 저장 시작" | 작성 시작 |
| 답변 | `features/cards/InterviewPage.tsx:82`, `features/cards/CardPage.tsx:258`, `:255` | "답변 작성", "답변으로 보완", "보완하기" | 답변하기 |
| 행동 | `features/cards/StarBlock.tsx:48`, `features/jobs/JobStatusPage.tsx:44`, `features/repos/AnalyzePage.tsx:132` | "직접 쓰기" | 직접 작성 |
| 행동 | `features/cards/InterviewPage.tsx:169`, `features/candidates/CandidateBoardPage.tsx:143` | "직접 수정", "직접 입력" | 수정, 직접 작성 |
| 가리기 | `features/cards/CardModes.tsx:140`, `:182`, `features/cards/CardDialogs.tsx:41`, `lib/labels.ts:16` | "마스킹 규칙 N개를 저장했습니다", "마스킹 적용", "마스킹", "정보 가림" | 가리기 |
| 작성 이력 | `features/cards/CardPage.tsx:137` | "확정 버전은 히스토리에 남습니다" | 작성 이력 |
| 마이페이지 | `components/layout/AppShell.tsx:36`, `features/settings/LeavePage.tsx:14` | "설정", "설정으로 돌아가기" | 마이페이지 |
| 메뉴 이름 | `components/layout/AppShell.tsx:14`, `:33`, `features/repos/ReposPage.tsx:69`, `:25` | "레포 정리", "레포", "레포 고르기", "레포 선택" | 레포 |
| 공고 | `features/match/MatchListPage.tsx:79`, `features/match/shared.tsx:13` | "채용 조건과 연결되는 내 경험을 확인하세요.", "채용공고" | 공고 조건, 공고. 명령형도 같이 |
| 회상 | `features/candidates/RecallPage.tsx:35`, `:76` | "회상 도우미" | 파일 보며 찾기 |
| 근거 | `features/cards/CardPage.tsx:167` | "근거 커밋 N건", "내가 말한 것 N건" | "근거 커밋 N개", "내 답변 N개" |
| 근거 | `lib/cardSummaryView.ts:14`, `lib/labels.ts:24`, `components/ui/index.tsx:137` | "내가 쓴 문장 N개", "직접 쓴 답변", "직접 작성한 내용" | 내 답변 |
| 영문 병기 | `lib/labels.ts:25` | "상황 (Situation)", "과제 (Task)", "행동 (Action)", "결과 (Result)" | 한국어만. 접근성 이름과 단위 테스트의 레이블도 같이 바꾼다 |
| STAR | `features/cards/CardCopyDialog.tsx:12`, `components/ui/index.tsx:94`, `lib/siteMetadata.ts:4` | "STAR 형식", "STAR 채움", "STAR 경험 카드" | 풀어 쓰기 |
| 글자 노출 | `features/cards/InterviewPage.tsx:121`, `features/cards/StarBlock.tsx:46`, `features/cards/CardPage.tsx:209` | `{field} 칸`, `{field} 칸만 다시 생성` | 한국어 칸 이름 |
| 글자 노출 | `features/cards/CardPage.tsx:206`, `:230`, `:288` | 글자 목록 뒤에 붙는 "는 채워졌고,"와 "은 아직입니다"(예: S, T, A와 R), "결과(R)가 채워지지 않습니다", 글자를 `join('·')`으로 이은 뒤의 "칸이 빈 이유를 설명할 수 있나요?" | 한국어 칸 이름, 해요체 |
| 가운뎃점 | `features/candidates/CandidateBoardPage.tsx:170`, `:185` | "후보 검색 (제목·추천 이유)", "더 보기 후보 N개"에 이어 붙는 " · 보기" | 쉼표, 문장으로 |
| 가운뎃점 | `features/auth/LandingExample.tsx:44`, `lib/labels.ts:39` | "연결된 근거 · 커밋 3개", "PR · 리뷰 읽기" | 쉼표, "PR과 리뷰 읽기" |
| 가운뎃점 | `features/settings/MyPage.tsx:62`, `features/repos/ReposPage.tsx:81`, `features/cards/NewCardPage.tsx:170` | "저장·보관 정보", "검색·필터 초기화", " · 필수" | "저장과 보관 정보", "검색과 필터 초기화", "필수" |
| 화살표 | `features/repos/ReposPage.tsx:89` | "다음 →" | 다음 |
| 화살표 | `features/match/MatchDetailPage.tsx:39`, `:74`, `:157`, `features/auth/LandingBrand.tsx:84` | "← 공고 목록", "공고 원문 보기 ↗", "카드 보기 →", "건너뛰기" 뒤의 "↗" | 글자 화살표 제거 |
| 밑줄 단어 | `features/home/HomePage.tsx:87` | `<span className="hl">경험을</span>` | 강조 제거 |
| 합니다체 | `features/NotFoundPage.tsx:9`, `features/repos/AnalyzePage.tsx:176`, `features/settings/GithubSettingsPage.tsx:80`, `features/cards/CardDialogs.tsx:74` | "그런 페이지가 없습니다", "읽고 있습니다", "토큰은 유예 없이 즉시 파기됩니다.", "되돌리면 새 버전이 생깁니다" | 해요체 |
| 합니다체 | `features/home/HomePage.tsx:138`, `:139`, `:140`, `lib/labels.ts:28` | "커밋과 PR에서 경험을 찾습니다", "수정한 파일을 보며 경험을 떠올립니다", "코드에 남지 않은 경험을 적습니다", "근거를 찾지 못해 비워 두었습니다" | 해요체 |
| 존칭 | `features/repos/AnalyzePage.tsx:123`, `features/auth/LandingPage.tsx:47`, `features/cards/CardModes.tsx:89`, `features/cards/InterviewPage.tsx:158` | "이렇게 해 보시겠어요", "GitHub에서 동의를 취소하셨네요", "여기 쓰시면", "채우시면 돼요" | "-해 주세요", "-했어요" |
| 명령형 | `features/match/MatchListPage.tsx:79`, `features/candidates/CandidateBoardPage.tsx:156`, `features/cards/CardPage.tsx:255`, `features/repos/ReposPage.tsx:87` | "확인하세요", "선택하세요", "확정하세요", "고르세요" | "-해 주세요", "-할 수 있어요" |
| 띄어쓰기 | `features/cards/CardPage.tsx:106`, `features/repos/AnalyzePage.tsx:233` | "알려드릴게요", 앞말과 띄운 "까지 읽었어요" | "알려 드릴게요", "9/12개까지 읽었어요" |
| 서버, AI, 목 | `ai/services/star_generator.py:93`, `:94` | "근거는 있으나 해석이 불확실해 사용자 확인이 필요합니다.", "근거 커밋 없이 사용자 답변으로만 채워 사용자 확인이 필요합니다." | 해요체로 요청 |
| 서버, AI, 목 | `backend/src/main/java/com/gitory/backend/card/domain/CardHistoryService.java:118`, `:122`, `:126`, `backend/src/main/java/com/gitory/backend/recommend/domain/CandidateEmptyReasons.java:64` | `joining(" · ")`, `" · 원본(잠김)"`, `" · 확정"`, `건 · 리뷰 ` | 글자 S/T/A/R 대신 한국어 칸 이름, 쉼표 |
| 서버, AI, 목 | `mock/fixtures.ts:223`, `:306`, `:21` | "수치가 없고 근거 커밋이 1건뿐입니다", "답한 내용은 그대로 경험 카드의 근거가 됩니다.", "비공개 저장소 — 권한 자체를 요청하지 않습니다" | 해요체, 커밋은 개, 레포 |
| 단위 | `features/cards/CardPage.tsx:167`, `features/cards/CardDialogs.tsx:41`, `features/candidates/CandidateBoardPage.tsx:326` | "근거 커밋 N건", `${commits}건`, "커밋 N건이 붙어 있습니다" | 개 |
| 단위 | `features/repos/ReposPage.tsx:130`, `features/cards/CardsListPage.tsx:78` | "카드 N건", "N개 보기" | 장 |
| 단위 | `features/home/HomePage.tsx:72`, `features/cards/CardPage.tsx:126` | "빈 칸 N개", "비어 있는 항목 N개" | 칸 |
| 0으로 보임 | `features/repos/ReposPage.tsx:69` | `{repos.data?.length ?? 0}` | 모르면 "—"나 숨김 |

## 6. 아직 정하지 못한 것

새 문구에는 쓰지 말고 팀이 정한 뒤 1절 표에 올린다.

1. 서버 Job을 묶어 부르는 말. `features/jobs/JobStatusPage.tsx`는 레포 정리(`ANALYZE`)와 카드 초안 쓰기(`DRAFT`)를 같이 보여 준다(`features/jobs/JobStatusPage.tsx:36` `job.type === 'DRAFT'`, `api/schemas.ts:40`). 제목을 "정리 완료"로 바꾸면 카드 초안 쓰기에는 맞지 않는다. 홈의 "진행 중인 작업"(`features/jobs/ActiveJobsList.tsx:15`)도 두 종류를 함께 담는다.
2. 카드 초안을 만드는 동작의 이름. "쓰다"(`features/cards/CardPage.tsx:102`, `features/candidates/CandidateBoardPage.tsx:155`)와 "생성"(`lib/labels.ts:8`, `features/cards/DraftProgress.tsx:44`)이 섞여 있다.
3. "보완 필요"와 "확정 대기"를 없앨지, 뜻을 정해 남길지.
4. "-해 보세요" 같은 권유형을 허용할지. 허용하면 2.1의 명령형 수에서 뺀다.
5. AI를 부르는 말. "분석 도우미"(`lib/labels.ts:46`, `features/match/shared.tsx:47`)에서 "분석"을 "정리"로 바꾸면 공고 매칭 화면에는 맞지 않는다.
6. 아이콘 화살표(`ArrowRight` 등)와 입력 칸 사이 방향 표시(`features/cards/CardModes.tsx:152`), 점 안의 글자 S/T/A/R(`components/ui/index.tsx:95`)을 어떻게 할지.

## 7. 다시 세는 법

`frontend/`에서 실행한다. 따옴표 없는 glob은 zsh에서 실패하므로 `--include`는 따옴표로 감싼다.

```sh
ui() { grep -rn "$@" src --include="*.ts" --include="*.tsx" | grep -v -e "^src/test/" -e "^src/mock/" | grep -vE "^[^:]+:[0-9]+:[[:space:]]*(//|\*|/\*)"; }

ui "저장소"                # 17줄
ui "분석"                  # 17줄
ui "마스킹"                # 8줄
ui "히스토리"              # 1줄
ui -E "니다"               # 64줄(줄 끝 주석에 걸린 1줄 포함, 합니다체는 63줄)
ui -E "[^주]세요"          # 16줄(명령형과 권유형)
ui -E "시겠|셨|쓰시|채우시"  # 4줄(존칭)
ui "·"                     # 59줄(줄 끝 주석 5줄, 개발자 메시지 1줄 포함)
ui -E "→|←|↗|↘"            # 6줄
ui 'className="hl"'        # 1줄
```

`src/mock`은 목 데이터라 제외했다. 목의 문장도 같은 규칙으로 쓰므로(2.2) 목까지 보려면 함수에서 `-e "^src/mock/"`를 지운다.

## 부록 A. 합니다체 63줄의 파일 목록

줄 전체는 옮기지 않는다. 위 7절의 `ui -E "니다"`로 나온다(줄 끝 주석 1줄 제외).

- 화면 문구 58줄: `features/NotFoundPage.tsx`(2), `features/candidates/CandidateBoardPage.tsx`(5), `features/candidates/RecallPage.tsx`(3), `features/cards/CardCopyDialog.tsx`(2), `features/cards/CardDialogs.tsx`(13), `features/cards/CardMetadataDialog.tsx`(3), `features/cards/CardModes.tsx`(3), `features/cards/CardPage.tsx`(8), `features/cards/NewCardPage.tsx`(4), `features/home/HomePage.tsx`(4), `features/repos/AnalyzePage.tsx`(1), `features/settings/GithubSettingsPage.tsx`(6), `lib/labels.ts`(4)
- 개발자 메시지로 보이는 5줄(화면에 나가는지 확인 필요): `api/client.ts`(4), `api/schemas.ts`(1)

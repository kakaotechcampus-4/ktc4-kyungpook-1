# Gitory — 프론트엔드

경험 정리 → 자소서·면접 → 기업 매칭으로 이어지는 커리어 관리 서비스의 프론트엔드입니다.

## 스택
- React 18 + Vite
- Tailwind CSS v3
- lucide-react (아이콘)

## 실행
```bash
npm install
npm run dev      # 개발 서버 (http://localhost:5173)
npm run build    # 프로덕션 빌드
npm run preview  # 빌드 결과 미리보기
```

## 폴더 구조
```
src/
├─ App.jsx                          # 최상위 (레이아웃 + 페이지 라우팅)
├─ components/
│  ├─ layout/
│  │  ├─ AppLayout.jsx              # 사이드바 + 메인 골격 (active/onNavigate 전달)
│  │  └─ Sidebar.jsx                # 좌측 내비게이션 (커리어 관리 / 설정, controlled)
│  ├─ home/
│  │  ├─ HomePage.jsx               # "경험정리" 홈 화면 조립
│  │  ├─ OrganizePromoBanner.jsx    # 레포 정리를 유도하는 배너
│  │  ├─ CardToolbar.jsx            # 검색 / 기술·정성 필터 / 정렬 / 직접 작성 / 레포 정리하기
│  │  ├─ CardItem.jsx               # 경험 카드 1개 (card prop)
│  │  ├─ CardItemSkeleton.jsx       # 로딩 스켈레톤
│  │  └─ CardGrid.jsx               # 카드 그리드 + 빈 상태
│  ├─ cards/
│  │  └─ CardsPage.jsx              # "경험 카드" 전용 페이지 (대시보드 없이 목록만)
│  ├─ organize/                     # "레포 정리" 마법사 플로우
│  │  ├─ OrganizeFlow.jsx           # 스텝 상태 관리 + 상단 브레드크럼
│  │  ├─ RepoSelectStep.jsx         # 레포 단일 선택 (내 커밋/팀 커밋 비교)
│  │  ├─ DisclosureStep.jsx         # 읽습니다 / 읽지 않습니다 사전 고지
│  │  ├─ AnalyzingStep.jsx          # 분석 진행 체크리스트 (자동 진행 시뮬레이션)
│  │  ├─ CandidateBoardStep.jsx     # 후보 보드 (다중 선택 + 스티키 CTA)
│  │  ├─ DraftStep.jsx              # 카드 초안(STAR) 편집 + 되묻기 트리거 + 확정
│  │  ├─ InterviewPanel.jsx         # 되묻기 인라인 패널 (찾은 것/없는 것/질문/답변)
│  │  └─ ConfirmDialog.jsx          # 카드 확정 확인 모달
│  ├─ settings/
│  │  ├─ GithubSettingsPage.jsx     # "GitHub 연결" 설정 페이지
│  │  └─ AccountSettingsPage.jsx    # "마이페이지"
│  ├─ common/
│  │  └─ Avatar.jsx                 # 이니셜 기반 아바타
│  └─ modals/
│     └─ CardDetailModal.jsx        # 경험 카드 상세(읽기 전용 STAR) 모달
├─ hooks/
│  ├─ useCards.js                   # 경험 카드 목록 로딩 훅 (백엔드 연동 지점)
│  └─ useModalTransition.js         # 모달 열림/닫힘 트랜지션 상태 훅
└─ data/
   ├─ cards.js                      # 경험 카드 mock 데이터 + 상태 상수
   ├─ repos.js                      # 레포 목록 mock 데이터
   ├─ candidates.js                 # 후보 보드 mock 데이터
   └─ user.js                       # 로그인 사용자 · GitHub 연결 mock 데이터
```

## 내비게이션
사이드바는 `App.jsx`가 들고 있는 `active` 상태를 그대로 반영하는 controlled 컴포넌트입니다.
각 메뉴 키는 `App.jsx`의 `PAGES` 맵을 통해 다음 화면으로 연결됩니다. `organize`만 예외로,
페이지 전환이 아니라 `OrganizeFlow`가 내부적으로 스텝을 관리합니다.

| 메뉴 | 화면 | 비고 |
|---|---|---|
| 경험정리/홈 | `HomePage` | Figma `v3` E1 |
| 레포 정리 | `OrganizeFlow` | B1~D8 마법사 (레포 선택 → 사전 고지 → 분석 → 후보 보드 → 카드 초안) |
| 경험 카드 | `CardsPage` | 대시보드 없이 카드 목록만 |
| GitHub 연결 | `GithubSettingsPage` | 연결 상태 카드 + 레포 정리 진입점 |
| 마이페이지 | `AccountSettingsPage` | 이름 · 플랜 · 크레딧 표시 |

## 도메인 모델 (v3)
이전 버전은 "활동(Activity)" 아래 경험 카드를 묶었지만, v3부터는 **경험 카드가 화면의 기본 단위**입니다.
카드는 `kind`(기술 · 정성), `status`(작성 중 · 확정됨), `needsReview`(확인 필요 플래그), 근거 출처
(`PR` · `이슈` · `커밋 묶음` · `되묻기`)를 가집니다. `src/data/cards.js`의 `evidenceSummary()`가
"근거 N건" · "내가 말한 것 N건" · "R 칸 비어 있음" 표기를 계산합니다.

레포 정리 흐름(`src/components/organize/`)은 레포 선택 → 사전 고지 → 분석 진행 → 후보 보드 →
카드 초안(STAR + 되묻기) → 확정까지 이어지며, 각 단계는 mock 데이터(`repos.js`, `candidates.js`,
`cards.js`의 `star` 필드)로 동작합니다.

## 백엔드 연동
`src/hooks/useCards.js` 안에 fetch 블록이 주석으로 준비돼 있습니다.
백엔드가 준비되면 주석을 풀고 mock 블록을 지우면 됩니다.

기대하는 응답 형태는 [docs/api-spec.md](docs/api-spec.md)에 정리했습니다 (v3 기준으로 갱신됨).

## 디자인 출처
Figma 파일 `Gitory`의 `화면 v3 · 스펙 정렬` 섹션(총 27개 프레임)을 기준으로 구현했습니다.
핵심 플로우(레포 선택 · 사전 고지 · 분석 진행 · 후보 보드 · 카드 초안 · 되묻기 · 홈 · 카드 상세 ·
GitHub 연결 · 마이페이지)는 반영했고, 온보딩(A1~A3) · 세부 실패/엣지케이스 화면(수집 실패 ·
요청 한도 · 소재 부족 · 커밋 묶음 펼치기 · 마스킹 · 버전 히스토리 · 정성 카드 직접 작성 ·
탈퇴·삭제)은 이번 범위에서 제외했습니다.

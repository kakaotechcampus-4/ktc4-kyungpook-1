# README 화면 캡처

## 현재 README에 사용하는 화면

- 캡처일: **2026-11-09** (Asia/Seoul)
- 대상: [Gitory 운영 서비스](https://gitory.duckdns.org/)
- 데이터: 로그인된 서비스에서 조회한 공개 저장소 개발 기록, 경험 카드, 공고 및 자소서 초안.
- 형식: Chrome DevTools의 **Capture screenshot**으로 저장한 PNG 원본. 브라우저 도구 모음은 포함하지 않았으며, 화면 문구·데이터를 합성하거나 바꾸지 않았다.
- 크기: 모두 3420 × 1780px. 동일한 데스크톱 브라우저 창에서 캡처했다.
- 해석: 카드 수·공고 수·마감일·매칭 결과·자소서 내용은 캡처 당시의 상태다. 제품의 성능 지표나 전체 사용자 통계가 아니다.

| 파일 | 화면 | 확인한 내용 |
| --- | --- | --- |
| [01-home.png](01-home.png) | 홈 | 한 줄 작업 입력, 지원할 공고, 조건을 채운 카드, 자소서 이어보기 |
| [02-repositories.png](02-repositories.png) | 저장소 선택 | 내 기여 정보, 저장소 분류, 정리 시작 |
| [03-candidates.png](03-candidates.png) | 경험 후보 | 추천 작업, 부분 수집 안내, 이미 카드로 만든 일, 최대 10개 선택 |
| [04-star-card.png](04-star-card.png) | 확정 경험 카드 | STAR 칸별 근거 커밋, 태그, 만든 과정, 작성 이력, 복사·인쇄 |
| [05-matching.png](05-matching.png) | 공고 매칭 | 필수 조건을 채운 정도와 부족한 경험 |
| [06-posting.png](06-posting.png) | 공고 상세 | 조건별 맞음·모자람·직접 확인, 근거 카드, 원문·자소서 연결 |
| [07-cover-letter.png](07-cover-letter.png) | 자소서 초안 | 문항별 편집, 빈칸 안내, 대화, 진단과 첨삭, 짐작 확인 |

공개 팀 저장소 `kakaotechcampus-4/ktc4-kyungpook-1`의 후보·카드를 주요 예시로 사용했다.
설정·인적사항 입력 화면, 인증 정보, 비공개 저장소는 캡처에 포함하지 않았다.

## 화면을 갱신할 때

1. 운영 서비스에서 해당 화면을 열고 데이터 로딩이 끝난 것을 확인한다.
2. 민감한 정보가 없는지 확인하고, 브라우저 UI가 제외된 화면 원본을 저장한다.
3. 루트 README의 경로·대체 텍스트·기능 설명을 실제 화면과 대조한다.
4. 이 문서에 캡처일·대상·데이터 출처를 갱신하고, 모든 이미지를 직접 열어 확인한다.

## 이전 캡처

`01-home.jpg` · `02-repositories.jpg` · `03-candidates.jpg` · `04-star-card.jpg` · `05-interview.jpg`는
2026-10-19 [샘플 데모](https://gitory-prototypes.vercel.app)에서 캡처한 체험용 예시다.
이전 화면 비교를 위해 보관하며 현재 루트 README에는 사용하지 않는다.

## 소개 이미지 · 흐름 그림 · 움직이는 화면 (2026-11-11)

- `hero.png`(2400 × 1200) · `flow.png`(2400 × 720): HTML 로 구성해 Playwright 로 그린 그림이다. 화면은 첫 화면이 쓰는 제품 그림(`frontend/public/landing/*.png`)을 브라우저 틀에 담았다. 원본 HTML 은 저장소에 두지 않았다.
- `gif/*.gif`(1280 × 800, 초당 12장, 여섯 개 합쳐 3MB 안팎): 로그인 없이 쓰는 **샘플 데이터(목 서버)** 화면을 Chrome DevTools 스크린캐스트(브라우저가 그리는 프레임 그대로)로 받아 Pillow 로 초당 12장에 맞춰 이었다. 팔레트 하나를 같이 쓰고 바뀐 부분만 저장해 작다. 샘플 데이터라 글 내용은 예시이고, 레포 정리는 PR 이 있는 샘플 레포(web-client)로 찍었다.

| 파일 | 화면 | 길이 |
| --- | --- | --- |
| [gif/analysis-run.gif](gif/analysis-run.gif) | 레포 정리 — 읽은 PR·커밋 줄, 묶은 이유, 추천 이유 | 13초 |
| [gif/card-draft-live.gif](gif/card-draft-live.gif) | 카드 초안 — 네 칸이 한 글자씩 | 15초 |
| [gif/interview-chat.gif](gif/interview-chat.gif) | 되묻기 — 답을 보내면 칸에 넣고 다음 질문 | 10초 |
| [gif/cover-letter-fill.gif](gif/cover-letter-fill.gif) | 자소서 대화 — 한 줄 답으로 그 자리를 다시 씀 | 12초 |
| [gif/repo-match-pick.gif](gif/repo-match-pick.gif) | 맞는 기업 고르기 — 과정과 이유 | 10초 |
| [gif/home-command.gif](gif/home-command.gif) | 홈 한 줄 명령 — 도구를 이어 실행 | 12초 |

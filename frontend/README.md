# gitory-web

## 2026-10-07 기업·직무 매칭과 자소서 초안 (제안 · 목 전용)

확정한 카드 근거로 기업 인재상을 비교하는 `/match` 와 카드 문장으로 만드는 `/cover-letter` 를 목 서버 위에 추가했습니다. PRD 7단계는 V1 범위 밖이라 계약은 미확정 제안이며, 실서버 모드에서는 "서버 연동 대기"로 막힙니다. [설계·검증 기록](docs/2026-10-07-COMPANY_MATCH.md)과 [제안 계약](docs/BACKEND_CONTRACT.md)을 참고하세요.

## 2026-10-07 사이드바 글자 대비

메뉴·그룹 제목·최근 카드 글자는 라이트에서 먹색, 다크에서 밝은 본문색을 사용합니다. `--sidebar-text`로 함께 관리하며 선택 배경·아이콘·글자 굵기와 모바일 메뉴를 유지합니다. 두 테마의 선택/hover·접힘/펼침·모바일 전환과 기존 대비/반응형 검사 6개를 확인했습니다. [후속 기록](docs/2026-10-06-UI_CONSISTENCY.md)을 참고하세요.

## 2026-10-06 검색·메뉴·작성 UI 통일

검색은 테두리 하나와 지우기 동작을 사용하고, 정렬·필터·관련 레포는 테마에 맞는 선택 메뉴를 공유합니다. PC 홈의 좌우 경계, STAR 제목과 입력 시작선, 버튼의 곡률·조작 크기를 맞췄습니다. Pretendard의 본문 400·버튼 500·제목 600과 자간 -3%를 공통 적용하며 스크롤바는 두 테마에서 얇게 표시합니다. 한글 입력·키보드 선택·모바일 긴 메뉴까지 확인했습니다. [구현·검증·재발 방지](docs/2026-10-06-UI_CONSISTENCY.md)를 참고하세요.

최종 새 설치에서 Node 22·24 단위 108개씩, 타입·빌드·정적 E2E 76개와 npm audit 0건을 확인했습니다. 운영 배포에서도 입력·메뉴·저장·새로고침 및 두 테마의 모바일/PC 화면 60개를 확인했습니다.

## 2026-10-04 Node 24 Checks 호환성

Vitest를 4.1.11로 고정하고 Request·DOM 이벤트 취소 회귀 검사를 추가했습니다. 브라우저와 테스트의 TypeScript 환경을 분리하며 테스트도 빌드에서 검사합니다. Node 22·24의 단위 108개와 정적 E2E 60개가 통과했습니다. [원인·설치 재현·검증 기록](docs/2026-10-04-NODE24_CHECKS.md)을 참고하세요.

## 2026-10-03 카드 작업·활용 흐름

홈에서 진행 중인 서버 작업을 다시 열고, 카드 목록을 빈칸/확인 필요로 필터링하며, 본문만·STAR 형식·근거 포함 복사를 선택할 수 있습니다. 생략/null 부가 정보는 임의 출처나 0건으로 표시하지 않습니다. DRAFT 제목·기간 수정과 첫 저장 후 수정은 Mock에서 동작하며 실제 API 모드는 백엔드 확인 전 기본 비활성입니다. [구현·검증·백엔드 요청](docs/2026-10-03-CARD_WORKFLOW.md)을 참고하세요.

## 2026-10-03 UX 8가지·PR 리뷰 보완

개발 StrictMode에서도 저장 성공값이 상세 캐시에 반영되도록 직접 작성·본문 편집의 생명주기 보호를 수정했습니다. 관련 저장·이탈 검사는 앱과 동일한 StrictMode에서 실행합니다. [재현·수정 기록](docs/TROUBLESHOOTING.md)을 참고하세요.

모바일 예시·수정 버튼과 칸별 포커스, 이어쓰기 우선 홈, 자주 쓰는 설정, 조회 실패 건수, 답변/후보 문구와 카드 보완 행동을 정리했습니다. #76의 프론트 보완을 통합했고 확정 성공 뒤 재조회 실패 및 요청 중 모달 닫기로 생길 수 있는 중복 확정도 막습니다. [범위·리뷰 판단·검증 기록](docs/2026-10-03-UX_IMPROVEMENTS.md)을 참고하세요.

## 2026-10-01 제품 UX 정리

라이트는 옅은 세이지 배경·흰 카드·짙은 녹색 행동으로 조정했습니다. 추가 시안의 색 배치 위계를 참고했으며 말차 나이트의 올리브·밝은 그린도 지원합니다. 버튼·선택·STAR·근거의 색과 주의/오류 의미는 공통 토큰으로 관리합니다. [팔레트·적용 범위·검증](docs/MATCHA_THEME.md)을 참고하세요.

확정 창의 자기 확인 문구·체크박스를 제거했습니다. 상황/행동이 준비되면 내용 확인 후 바로 확정할 수 있습니다.

최신 develop의 프론트 보호 로직을 병합하고 GitHub→STAR 첫 화면, PC 개별 카드, 둥근 선택 표시, 테마별 로고, 답변/이력 문구와 권한 배지를 정리했습니다. [설계·PR 리뷰 반영·검증](docs/2026-10-01-UX_PLAN.md)을 참고하세요. API 필드와 enum은 유지합니다.

로컬 5173이 다른 앱에서 사용 중이면 PowerShell에서 `$env:PLAYWRIGHT_PORT='5176'` 후 `npm run e2e:static`으로 검사하세요.

## 2026-09-26 모바일·PC 배치 개선

작성 메타데이터·STAR 입력, 홈 이어쓰기, 설정 정보/상태, 상세 헤더와 공통 하단 행동의 배치를 화면 폭에 맞게 정리했습니다. 짧은 가로 화면에서도 모달 본문만 스크롤되고 하단 행동은 보입니다. [화면 점검과 검증 기준](docs/RESPONSIVE_LAYOUT_AUDIT.md)을 참고하세요.

## 2026-09-26 계약 동기화·후보 보드 보완

Node 버전은 `.nvmrc`의 22를 사용합니다. 확정 오류 코드 `GITHUB_RATE_LIMITED`, `IDEMPOTENCY_KEY_MISMATCH`와 서버 `retryable === true` 조건을 적용했습니다. 후보 제외·생성 실패 시 선택과 입력을 유지하고, 공통 DIRECT_CARD 사례를 테스트합니다. `?demoScenario=candidate-error`로 재현할 수 있습니다.

[#57 영향·담당별 반영·실제 연동 대기 항목](docs/FE_INTEGRATION_REVIEW_2026-09-26.md)을 확인하세요. 후보 랭킹/인터뷰의 실제 백엔드 연동은 아직 대기 중이며 배포는 Mock 체험 모드를 유지합니다.

브라우저 탭 아이콘은 흰 테두리를 제거한 `public/favicon-borderless.svg`를 사용합니다. 원인과 확인 방법은 [브랜딩 이미지 문서](docs/BRAND_ASSETS.md)에 기록했습니다.

## 2026-09-23 모바일 UX 정리

첫 화면의 샘플 체험은 버튼 한 번으로 시작합니다. 모바일에서는 긴 예시 카드를 숨기고 설명을 한 문장으로 줄였습니다. 홈·레포·카드 목록의 반복 제목과 직접 작성 화면의 반복 출처 문구를 걷어 냈으며, 좁은 화면에서 한글 단어가 과하게 쪼개지지 않도록 조정했습니다. 화면별 판단과 회귀 범위는 [모바일 UX 정리 문서](docs/MOBILE_UX_CLEANUP.md)에 기록했습니다.

## 2026-09-23 프론트 안정성 보완

저장 직렬화, 실패 시 입력 유지·이탈 방지, 멱등키 409 대응, 조회 실패 화면, 키보드 모달, 읽기 쉬운 STAR 라벨을 보완했습니다. 실제 API 응답이 느리거나 실패해도 테스트할 수 있는 선택형 Mock 시나리오를 제공합니다.

- 설계·시나리오·검증: [FRONTEND_RESILIENCE.md](docs/FRONTEND_RESILIENCE.md)
- 첫 직접 작성은 `임시 저장 시작`으로 서버 DRAFT를 만든 후 본문을 자동 저장합니다. 저장 실패 시 이 화면에 머물러 재시도하세요.
- 체험 모드는 `샘플로 체험하기`로 표시하며, 탭을 닫으면 데이터가 사라질 수 있습니다. 실제 계정 연결 및 서버 저장과 구분합니다.
- `/cards?demoScenario=sparse`, `/cards?demoScenario=read-error`로 경계 상황을 확인하고 `?demoScenario=normal`로 복구합니다.


Gitory 프론트엔드. 와이어프레임 v3(27장) · 유저 플로우(29노드 · 경로 54개) · 테크스펙 인터페이스 명세를 그대로 코드로 옮겼습니다.
**React 19 + TypeScript + Vite · TanStack Query · React Router · zod**. 백엔드 없이 목 API 로 전 화면이 돌고, Spring 이 뜨면 `.env` 두 줄로 붙습니다.

**데모 배포**: https://gitory-prototypes.vercel.app — 백엔드 없이 브라우저 목으로 전 구간이 돕니다.

```bash
npm install
npm run dev        # http://localhost:5173 — 목 API 내장 (브라우저에서 구동)
npm test           # 계약 계층 유닛 (vitest)
npm run e2e        # 기능 + 반응형 E2E (playwright · 최초 1회 npx playwright install chromium)
npm run e2e:static # 같은 E2E 를 프로덕션 빌드에 — Vercel 이 서빙하는 산출물과 동일
npm run build      # tsc -b && vite build
```

## 백엔드 연동 — "조금만 붙이면" 되는 지점

| 무엇 | 어디 | 상태 |
|---|---|---|
| API 주소 | `.env` `VITE_API_MOCK=false` + `VITE_API_ORIGIN` (개발 프록시) / `VITE_API_BASE` (배포) | 코드 변경 없음 — 목이 꺼지고 fetch 가 그대로 `/api` 로 나갑니다 |
| OAuth | `[GitHub으로 이동]` → `GET {API}/auth/github/start` → GitHub → Spring callback → `302 /`. 취소는 `/login?error=access_denied` | 프론트 완료 |
| 세션 | 쿠키 · `credentials: 'include'` · 401 → `/login?reason=expired` → 재로그인 후 원래 화면 복귀 | 완료 |
| CSRF | `XSRF-TOKEN` 쿠키 → `X-XSRF-TOKEN` 헤더 자동 (Spring `CookieCsrfTokenRepository.withHttpOnlyFalse()`) | 완료 |
| 계약 | **[docs/BACKEND_CONTRACT.md](docs/BACKEND_CONTRACT.md)** ← BE 는 이걸 보고 DTO 를 맞춘다. 단일 출처는 `src/api/schemas.ts` | 문서화 |
| Job | `POST /repos/{id}/analyze` + `Idempotency-Key` → 폴링은 응답의 `pollAfterMs` 간격 → 복구는 `GET /jobs?active=true` | 완료 (2026-09 계약 반영) |
| 임시 저장 | `POST /cards/manual/draft` 로 DRAFT 를 먼저 만들고 `PATCH /cards/{id}/draft` 로 2.5초 디바운스 저장 | 완료 — 브라우저에 카드 입력을 남기지 않습니다 |
| 제목·기간 | 제안 `PATCH /cards/{id}/metadata` 및 상세 period | Mock·프론트 준비, 백엔드 확인 대기. 실제 모드에서 `VITE_CARD_METADATA_ENABLED=true` 명시 전 호출 차단 |
| 지표 | `POST /events` (퍼널 이벤트, `src/lib/track.ts`) | 프론트 완료 |

**R-10 (교차 출처 쿠키)**: 개발은 프록시라 같은 오리진입니다. 배포도 같은 오리진 뒤에 `/api` 를 리버스 프록시로 붙이는 걸 전제로 했습니다. API 가 다른 오리진이면 서버가 `SameSite=None; Secure` + `Access-Control-Allow-Credentials` 를 줘야 합니다.

## 계약 계층 — FE 리드 담당 범위

```
src/api/schemas.ts    zod 스키마 = FE 쪽 계약 단일 출처. enum 전부 영문 대문자. Spring DTO 와 갈라지면 ContractError
src/api/client.ts     { data, error } 봉투 · 401 → AuthError · CSRF 헤더 · 네트워크 실패 메시지
src/api/endpoints.ts  URL 은 이 파일 밖에 없다
src/api/keys.ts       쿼리 키 팩토리 — 무효화는 항상 이 키로
src/api/queries.ts    TanStack Query 훅. Job 폴링(터미널에서 멈춤 · 백그라운드 유지) · 변경 후 무효화 규칙
src/app/queryClient   전역: 401 → 로그인 리다이렉트 · 변경 실패 → 토스트
src/lib/labels.ts     enum → 한국어. 한국어는 이 파일 밖으로 새지 않는다
src/lib/jobWatcher    "끝나면 알려드릴게요" — 화면을 떠나도 Job 완료를 토스트로
src/lib/track.ts      퍼널 이벤트 (스펙 §추가 지표)
```

**"에러가 아닌 것"은 에러로 다루지 않습니다.** 후보 0개(`verdict: EMPTY`) · 부분 결과(`partial: true`) · 작업 실패(`state: FAILED`) 는 200 이고 화면이 판정을 보여줍니다. `ErrorBoundary` 는 진짜 에러(네트워크 · 계약 불일치)에만 뜹니다.

## 라우트 = 플로우 맵 노드

| 라우트 | 노드 | 화면 |
|---|---|---|
| `/login` | A1 · A2 | 랜딩 · GitHub 동의 안내(모달) · 취소/만료 안내 |
| `/` | E1 · A3 | 홈 카드 그리드 · 첫 진입(이력 0) |
| `/repos` | B1 · B2 | 레포 선택(정렬·필터) · 사전 고지(모달, `?select=&disclose=1`) |
| `/repos/:id/run` | B3 · B4 · B5 | 분석 진행 · 수집 실패(E-1) · 요청 한도(E-2) |
| `/jobs/:jobId` | — | 홈 활성 작업의 상세 상태·결과 연결 (새로고침 가능) |
| `/repos/:id/candidates` | C1 · C2 · C3 · C5 | 후보 보드〔게이트 1〕· EMPTY · 커밋 묶음 펼치기(E-4) · 직접 추가+커밋 찾기(`?add=1`) · 분석 기준(`?criteria=1`) · 제외 실행취소 |
| `/repos/:id/recall` | C4 | 회상 도우미 |
| `/cards/:id` | D1~D4 · D6~D9 · E2 · E3 | 생성 중 → 초안 STAR → (편집·자동저장 `?mode=edit` · 마스킹 `?mode=mask` · 확정 `?confirm=1` · 버전 `?versions=1`) → 확정 읽기 · 복사 · 인쇄 |
| `/cards/:id/interview` | D5 | 답변 작성 — 다중 턴 · 서버 `maxTurns` 기준 |
| `/cards/new` | E4 | 정성 카드 직접 작성 |
| `/cards` | — | 카드 목록 (작성 상태·빈칸/확인 필요 필터) |
| `/settings` `/settings/github` `/settings/leave` | F2 · F1 · F3 | 마이페이지(테마) · GitHub 연결 · 탈퇴(정책 미정, 비활성) |

## 디자인 토큰

`src/styles/tokens.css`는 기존 의미 토큰 이름에 그린 팔레트를 연결합니다. 라이트는 #F2F7F4 배경과 #46704E 강조색, 다크는 #12140F 배경과 #A8C47C 강조색입니다. 앰버는 확인 필요, 레드는 실패를 표시합니다. **기본 라이트 고정** — OS 다크를 따라가지 않고 마이페이지에서 켠 것만 기억합니다. 화면 스타일은 의미 토큰을 사용하며, [테마 문서](docs/MATCHA_THEME.md)의 대비 검사를 유지합니다.

입력·선택·버튼의 공통 규칙은 `--control-*`, 글자는 `--body-weight`·`--heading-weight`·`--text-tracking`에서 관리합니다. 검색은 `SearchField`, 선택은 controlled `Select`, STAR 읽기는 `StarRow`, STAR 수정은 `StarEditorField`를 재사용합니다. 새로운 화면에서는 같은 역할을 화면별 CSS로 다시 정의하지 않습니다. 코드·SHA·단일 STAR 글자는 자간을 별도로 유지합니다.

## 반응형 레이아웃

`src/styles/responsive.css`가 셸과 breakpoint의 최종 스타일을 담당하며 다른 CSS 파일보다 마지막에 로드됩니다.

| 화면 폭 | 내비게이션과 레이아웃 |
| --- | --- |
| 320–767px | 상단 브랜드 + 이름이 표시되는 하단 메뉴, 단일 열, safe-area를 고려한 sticky 동작 |
| 768–1199px | 아이콘 중심 축소 사이드바, 콘텐츠 성격에 따른 1–2열 |
| 1200px 이상 | 펼침/접힘 사이드바와 가용 폭을 사용하는 작업 영역 |

랜딩은 가치 제안과 CTA를 먼저 보여주고 세부 GitHub 접근 범위는 접어 둡니다. 모달, 검색·필터, 저장소 행, 후보 목록, 인터뷰, 설정 폼은 작은 화면에서 줄바꿈되며 가로 스크롤을 만들지 않습니다. 레이아웃 문제와 재발 방지는 [상위 트러블슈팅 문서](../docs/TROUBLESHOOTING.md)에 기록합니다.

## 조용한 목록형 UI

- 데모에서도 로컬 GitHub 프로필 샘플을 표시하며, 실제 이미지가 없거나 깨지면 동일 크기의 이니셜로 대체합니다.
- 경험 카드의 STAR는 `S/T/A/R`과 `상황/과제/행동/결과`를 고정된 네 열로 정렬합니다.
- PC 경험 카드는 개별 카드로 구분하고, 저장소·후보 목록은 넉넉한 행 간격과 구분선을 사용합니다. 불필요한 안쪽 테두리와 반복 그림자는 줄입니다.
- 페이지 섹션은 32px, 목록 행과 관련 정보 묶음은 20–24px 간격을 기본으로 사용합니다.
- 선택, 경고, 오류, 입력, 모달처럼 경계를 알아야 하는 요소의 테두리는 유지합니다.

세부 기준과 검증 범위는 [조용한 목록형 UI 설계안](docs/superpowers/specs/2026-09-10-gitory-quiet-ui-design.md)을 참고하세요.

## 목 시나리오 (데모용)

| 레포 | 시나리오 |
|---|---|
| `hong-dev/auth-service` | 정상 · 후보 20개 · 카드 여러 장 |
| `hong-dev/algorithm-study` | **PR 0건** → 커밋 묶음만 |
| `kbu-capstone/team-board` | **후보 0개** → verdict EMPTY → 회상 도우미 |
| `hong-dev/legacy-monolith` | **E-1 수집 실패** |
| `hong-dev/data-pipeline` | **E-2 rate limit** — partial |
| `TaeHuiKKIM/free-tier-sleep` | **케이스 5 실물** — Claude Sonnet 5 가 실제 patch 를 읽고 만든 초안(진짜 sha) |
| "카드감 낮음" 후보로 카드 생성 | **E-6 / E-7** |
| `/login?error=access_denied` | 동의 취소 복귀 (실서버에서 Spring 이 보내는 주소) |

## 목 API 는 어디에 있나

`src/mock/` — `router.ts`(순수 라우터) · `store.ts`(인메모리 상태 + Job 시뮬레이터) · `browser.ts`(fetch 패치).
개발 서버 미들웨어가 아니라 **브라우저에서** 돕니다. 그래서 `vite build` 산출물만 올려도 데모가 그대로 동작합니다 (Vercel).
`VITE_API_MOCK=false` 면 `browser.ts` 가 아무것도 하지 않습니다.

## Job 과 복구 흐름

작업은 서버에서 돈다. 화면은 그 상태를 읽을 뿐이고, **진행 상황을 브라우저에 적어 두지 않는다.**

1. `POST /repos/{id}/analyze` 에 `Idempotency-Key` 를 실어 보낸다. 더블클릭해도, 같은 저장소에 이미 진행 중인 작업이 있어도 Job 은 하나다.
2. `GET /jobs/{jobId}` 를 응답이 준 `pollAfterMs` 간격으로 읽는다. 간격을 화면이 정하지 않는다.
3. 새로고침해서 주소의 `?job=` 을 잃으면 `GET /jobs?active=true` 로 서버에 다시 물어 붙는다.
4. 끝난 이유는 네 갈래로 **각각 다른 화면**이다.
   - 조회 실패 → 완료로 간주하지 않고 기존 Job 조회 재시도 또는 목록으로 이동
   - `FAILED` + `errorCode` → 원인과 다음 행동. `retryable: true`인 경우만 재시도하고, `GITHUB_RATE_LIMITED` 면 `retryAfterSec` 이 지날 때까지 막는다
   - `SUCCEEDED` + `partial: true` → "읽은 데까지 정리했어요" + 이어 읽기
   - `SUCCEEDED` + `verdict: EMPTY` → 실패 화면이 아니라 후보 보드의 판정 화면 (근거 3줄)

## 검증

```bash
npm run typecheck
npm test            # 계약·저장 경합·이탈 보호·전송 실패·Mock 시나리오
npm run e2e         # 기능·오류 복구·UI·반응형 (320~1920px)
npm run e2e:static  # 동일한 검사를 프로덕션 빌드 산출물에 실행
```

최신 검증 기록과 제한은 [FRONTEND_RESILIENCE.md](docs/FRONTEND_RESILIENCE.md)를 참고하세요.
팀 모노레포 CI는 루트 `.github/workflows/frontend-ci.yml`입니다. 개인 프론트 저장소는 `.github/workflows/ci.yml`을 사용합니다.

## 아직 없는 것

- Spring 실연동 검증 (계약은 문서화됨 — 프론트는 확정 계약대로 붙어 있습니다)
- 단계별 이어서 처리(재시도 시 마지막 완료 단계 이후부터)는 서버 몫이라 프론트에서 확인할 수 없습니다
- 데모 목 상태는 `sessionStorage` 라 탭을 닫으면 초기화됩니다
- 카드 다중 선택 일괄 동작(내보내기 묶음) · 카드 검색의 본문 검색
- 오프라인/재연결 배너

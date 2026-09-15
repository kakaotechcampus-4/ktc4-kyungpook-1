# gitory-web

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
| `/repos/:id/candidates` | C1 · C2 · C3 · C5 | 후보 보드〔게이트 1〕· EMPTY · 커밋 묶음 펼치기(E-4) · 직접 추가+커밋 찾기(`?add=1`) · 분석 기준(`?criteria=1`) · 제외 실행취소 |
| `/repos/:id/recall` | C4 | 회상 도우미 |
| `/cards/:id` | D1~D4 · D6~D9 · E2 · E3 | 생성 중 → 초안 STAR → (편집·자동저장 `?mode=edit` · 마스킹 `?mode=mask` · 확정 `?confirm=1` · 버전 `?versions=1`) → 확정 읽기 · 복사 · 인쇄 |
| `/cards/:id/interview` | D5 | 되묻기 — 다중 턴 · 상한 4 (Q6) |
| `/cards/new` | E4 | 정성 카드 직접 작성 |
| `/cards` | — | 카드 목록 (상태 2단 필터) |
| `/settings` `/settings/github` `/settings/leave` | F2 · F1 · F3 | 마이페이지(테마) · GitHub 연결 · 탈퇴(정책 미정, 비활성) |

## 디자인 토큰

`src/styles/tokens.css` 는 Figma 컬렉션(Primitives 84 · Semantic 39)과 이름 1:1. 웜 잉크 무채색 + 신호색 2계열(앰버 = ⚑ 확인 필요 · 브릭 = 실패). **기본 라이트 고정** — OS 다크를 따라가지 않고 마이페이지에서 켠 것만 기억합니다. 화면 코드는 원시 `--ink-*` 를 텍스트 색으로 쓰지 않습니다.

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
- 홈·카드·저장소·후보 목록은 항목마다 네모와 그림자를 반복하지 않고, 목록 표면 하나와 넉넉한 행 간격·구분선을 사용합니다.
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
   - 네트워크가 끊김 → "정리는 서버에서 계속 돌고 있어요" + 다시 연결
   - `FAILED` + `errorCode` → 원인과 다음 행동 3개. `retryable: false` 면 다시 시도 버튼을 막고, `RATE_LIMITED` 면 `retryAfterSec` 이 지날 때까지 막는다
   - `SUCCEEDED` + `partial: true` → "읽은 데까지 정리했어요" + 이어 읽기
   - `SUCCEEDED` + `verdict: EMPTY` → 실패 화면이 아니라 후보 보드의 판정 화면 (근거 3줄)

## 검증

```bash
npm run typecheck
npm test            # 28 — 계약 계층 + 목이 실서버 계약을 지키는지 (src/test/contract.test.ts)
npm run e2e         # 20 — 기능 12 + UI·반응형 8 (320/390/768/1024/1440/1920px)
npm run e2e:static  # 같은 20개를 프로덕션 빌드에 — Vercel 이 서빙하는 산출물과 동일
```

마지막 실행(2026-09-15): typecheck 통과 · vitest 28/28 · e2e 20/20 · e2e:static 20/20.
CI 는 `.github/workflows/ci.yml`.

## 아직 없는 것

- Spring 실연동 검증 (계약은 문서화됨 — 프론트는 확정 계약대로 붙어 있습니다)
- 단계별 이어서 처리(재시도 시 마지막 완료 단계 이후부터)는 서버 몫이라 프론트에서 확인할 수 없습니다
- 데모 목 상태는 `sessionStorage` 라 탭을 닫으면 초기화됩니다
- 카드 다중 선택 일괄 동작(내보내기 묶음) · 카드 검색의 본문 검색
- 오프라인/재연결 배너

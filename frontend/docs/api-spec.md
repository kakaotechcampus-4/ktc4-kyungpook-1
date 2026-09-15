> ## ⚠️ 2026-09-15 — 일부 절이 확정 계약으로 대체됐습니다
>
> 이 문서는 화면을 만들며 프론트가 **제안한 초안**입니다. 그 뒤 백엔드에서 아래 항목이 확정되면서
> 실제로 구현된 계약과 갈라진 절이 생겼습니다. **확정된 내용은 [BACKEND_CONTRACT.md](BACKEND_CONTRACT.md) 가 기준이고,
> 코드 상 단일 출처는 `src/api/schemas.ts`(zod) 입니다.**
>
> | 이 문서의 절 | 상태 |
> |---|---|
> | §3 Job (분석 진행 상태) | **대체됨** — 상태값·필드·폴링 방식이 전부 바뀌었습니다 |
> | §6 카드 — 목록 응답 | **대체됨** — `star` 판정값과 `versionNo` 가 추가됐습니다 |
> | §6 카드 — 임시 저장 | **추가됨** — 이 문서에 없던 `PATCH /cards/{id}/draft` 가 생겼습니다 |
> | §8 미확정 항목 | 일부 해소 — Job·카드 목록·임시 저장은 확정됐습니다 |
> | §0·§1·§2·§4·§7 | 아직 유효합니다 |
>
> 두 문서를 같이 두면 다음 사람이 어느 쪽을 믿어야 할지 모릅니다.
> 이 문서를 접고 `BACKEND_CONTRACT.md` 로 합칠지는 팀에서 정해 주세요 (PR 에 같이 여쭤 뒀습니다).

# Gitory API 명세 (draft v0.2 — v3 반영)

프론트(React + TS + Vite · TanStack Query · React Router)가 필요한 API 모양을 기준으로 작성한 초안입니다.
`인터페이스 명세` 섹션(카드 조회·되묻기·확정)은 테크스펙에 이미 확정된 내용을 그대로 인용했고,
그 외(레포 목록·분석 Job·후보 보드)는 화면을 먼저 만들며 필요해진 것을 프론트 쪽에서 제안하는
초안입니다. **⚠️ 표시는 백엔드 확인 전까지 확정이 아닙니다.**

> v0.2에서 바뀐 것: Figma가 v3로 업데이트되면서 "활동(Activity)" 개념이 완전히 빠지고 카드가
> 화면의 기본 단위가 됐습니다. 이전 v0.1의 §5(활동 대시보드)는 삭제하고, 그 내용을 §6(카드)에
> 흡수했습니다. 레포 분석도 다중 선택 → 단일 선택으로 바뀌었습니다.

## 0. 공통 규칙

- Base URL: `/api`
- 인증: GitHub OAuth 로그인 후 발급되는 세션 쿠키. 클라이언트에 토큰을 두지 않는다.
- 응답 봉투는 전부 `{ data, error }`.
- 실패도 웬만하면 `200 + 상태값`으로 내려온다 (`EMPTY`, `FAILED`, `partial: true` 등). 진짜 HTTP 에러(4xx/5xx)는
  인증 실패·요청 자체가 처리 불가능한 경우로 한정한다.
- enum 값은 전부 영문 대문자. 한국어 라벨은 프론트가 매핑하고, 백엔드 응답에 한국어 문자열을 넣지 않는다.
- 날짜는 ISO 8601(`"2021-07-01"`)로 내려오고, 프론트에서 `2021.07` 형태로 포맷한다.
- 목록 응답은 커서 페이지네이션: `{ data: { items: T[], nextCursor: string | null } }`

```ts
type ApiResponse<T> = { data: T; error: null } | { data: null; error: { code: string; message: string } };
```

## 1. 인증

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/auth/github/login` | GitHub OAuth 시작. `public_repo`, `read:user` 스코프만 요청 (저장소 쓰기 권한 요청 안 함) |
| GET | `/api/auth/github/callback` | OAuth 콜백. 세션 쿠키 발급 후 `/`로 리다이렉트 |
| GET | `/api/auth/session` | 로그인 여부 확인 |
| POST | `/api/auth/logout` | 세션 종료 |

```ts
interface Session {
  id: string;
  name: string;
  githubUsername: string;
  plan: "FREE" | "PRO";
  credits: number; // ⚠️ v3 화면(레포 선택·후보 보드·카드 초안)에는 크레딧 소모 UI가 없다. 마이페이지에만
                    // 남아 있는데, 정말 남겨둔 개념인지 그냥 지우다 만 흔적인지 확인 필요.
}
// GET /api/auth/session → ApiResponse<Session | null>
```

## 2. 레포지토리

### 2-1. 목록 조회

```
GET /api/repositories?query=&sort=ACTIVITY_VOLUME
```

```ts
interface RepositorySummary {
  id: string;
  owner: string;
  name: string;
  language: string | null;
  dateRange: string;          // "2024.03 - 2024.08"
  myCommitCount: number;      // "내 커밋 214 / 팀 402" — R-1(기여도 부풀리기) 방지용
  teamCommitCount: number;
  prCount: number;
  reviewCount: number;        // ⚠️ v3 신규 필드 — 내가 남긴 리뷰 코멘트 수
  myContributionPct: number | null; // 기여도가 낮을 때만 채워짐 (예: 2) — 경고 배지용
  warning: string | null;     // "낮은 기여도 — ..." / "PR 0건 — ..." 같은 한 줄 경고
}
// → ApiResponse<{ items: RepositorySummary[]; totalCount: number }>
```

`myContributionPct`를 배지로 보여주되, STAR 카드 문장에는 절대 넣지 않는다 — 팀 피드백에서
"92% 기여했습니다" 같은 수치화된 자기 주장은 하지 말자고 확인된 부분이라, 여기서는 저 위험을
"미리 알려주는 경고"로만 쓴다.

### 2-2. 분석 시작

```
POST /api/repositories/{repositoryId}/analyze
```

한 번에 레포 하나만 정리한다 (v2의 다중 선택 → v3에서 단일 선택으로 변경). 응답은 `jobId` 하나.
⚠️ 크레딧 차감 여부·크레딧 부족 처리(v0.1에서는 409로 제안했었음)는 화면에서 크레딧 UI 자체가
빠지면서 이 제안이 아직 유효한지 불확실합니다 — 백엔드와 재확인이 필요합니다.

```ts
interface AnalyzeAccepted {
  jobId: string;
}
```

## 3. Job (분석 진행 상태)

```
GET /api/jobs/{jobId}
```

```ts
interface Job {
  id: string;
  type: "REPO_ANALYSIS";
  state: "RUNNING" | "DONE" | "FAILED";
  steps: { key: "COMMITS" | "PR_REVIEW" | "COMPRESS" | "REASON"; state: "DONE" | "RUNNING" | "PENDING"; note: string | null }[];
  partial: boolean; // 요청 한도 소진 등으로 일부만 완료된 경우 true
  resultRepositoryId: string | null;
}
```

`steps`는 화면의 4단계 체크리스트(커밋 읽기 → PR·리뷰 코멘트 읽기 → 후보 20개로 압축 → 추천 이유
붙이기)와 1:1로 대응한다. 프론트는 `useJob(jobId)`를 `state === "RUNNING"`인 동안 폴링한다.

## 4. 후보 보드

```
GET /api/repositories/{repositoryId}/candidates
```

```ts
interface Candidate {
  id: string;
  type: "PR" | "ISSUE" | "COMMIT_CLUSTER"; // COMMIT_CLUSTER를 화면에서 "PR"로 부르지 않는다
  ref: string | null;      // "#42", "#31" — COMMIT_CLUSTER는 null
  title: string;
  reason: string;          // 추천 이유 한 줄. 이걸 못 쓰면 후보 자체를 올리지 않는다
  commitCount: number;
  changedFileCount: number | null;
  reviewCount: number | null;
  status: "NEW" | "USED" | "EXCLUDED";
  lowCardWorth: boolean;   // ⚠️ 신규 — "카드감 낮음" 배지. 판정 기준(문서 변경만 등)이 아직 규칙인지 모델 판단인지 불명
}
// → ApiResponse<{ verdict: "OK" | "EMPTY"; reason?: string; items: Candidate[] }>
```

`verdict: "EMPTY"`는 실패가 아니라 판정이라 200으로 내려온다.

```
PATCH /api/candidates/{candidateId}     Request { "status": "NEW" | "EXCLUDED" }
POST  /api/candidates/confirm           Request { "candidateIds": string[] }
                                         → ApiResponse<{ cardIds: string[] }>
```

`confirm`이 후보 확정(사람 개입 1)이고, 여기서부터 코드 깊이 읽기 비용이 발생한다. 선택한 후보
개수만큼 카드 초안이 큐에 쌓이고, 프론트는 카드를 하나씩 순서대로 보여준다("카드 1/2" 표시).

## 5. (삭제됨) 활동 대시보드

v0.1에 있던 `GET /api/activities`는 삭제합니다. Figma v3에서 "활동" 개념 자체가 빠지고 카드가
화면의 기본 단위가 됐습니다. 아래 §6의 카드 목록/상세 API로 대체됩니다.

## 6. 카드

### 6-1. 목록 조회

```
GET /api/cards?kind=TECH|QUALITATIVE&status=DRAFT|CONFIRMED&query=&sort=RECENT|NEEDS_REVIEW_FIRST|NAME
```

```ts
type CardKind = "TECH" | "QUALITATIVE";
type CardStatus = "DRAFT" | "CONFIRMED";
type SourceType = "PR" | "ISSUE" | "COMMIT_CLUSTER" | "INTERVIEW";

interface CardSummary {
  id: string;
  kind: CardKind;
  title: string;
  status: CardStatus;
  needsReview: boolean;      // ⚠️ 파생값 — 아래 규칙 참고. "확인 필요" 플래그
  repoName: string;
  source: { type: SourceType; ref: string | null };
  dateLabel: string;
  evidenceCount: number | null;     // "근거 3건"
  userStatedCount: number | null;   // "내가 말한 것 2건"
  emptyFieldNote: string | null;    // "R 칸 비어 있음"
}
// → ApiResponse<{ items: CardSummary[]; totalCount: number; confirmedCount: number; organizedRepoCount: number }>
```

**`needsReview` 파생 규칙 (제안)**: `status === "DRAFT"`이면서 STAR 칸 중 `droppedFields`가 있거나
`lowConfidenceFields`가 있으면 `true`. 서버가 계산해서 내려주는 쪽을 제안합니다 — 프론트 여러
화면(홈 배너·카드 리스트·카드 상세)에서 같은 규칙을 반복 구현하면 로직이 갈라질 위험이 있습니다.

### 6-2. 상세 조회 · 되묻기 · 확정

테크스펙 `인터페이스 명세`에서 이미 확정된 부분입니다. 그대로 인용합니다.

```
GET  /api/cards/{cardId}
POST /api/cards/{cardId}/interview      Request { "field": "S" | "T" | "A" | "R" }
POST /api/cards/{cardId}/confirm        Request { "edited": boolean, "maskedFields": string[] }
```

```ts
interface StarField {
  text: string | null;              // null이면 droppedFields에 해당 — "근거를 찾지 못해 비워 두었습니다"
  needsReview: boolean;
  reviewNote: string | null;        // "수치가 없고 근거 커밋이 1건뿐입니다"
  userStated: boolean;              // 되묻기로 채운 칸인지
  evidence: { sha: string; message: string; url?: string }[];
}

interface Card {
  id: string;
  kind: CardKind;
  status: CardStatus;
  title: string;
  source: { type: SourceType; ref: string | null };
  dateLabel: string;
  star: { situation: StarField; task: StarField; action: StarField; result: StarField };
}
```

```
POST /api/cards/manual                  # 직접 입력 카드 생성 (코드에 없는 경험용)
     Request { "kind": "QUALITATIVE", "title": string, "situation": string, "task": string, "action": string, "result": string }

GET  /api/cards/{cardId}/versions       # append-only 버전 히스토리 (이전 버전 복귀용)
POST /api/cards/{cardId}/versions/{versionNo}/restore
```

## 7. 라우트 ↔ 훅 매핑

지금 mock으로 동작 중인 실제 프론트 컴포넌트 기준으로 정리했습니다.

| 화면 | 데이터 훅 | 액션 훅 | 대응 컴포넌트 |
| --- | --- | --- | --- |
| 경험정리/홈 | `useCards()` | — | [HomePage.jsx](../src/components/home/HomePage.jsx) |
| 경험 카드 | `useCards(filters)` | — | [CardsPage.jsx](../src/components/cards/CardsPage.jsx) |
| 레포 정리 · 레포 선택 | `useRepositories()` | `useAnalyzeRepository()` | [RepoSelectStep.jsx](../src/components/organize/RepoSelectStep.jsx) |
| 레포 정리 · 사전 고지 | — | — | [DisclosureStep.jsx](../src/components/organize/DisclosureStep.jsx) |
| 레포 정리 · 분석 진행 | `useJob(jobId, { refetchInterval })` | — | [AnalyzingStep.jsx](../src/components/organize/AnalyzingStep.jsx) |
| 레포 정리 · 후보 보드 | `useCandidates(repositoryId)` | `useUpdateCandidate()`, `useConfirmCandidates()` | [CandidateBoardStep.jsx](../src/components/organize/CandidateBoardStep.jsx) |
| 레포 정리 · 카드 초안 | `useCard(cardId)` | `useInterview(cardId)`, `useConfirmCard(cardId)` | [DraftStep.jsx](../src/components/organize/DraftStep.jsx), [InterviewPanel.jsx](../src/components/organize/InterviewPanel.jsx) |
| 카드 상세(읽기) | `useCard(cardId)` | — | [CardDetailModal.jsx](../src/components/modals/CardDetailModal.jsx) |
| GitHub 연결 | `useSession()` | — | [GithubSettingsPage.jsx](../src/components/settings/GithubSettingsPage.jsx) |
| 마이페이지 | `useSession()` | — | [AccountSettingsPage.jsx](../src/components/settings/AccountSettingsPage.jsx) |

현재 프론트는 React Router 없이 `App.jsx`의 `active` 상태 + `OrganizeFlow.jsx` 내부 스텝 상태로
라우팅을 흉내 내고 있습니다. 실제 React Router 도입 시 위 화면 단위를 그대로 라우트로 옮기면 됩니다
(예: `/organize/repo`, `/organize/candidates` 등 — 다만 "떠나도 됩니다"(다른 작업 하러 가기) 요구사항 때문에
분석 진행 상태는 URL이 아니라 전역 상태나 백그라운드 폴링으로 유지하는 편이 나을 수 있습니다).

## 8. 미확정 항목 정리 (백엔드 확인 필요)

| # | 항목 | 왜 막혀 있나 |
| --- | --- | --- |
| 1 | 크레딧 시스템이 아직 유효한지 | v3 화면 어디에도 크레딧 소모 UI가 없다. 마이페이지에만 남아 있는 잔재인지 확인 필요 |
| 2 | `needsReview` 계산 위치 | 서버 계산 vs 프론트 계산 — 여러 화면에서 같은 규칙을 쓰므로 서버 계산을 제안 |
| 3 | `lowCardWorth` 판정 로직 | 규칙 기반(문서 변경만 등)인지 모델이 매기는 점수인지 불명 |
| 4 | `myCommitCount` / `teamCommitCount` / `reviewCount` 신설 | GitHub API에서 팀 전체 커밋·리뷰 수를 어떻게 셀지 정의 필요 |
| 5 | 카드 버전 히스토리·복원 엔드포인트 | 테크스펙엔 "삭제되지 않는다"만 명시, 조회/복원 API는 없음 |
| 6 | "다른 작업 하러 가기" 이후 분석 상태 유지 방식 | Job 폴링을 전역(예: 알림 뱃지)으로 옮길지, 페이지 이탈 시 그냥 멈출지 |

// 경험 카드 mock 데이터.
// v3부터는 "활동" 묶음 없이 카드(기술/정성) 하나하나가 화면의 기본 단위입니다.
// 백엔드 연동 시 이 파일은 지우고 useCards 훅의 fetch로 대체하면 됩니다.
export const KIND = {
  TECH: "tech",
  QUALITATIVE: "qual",
};

export const KIND_LABEL = {
  [KIND.TECH]: "기술",
  [KIND.QUALITATIVE]: "정성",
};

export const CARD_STATUS = {
  DRAFT: "draft",
  CONFIRMED: "confirmed",
};

export const CARD_STATUS_LABEL = {
  [CARD_STATUS.DRAFT]: "작성 중",
  [CARD_STATUS.CONFIRMED]: "확정됨",
};

export const CARD_STATUS_BADGE = {
  [CARD_STATUS.DRAFT]: "bg-white text-slate-500 ring-1 ring-inset ring-slate-200",
  [CARD_STATUS.CONFIRMED]: "bg-slate-900 text-white",
};

// 카드의 근거 출처. COMMIT_CLUSTER는 화면에 "커밋 묶음"으로만 표기하고 PR이라 부르지 않습니다.
export const SOURCE_TYPE = {
  PR: "pr",
  ISSUE: "issue",
  COMMIT_CLUSTER: "commit_cluster",
  INTERVIEW: "interview",
};

export const SOURCE_LABEL = {
  [SOURCE_TYPE.PR]: (ref) => `PR ${ref}`,
  [SOURCE_TYPE.ISSUE]: (ref) => `이슈 ${ref}`,
  [SOURCE_TYPE.COMMIT_CLUSTER]: () => "커밋 묶음",
  [SOURCE_TYPE.INTERVIEW]: () => "되묻기",
};

export const MOCK_CARDS = [
  {
    id: "card-1",
    kind: KIND.TECH,
    title: "로그인 세션 처리",
    status: CARD_STATUS.CONFIRMED,
    needsReview: false,
    repoName: "auth-service",
    source: { type: SOURCE_TYPE.PR, ref: "#42" },
    dateLabel: "2024.03 - 2024.08",
    evidenceCount: 3,
    userStatedCount: null,
    emptyFieldNote: null,
    star: {
      situation: { text: "3인 팀 프로젝트에서 로그인 유지가 되지 않는 문제가 있었다", evidence: [{ sha: "a3f21c", message: "로그인 세션 처리 추가" }] },
      task: { text: null, evidence: [] },
      action: {
        text: "인증 모듈의 세션 처리를 담당했다",
        evidence: [
          { sha: "a3f21c", message: "로그인 세션 처리 추가" },
          { sha: "9c02de", message: "리프레시 토큰 검증 로직 추가" },
        ],
      },
      result: {
        text: "재로그인 요청이 줄었다",
        needsReview: true,
        reviewNote: "수치가 없고 근거 커밋이 1건뿐입니다",
        evidence: [{ sha: "7f3e9a", message: "세션 만료 로그 추가" }],
      },
    },
  },
  {
    id: "card-2",
    kind: KIND.QUALITATIVE,
    title: "결제 롤백 대응 경험",
    status: CARD_STATUS.DRAFT,
    needsReview: true,
    repoName: "auth-service",
    source: { type: SOURCE_TYPE.ISSUE, ref: "#31" },
    dateLabel: "2024.05",
    evidenceCount: null,
    userStatedCount: null,
    emptyFieldNote: "R 칸 비어 있음",
    star: {
      situation: { text: "결제 API 응답 지연으로 일부 요청이 타임아웃됐다", evidence: [{ sha: "d81ac2", message: "결제 타임아웃 재현 이슈 등록" }] },
      task: { text: "롤백 없이 재시도만 하면 이중 결제가 날 수 있는 상황이었다", evidence: [] },
      action: { text: "revert 커밋 2건으로 우선 롤백하고 원인을 분리했다", evidence: [{ sha: "e2f9a1", message: "결제 모듈 revert" }] },
      result: { text: null, evidence: [] },
    },
  },
  {
    id: "card-3",
    kind: KIND.TECH,
    title: "결제모듈 6커밋 정리",
    status: CARD_STATUS.CONFIRMED,
    needsReview: false,
    repoName: "auth-service",
    source: { type: SOURCE_TYPE.COMMIT_CLUSTER, ref: null },
    dateLabel: "2024.05.12",
    evidenceCount: 6,
    userStatedCount: null,
    emptyFieldNote: null,
    star: {
      situation: { text: "PR 없이 결제 모듈에 커밋 6개가 같은 날 몰려 있었다", evidence: [{ sha: "b7c341", message: "5.12 결제모듈 정리 시작" }] },
      task: { text: "흩어진 변경을 하나의 작업 단위로 묶어 정리해야 했다", evidence: [] },
      action: { text: "커밋을 시간대·디렉터리 기준으로 묶어 /payment 변경을 정리했다", evidence: [{ sha: "b7c341", message: "5.12 결제모듈 정리 시작" }, { sha: "0fa88d", message: "결제 검증 로직 보완" }] },
      result: { text: "결제 모듈 변경 이력을 한 번에 추적할 수 있게 됐다", evidence: [{ sha: "0fa88d", message: "결제 검증 로직 보완" }] },
    },
  },
  {
    id: "card-4",
    kind: KIND.QUALITATIVE,
    title: "팀원과 토큰 저장 위치로 갈린 경험",
    status: CARD_STATUS.CONFIRMED,
    needsReview: false,
    repoName: "auth-service",
    source: { type: SOURCE_TYPE.INTERVIEW, ref: null },
    dateLabel: "2024.04",
    evidenceCount: null,
    userStatedCount: 2,
    emptyFieldNote: null,
    star: {
      situation: { text: "토큰을 로컬스토리지에 둘지 쿠키에 둘지를 두고 팀원과 의견이 갈렸다", evidence: [], userStated: true },
      task: { text: "보안과 구현 편의 사이에서 팀이 합의할 기준이 필요했다", evidence: [] },
      action: { text: "XSS 위험을 근거로 httpOnly 쿠키를 제안하고 팀을 설득했다", evidence: [], userStated: true },
      result: { text: "팀 컨벤션으로 httpOnly 쿠키 저장이 채택됐다", evidence: [] },
    },
  },
];

export const SORT_OPTIONS = [
  { key: "latest", label: "최신순" },
  { key: "needs_review_first", label: "확인 필요 먼저" },
  { key: "name", label: "이름순" },
];

// dateLabel은 "YYYY.MM", "YYYY.MM.DD", "YYYY.MM - YYYY.MM"(범위) 형식이 섞여 있다.
// 정렬 기준으로는 범위의 끝(가장 최근) 날짜를 사용해야 실제 최신순과 맞는다.
function getLatestTimestamp(dateLabel) {
  const parts = dateLabel.split("-");
  const last = parts[parts.length - 1].trim();
  const [year, month, day] = last.split(".").map(Number);
  return new Date(year, (month || 1) - 1, day || 1).getTime();
}

export function sortCards(list, sortKey) {
  const sorted = [...list];
  switch (sortKey) {
    case "needs_review_first":
      return sorted.sort((a, b) => Number(b.needsReview) - Number(a.needsReview));
    case "name":
      return sorted.sort((a, b) => a.title.localeCompare(b.title, "ko"));
    case "latest":
    default:
      return sorted.sort((a, b) => getLatestTimestamp(b.dateLabel) - getLatestTimestamp(a.dateLabel));
  }
}

// 카드 한 장의 근거·되묻기 상태 표시용 보조 텍스트 ("근거 3건" / "내가 말한 것 2건" / "R 칸 비어 있음")
export function evidenceSummary(card) {
  if (card.emptyFieldNote) return card.emptyFieldNote;
  if (card.userStatedCount != null) return `내가 말한 것 ${card.userStatedCount}건`;
  if (card.evidenceCount != null) return `근거 ${card.evidenceCount}건`;
  return null;
}

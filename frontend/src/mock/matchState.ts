import type { GapFill, GapFillCondition, GapFillProposal, MatchDetail } from "@/api/matching";

/**
 * Mock server only: 맞춰 보기와 모자란 조건 채우기의 진행 상태.
 * 라우터가 순수 함수라 타이머를 못 쓰므로, 채우기는 '언제 무엇이 생기는지' 를 시작 시각 기준 시간표로 두고 읽는 순간의 경과 시간으로 그때까지 생긴 일만 돌려준다.
 */

/** 공고 id → 맞춰 봤을 때의 확정 카드 묶음. 카드가 바뀌면 실서버처럼 다시 맞춰 봐야 한다 */
const judged = new Map<string, string>();
const runs = new Map<string, Plan[]>();

export const GAP_FILL_POLL_MS = 1500;

type Step = GapFillCondition["steps"][number] & { at: number };
type Plan = {
  conditionId: string;
  decidedAt: number;
  decision: NonNullable<GapFillCondition["decision"]>;
  decisionReason: string;
  forced: boolean;
  steps: Step[];
  endsAt: number;
  result: NonNullable<GapFillCondition["result"]>;
  proposal: GapFillProposal | null;
};

export function markJudged(matchId: string, signature: string) {
  judged.set(matchId, signature);
}

export function isJudged(matchId: string, signature: string) {
  return judged.get(matchId) === signature;
}

export function resetMatchState() {
  judged.clear();
  runs.clear();
}

const step = (at: number, kind: string, count: number | null = null, query: string | null = null, repoName: string | null = null): Step => ({ at, kind, count, query, repoName });

/** 짧은 검색어 — 조건 글에서 영문 낱말을 하나 고르고, 없으면 기본값 */
const queryOf = (text: string, fallback: string) => text.match(/[A-Za-z][A-Za-z0-9./-]{2,}/)?.[0]?.toLowerCase() ?? fallback;

/**
 * ✕ 조건 순서대로 결말을 돌려 가며 붙인다 — 찾음(후보로 카드) · 찾지 않기로 함 · 못 찾음 · 시간 초과 · 찾음(작성 중 카드) · 찾음(레포 분석).
 * 제안이 가리키는 후보·카드·레포는 데모 자료에 있는 것이라 [진행하기] 가 실제로 이어진다.
 */
const OUTCOMES: ((start: number, condition: MatchDetail["conditions"][number]) => Plan)[] = [
  (start, c) => ({
    conditionId: c.id, decidedAt: start + 1200, decision: "SEARCH", forced: false,
    decisionReason: "응답 속도를 줄인 후보가 있어 이 조건과 관련 있어 보여요",
    steps: [step(start + 2000, "CANDIDATES_CHECKED", 12), step(start + 3000, "DRAFT_CARDS_CHECKED", 2), step(start + 4500, "GITHUB_SEARCHED", 5, queryOf(c.text, "api"))],
    endsAt: start + 5500, result: "PROPOSED",
    proposal: { action: "CREATE_CARD", candidateId: "c_51", repoId: "r_auth", title: "로그인 API 응답 속도 개선", repoName: "auth-service", reason: "응답 시간을 줄인 커밋이 이 조건의 근거가 될 수 있어요" },
  }),
  (start, c) => ({
    conditionId: c.id, decidedAt: start + 1200, decision: "SKIP", forced: false,
    decisionReason: "내 카드와 후보가 모두 다른 분야의 일이라 거리가 멀어요",
    steps: [], endsAt: start + 1200, result: "SKIPPED", proposal: null,
  }),
  (start, c) => ({
    conditionId: c.id, decidedAt: start + 1200, decision: "SEARCH", forced: false,
    decisionReason: "작성 중인 카드에 비슷한 일이 있을 수 있어요",
    steps: [step(start + 2500, "CANDIDATES_CHECKED", 12), step(start + 3500, "DRAFT_CARDS_CHECKED", 2), step(start + 5500, "GITHUB_SEARCHED", 0, queryOf(c.text, "deploy")), step(start + 7000, "GITHUB_FAILED", null, "helm")],
    endsAt: start + 8000, result: "NOT_FOUND", proposal: null,
  }),
  (start, c) => ({
    conditionId: c.id, decidedAt: start + 1200, decision: "SEARCH", forced: false,
    decisionReason: "애매하지만 넘기면 손해라 찾아봐요",
    steps: [step(start + 3000, "CANDIDATES_CHECKED", 12), step(start + 8500, "GITHUB_SEARCHED", 2, queryOf(c.text, "ci")), step(start + 9500, "OUTSIDE_REPO_FOUND", 2, null, "kbu-capstone/team-board")],
    endsAt: start + 10500, result: "TIMED_OUT", proposal: null,
  }),
  (start, c) => ({
    conditionId: c.id, decidedAt: start + 1200, decision: "SEARCH", forced: false,
    decisionReason: "작성 중인 카드 하나가 이 조건과 가까워요",
    steps: [step(start + 2500, "DRAFT_CARDS_CHECKED", 2)],
    endsAt: start + 4000, result: "PROPOSED",
    proposal: { action: "CONTINUE_CARD", cardId: "card_01", title: "로그인 세션 처리", repoName: "auth-service", reason: "남은 되묻기에서 이 조건의 일을 채울 수 있어요" },
  }),
  (start, c) => ({
    conditionId: c.id, decidedAt: start + 1200, decision: "SEARCH", forced: false,
    decisionReason: "분석하지 않은 레포의 커밋에 관련 기록이 있을 수 있어요",
    steps: [step(start + 2500, "CANDIDATES_CHECKED", 12), step(start + 5000, "GITHUB_SEARCHED", 3, queryOf(c.text, "docker"))],
    endsAt: start + 6500, result: "PROPOSED",
    proposal: { action: "ANALYZE_REPO", repoId: "r_counting", title: "side-project-new", repoName: null, reason: "커밋 메시지 3개에 이 조건과 같은 일이 적혀 있어요" },
  }),
];

/** [그래도 찾아보기] — 찾지 않기로 한 조건을 다시 찾는다. 실서버처럼 decision 과 이유는 SKIP 그대로 두고 forced 만 켠 채 작성 중 카드 하나를 제안한다 */
const forced = (start: number, c: MatchDetail["conditions"][number], previous: Plan): Plan => ({
  conditionId: c.id, decidedAt: start, decision: previous.decision, decisionReason: previous.decisionReason, forced: true,
  steps: [step(start + 1800, "CANDIDATES_CHECKED", 12), step(start + 2800, "DRAFT_CARDS_CHECKED", 2)],
  endsAt: start + 4000, result: "PROPOSED",
  proposal: { action: "CONTINUE_CARD", cardId: "card_02", title: "결제 롤백 대응 경험", repoName: "auth-service", reason: "장애에 대응한 일이 이 조건과 이어질 수 있어요" },
});

/**
 * 채우기 시작. conditionIds 가 있으면 그 조건만 다시 찾고(나머지 결과는 그대로), 없으면 ✕ 조건 전부(최대 5개)로 새 시간표를 만든다.
 * 조건 순서는 공고 조건 순서 그대로다. 다시 찾기는 실서버 규칙대로 끝난 결과(DONE)에서 SKIPPED 인 조건만 된다 — 아니면 그 이유를 돌려준다.
 */
export function startGapFill(matchId: string, conditions: MatchDetail["conditions"], now: number, conditionIds?: string[]): "OK" | "INVALID_STATE" | "INVALID_REQUEST" {
  const notMet = conditions.filter((c) => c.status === "NOT_MET");
  const current = runs.get(matchId);
  if (conditionIds) {
    if (!current || current.some((p) => now < p.endsAt)) return "INVALID_STATE";
    const skipped = (id: string) => current.find((p) => p.conditionId === id && p.result === "SKIPPED" && !p.forced);
    if (conditionIds.length === 0 || !conditionIds.every((id) => skipped(id) && notMet.some((c) => c.id === id))) return "INVALID_REQUEST";
    runs.set(matchId, current.map((plan) => {
      const condition = notMet.find((c) => c.id === plan.conditionId);
      return condition && conditionIds.includes(plan.conditionId) ? forced(now, condition, plan) : plan;
    }));
    return "OK";
  }
  runs.set(matchId, notMet.slice(0, 5).map((c, i) => OUTCOMES[i % OUTCOMES.length](now, c)));
  return "OK";
}

/** 읽는 순간까지 생긴 일만 — 결론이 난 조건은 proposal·result 가 있고, 도는 동안은 steps 가 하나씩 늘어난다 */
export function gapFillOf(matchId: string, now: number): GapFill | null {
  const plans = runs.get(matchId);
  if (!plans) return null;
  const running = plans.some((p) => now < p.endsAt);
  return {
    state: running ? "RUNNING" : "DONE",
    pollAfterMs: running ? GAP_FILL_POLL_MS : null,
    conditions: plans.map((p) => ({
      conditionId: p.conditionId,
      decision: now >= p.decidedAt ? p.decision : null,
      decisionReason: now >= p.decidedAt ? p.decisionReason : null,
      forced: p.forced,
      steps: p.steps.filter((s) => s.at <= now).map(({ at, ...s }) => { void at; return s; }),
      proposal: now >= p.endsAt ? p.proposal : null,
      result: now >= p.endsAt ? p.result : null,
    })),
  };
}

/** 결론이 난 제안 — 확정한 카드로 조건이 채워졌는지 볼 때 쓴다 */
export function proposalsOf(matchId: string, now: number) {
  return (runs.get(matchId) ?? [])
    .filter((p) => now >= p.endsAt && p.proposal)
    .map((p) => ({ conditionId: p.conditionId, proposal: p.proposal! }));
}

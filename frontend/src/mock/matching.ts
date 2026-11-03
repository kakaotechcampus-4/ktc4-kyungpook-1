import listExample from "../../docs/examples/matching/list.json";
import detailExample from "../../docs/examples/matching/detail.json";
import { MatchDetail, MatchTarget } from "@/api/matching";
import { db, cardView } from "./store";
import { Card } from "@/api/schemas";
import { applyMask } from "@/lib/mask";
import { gapFillOf, isJudged, markJudged, proposalsOf, startGapFill } from "./matchState";

/**
 * Mock server only: 응답 계약의 companyId·logoUrl. 예시 JSON 은 서버와 같은 모양을 지키는 문서라 건드리지 않고 여기서 얹는다.
 * 로고는 한 곳만 https 주소다 — 지어낸 예시 호스트라 실제로는 불러오지 못하고 화면이 이니셜로 되돌린다(이미지 요청이 실패하는 길도 데모에서 그대로 보인다).
 */
const MOCK_COMPANY: Record<string, { companyId: string; logoUrl: string | null }> = {
  [listExample.data[0].id]: { companyId: "gaon", logoUrl: "https://logo.example.com/gaon.svg" },
  [listExample.data[1].id]: { companyId: "narae", logoUrl: null },
  [listExample.data[2].id]: { companyId: "daon", logoUrl: null },
  [listExample.data[3].id]: { companyId: "raon", logoUrl: null },
  [listExample.data[4].id]: { companyId: "maru", logoUrl: null },
};
const scenario = (index: number) => {
  const example = listExample.data[index];
  return MatchTarget.parse({ ...example, ...MOCK_COMPANY[example.id] });
};

type MetBy = MatchDetail["conditions"][number]["metBy"][number];
const metByOf = (c: (typeof db.cards)[number]): MetBy | null => {
  const card = Card.parse(cardView(c));
  const reason = [card.version.action, card.version.result].find((x) => x?.trim());
  return reason
    ? {
        cardId: card.id,
        cardTitle: applyMask(card.title, card.maskRules),
        cardKind: card.kind,
        repoId: card.repo?.id ?? null,
        reason: applyMask(reason, card.maskRules),
      }
    : null;
};

/** 채우기 제안을 따라 만들거나 마저 채운 카드가 확정됐으면, 그 조건은 다시 맞춰 볼 때 그 카드로 채워진다 */
const filledBy = (id: string, now: number) => {
  const filled = new Map<string, MetBy>();
  for (const { conditionId, proposal } of proposalsOf(id, now)) {
    const card = db.cards.find((c) =>
      c.status === "CONFIRMED" &&
      (proposal.action === "CREATE_CARD" ? c.candidate?.id === proposal.candidateId : proposal.action === "CONTINUE_CARD" ? c.id === proposal.cardId : false),
    );
    const metBy = card ? metByOf(card) : null;
    if (metBy) filled.set(conditionId, metBy);
  }
  return filled;
};

/**
 * Mock server only: supplied examples are scenarios, not a client-side matching algorithm.
 * 상세는 처음엔 맞춰 보기 전(needsJudgement true)이고, 맞춰 보기(judge)를 누른 공고만 판단을 보인다 — 확정 카드가 바뀌면 실서버처럼 다시 맞춰 보기 전으로 돌아간다.
 * 맞춰 본 뒤에는 앞쪽 met 개 조건을 확정 카드의 행동·결과 문장으로 채운다.
 */
export function mockMatchDetail(id: string, judge = false): MatchDetail | null {
  const index = listExample.data.findIndex((x) => x.id === id);
  if (index < 0) return null;
  const now = Date.now();
  const target = scenario(index);
  const confirmed = db.cards.filter((c) => c.status === "CONFIRMED");
  const signature = confirmed.map((c) => c.id).sort().join(",");
  if (judge) markJudged(id, signature);
  const judged = isJudged(id, signature);
  const metBy = confirmed.flatMap((c) => metByOf(c) ?? []);
  const filled = judged ? filledBy(id, now) : new Map<string, MetBy>();
  const seed = MatchDetail.parse(detailExample.data);
  const conditions: MatchDetail["conditions"] = [];
  for (const type of ["REQUIRED", "PREFERRED"] as const) {
    const templates = seed.conditions.filter((c) => c.type === type);
    const count = type === "REQUIRED" ? target.required : target.preferred;
    for (let i = 0; i < count.total; i++) {
      const template = templates[i % templates.length];
      const conditionId = `${id}-${type}-${i}`;
      const met = i < count.met && metBy.length > 0;
      const check = template.status === "CHECK";
      const extra = filled.get(conditionId);
      conditions.push({
        ...template,
        id: conditionId,
        generalType: met ? null : template.generalType,
        status: check ? "CHECK" : !judged ? "UNJUDGED" : met ? "MET" : extra ? "MET" : "NOT_MET",
        metBy: !judged || check ? [] : met ? metBy.slice(0, 2) : extra ? [extra] : [],
      });
    }
  }
  conditions.push(
    ...seed.conditions
      .filter((c) => c.type === "RESPONSIBILITY")
      .map((c) => ({ ...c, id: `${id}-${c.id}` })),
  );
  const required = conditions.filter((c) => c.type === "REQUIRED" && c.status !== "CHECK");
  const source = {
    ...target.source,
    collectedAt: new Date().toISOString(),
    expiresAt:
      target.source.expiresAt === null
        ? null
        : new Date(Date.now() + (index + 2) * 86400000).toISOString(),
  };
  return {
    ...target,
    source,
    fit: confirmed.length ? (metBy.length ? target.fit : "D") : null,
    required: {
      ...target.required,
      met: metBy.length ? target.required.met : 0,
    },
    preferred: {
      ...target.preferred,
      met: metBy.length ? target.preferred.met : 0,
    },
    experience: {
      ...target.experience,
      quote:
        target.experience.level === "ENTRY"
          ? "신입 지원 가능"
          : target.experience.minYears === null
            ? null
            : `${target.experience.minYears}년 이상의 경력`,
      status: judged && target.experience.minYears !== null ? "UNKNOWN" : null,
    },
    needsJudgement: !judged,
    summary: judged
      ? {
          requiredMet: required.filter((c) => c.status === "MET").length,
          requiredTotal: required.length,
        }
      : null,
    conditions,
    gapFill: gapFillOf(id, now),
  };
}
/** 채우기 시작(또는 고른 조건만 다시 찾기). 맞춰 보기 전이거나 끝난 결과가 없으면 INVALID_STATE, 다시 찾을 조건이 SKIPPED 가 아니면 INVALID_REQUEST */
export function mockStartGapFill(id: string, conditionIds?: string[]): MatchDetail | "NOT_FOUND" | "INVALID_STATE" | "INVALID_REQUEST" {
  const detail = mockMatchDetail(id);
  if (!detail) return "NOT_FOUND";
  if (detail.needsJudgement) return "INVALID_STATE";
  const started = startGapFill(id, detail.conditions, Date.now(), conditionIds);
  return started === "OK" ? mockMatchDetail(id)! : started;
}
export function mockMatchList() {
  return listExample.data
    .map((x) => {
      const detail = mockMatchDetail(x.id)!;
      const { conditions, experience, needsJudgement, summary, gapFill, ...target } = detail;
      void conditions;
      void needsJudgement;
      void summary;
      void gapFill;
      const { quote, status, ...level } = experience;
      void quote;
      void status;
      return { ...target, experience: level };
    })
    .sort(
      (a, b) =>
        (a.fit ?? "Z").localeCompare(b.fit ?? "Z") ||
        (a.source.expiresAt ?? "Z").localeCompare(b.source.expiresAt ?? "Z"),
    );
}

const OVERALL_SEC = 6;
/** Mock server only: 예시 공고에서 필수 조건을 많이 채운 순서로 기업마다 대표 공고 하나씩, 5곳까지 */
function overallCompanies() {
  const seen = new Set<string>();
  const ratio = (x: { required: { met: number; total: number } }) => x.required.met / Math.max(1, x.required.total);
  return [...mockMatchList()]
    .sort((a, b) => ratio(b) - ratio(a))
    .filter((x) => { const key = x.companyId ?? x.company; if (seen.has(key)) return false; seen.add(key); return true; })
    .slice(0, 5)
    .map((x) => ({
      companyId: x.companyId, companyName: x.company, logoUrl: x.logoUrl,
      posting: { id: x.id, title: x.role, deadline: x.source.expiresAt, closed: false },
      requiredMet: x.required.met, requiredTotal: x.required.total, preferredMet: x.preferred.met, preferredTotal: x.preferred.total,
    }));
}
/**
 * Mock server only: 레포의 확정 카드가 없으면 계산한 적 없음(NONE), 있으면 기업마다 공고 하나씩 3곳.
 * 실서버는 카드 글로 AI 가 판단하므로, 그 카드 기술과 겹치는 공고를 앞에 두고 남는 자리는 다른 공고로 채운다
 */
export function mockRepoRecommendation(repoId: string) {
  const confirmed = db.cards.filter((c) => c.repo?.id === repoId && c.status === "CONFIRMED");
  if (confirmed.length === 0) return { status: "NONE", computedAt: null, companies: [] };
  const mine = new Set<string>(confirmed.flatMap((c) => (c.techs ?? []) as string[]));
  const seen = new Set<string>();
  const companies = mockMatchList()
    .map((x) => ({ x, techs: x.techs.filter((tech) => mine.has(tech)).slice(0, 3) }))
    .sort((a, b) => b.techs.length - a.techs.length)
    .filter(({ x }) => { const key = x.companyId ?? x.company; if (seen.has(key)) return false; seen.add(key); return true; })
    .slice(0, 3)
    .map(({ x, techs }) => ({
      companyId: x.companyId, companyName: x.company, logoUrl: x.logoUrl,
      posting: { id: x.id, title: x.role, deadline: x.source.expiresAt, closed: false },
      techs, requiredMet: Math.max(1, techs.length), preferredMet: 0,
    }));
  return { status: "READY", computedAt: new Date().toISOString(), companies };
}
/**
 * Mock server only: 실서버처럼 맞춰 본 공고 · 전체 매칭 결과 · 레포 추천을 합쳐, 지금 확정 카드로 필수 조건이 맞은 공고를 한 번씩 센다.
 * 맞춰 본 공고는 카드 묶음이 그때와 같을 때만 센다(카드가 바뀌면 실서버도 옛 판단을 쓰지 않는다)
 */
export function mockMatchedPostings() {
  const confirmed = db.cards.filter((c) => c.status === "CONFIRMED");
  if (confirmed.length === 0) return { matchedPostings: 0 };
  const signature = confirmed.map((c) => c.id).sort().join(",");
  const supported = confirmed.some((c) => metByOf(c) !== null);
  const ids = new Set<string>();
  listExample.data.forEach((x, index) => {
    if (supported && isJudged(x.id, signature) && scenario(index).required.met > 0) ids.add(x.id);
  });
  const overall = mockOverall();
  if (overall.status === "READY") overall.companies.forEach((c) => ids.add(c.posting.id));
  new Set(confirmed.flatMap((c) => (c.repo?.id ? [c.repo.id as string] : []))).forEach((repoId) =>
    mockRepoRecommendation(repoId).companies.forEach((c) => ids.add(c.posting.id)));
  return { matchedPostings: ids.size };
}
/** Mock server only: 실서버처럼 누르면 배경에서 기업을 하나씩 확인하고(진행 개수), 다시 계산하는 동안에는 지난 기업을 그대로 준다 */
export function mockOverall() {
  const state = db.overall;
  if (!state) return { status: "NONE", computedAt: null, stale: false, progress: null, companies: [] };
  const companies = overallCompanies();
  const elapsed = (Date.now() - state.startedAt) / 1000;
  if (elapsed < OVERALL_SEC) {
    return { status: "COMPUTING", computedAt: state.computedAt, stale: false,
      progress: { done: Math.floor((elapsed / OVERALL_SEC) * companies.length), total: companies.length },
      companies: state.computedAt ? companies : [] };
  }
  state.computedAt = new Date(state.startedAt + OVERALL_SEC * 1000).toISOString();
  return { status: "READY", computedAt: state.computedAt, stale: false, progress: null, companies };
}
/** Mock server only: 이미 계산 중이면 새로 시작하지 않는다(실서버 규칙) */
export function startMockOverall() {
  const state = db.overall;
  if (!state || (Date.now() - state.startedAt) / 1000 >= OVERALL_SEC) {
    if (state) mockOverall(); // 끝난 계산의 시각을 남겨 둔다
    db.overall = { startedAt: Date.now(), computedAt: state?.computedAt ?? null };
  }
  return mockOverall();
}

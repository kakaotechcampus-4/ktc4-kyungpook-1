import listExample from "../../docs/examples/matching/list.json";
import detailExample from "../../docs/examples/matching/detail.json";
import { MatchDetail, MatchTarget } from "@/api/matching";
import { db, cardView, FIELD_KEY, isCardBlank, STAR } from "./store";
import { Card } from "@/api/schemas";
import { applyMask } from "@/lib/mask";
import { gapFillOf, isJudged, isRefreshedRepo, markJudged, proposalsOf, repoRunStart, restartRepoRun, startGapFill } from "./matchState";
import { rankPicks, techName, type LiveHit } from "@/lib/repoPick";

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
const OVERALL_CHECKED = 15;
type NamedCondition = { text: string; techs: string[] };
/**
 * Mock server only: 공고마다 맞은·모자란 필수 조건 이름. 실서버는 공고에 적힌 조건 글(text)과 그 조건의 기술 이름(techs)을 공고 순서로 3개까지 준다.
 * 개수는 예시 공고의 필수 조건(met/total)과 맞춘다
 */
const MOCK_CONDITIONS: Record<string, { met: NamedCondition[]; missing: NamedCondition[] }> = {
  [listExample.data[0].id]: {
    met: [
      { text: "Java·Spring 서버 개발 경험", techs: ["JAVA", "SPRING"] },
      { text: "관계형 데이터베이스 설계와 쿼리 작성 경험", techs: ["POSTGRESQL"] },
      { text: "캐시를 써 본 경험", techs: ["REDIS"] },
    ],
    missing: [],
  },
  [listExample.data[1].id]: {
    met: [
      { text: "Kotlin 으로 서버를 개발해 본 경험", techs: ["KOTLIN", "SPRING"] },
      { text: "REST API 설계와 개발 경험", techs: [] },
    ],
    missing: [
      { text: "Kafka 운영 경험", techs: ["KAFKA"] },
      { text: "메시지 큐 운영 경험", techs: [] },
    ],
  },
  [listExample.data[2].id]: { met: [], missing: [] },
  [listExample.data[3].id]: {
    met: [{ text: "Kotlin 으로 앱을 개발해 본 경험", techs: ["KOTLIN"] }],
    missing: [
      { text: "Android 앱 출시 경험", techs: ["ANDROID"] },
      { text: "앱 성능 개선 경험", techs: [] },
    ],
  },
  [listExample.data[4].id]: {
    met: [],
    missing: [
      { text: "Spark 로 대용량 데이터를 처리해 본 경험", techs: ["SPARK"] },
      { text: "Airflow 로 배치를 운영해 본 경험", techs: ["AIRFLOW"] },
    ],
  },
};
/**
 * Mock server only: 예시 공고에서 필수 조건을 많이 채운 순서로 기업마다 대표 공고 하나씩, 5곳까지.
 * 채운 개수는 예시 공고에 적힌 값 그대로라(가온 3/3 · 다온 필수 없음 · 마루 0/2), 데모에서 필수를 다 채운 타일과 모자란 타일이 함께 보인다
 */
function overallCompanies() {
  const seen = new Set<string>();
  const ratio = (x: { required: { met: number; total: number } }) => x.required.met / Math.max(1, x.required.total);
  return mockMatchList()
    .map((x) => {
      const { required, preferred } = scenario(listExample.data.findIndex((example) => example.id === x.id));
      return { ...x, required, preferred };
    })
    .sort((a, b) => ratio(b) - ratio(a))
    .filter((x) => { const key = x.companyId ?? x.company; if (seen.has(key)) return false; seen.add(key); return true; })
    .slice(0, 5)
    .map((x) => ({
      companyId: x.companyId, companyName: x.company, logoUrl: x.logoUrl,
      posting: { id: x.id, title: x.role, deadline: x.source.expiresAt, closed: false },
      requiredMet: x.required.met, requiredTotal: x.required.total, preferredMet: x.preferred.met, preferredTotal: x.preferred.total,
      ...MOCK_CONDITIONS[x.id],
    }));
}
/** 처음 읽고 계산을 시작하기까지(예약) · 시작하고 첫 회사를 확인하기까지 · 회사 하나에 걸리는 시간 · 그 가운데 확인 중인 시간 */
const PICK_QUEUE = 1;
const PICK_LEAD = 0.8;
const PICK_STEP = 2.4;
const PICK_CHECK = 1.7;
/** 카드에 기술 태그가 모자라면 데모가 단조롭지 않게 더하는 기술 */
const DEMO_TECHS = ["JAVA", "SPRING", "POSTGRESQL", "REDIS", "KAFKA", "AWS"];
/** 목 공고에 없는 회사 — 후보가 6곳이 넘게 보이도록 둔 것이고, 확인은 하지만 맞는 게 없는 후보로만 쓴다 */
const FILLER = [
  { companyId: "saebyeok", companyName: "새벽마켓", title: "백엔드 개발자", days: 6, overlap: 2 },
  { companyId: "hanbit", companyName: "한빛커머스", title: "백엔드 개발자", days: 2, overlap: 1 },
  { companyId: "chaeum", companyName: "채움에듀", title: "서버 개발자", days: 11, overlap: 3 },
] as const;
/** 확인하는 순서 — 예시 공고(순번)와 위 회사를 섞는다 */
const PICK_ORDER: ReadonlyArray<readonly ["post" | "fill", number]> = [["post", 1], ["fill", 0], ["post", 0], ["fill", 1], ["post", 2], ["post", 3], ["fill", 2], ["post", 4]];
type PickCandidate = {
  companyId: string | null; companyName: string; logoUrl: string | null; posting: { id: string; title: string; deadline: string | null };
  overlap: number; hits: LiveHit[]; matched: string[];
};

/**
 * Mock server only: 레포 카드로 고르는 결과 — 시간과 상관없이 정해지는 끝난 모습이다.
 * 카드(작성 중 포함) 기술과 겹치는 예시 공고가 기술마다 조건 하나를 채운다. 실서버처럼 네 칸이 모두 빈 카드는 빼고, 남은 카드가 없으면 null.
 */
function repoPicks(repoId: string) {
  const cards = db.cards.filter((c) => {
    const version = c.versions[c.versions.length - 1];
    return c.repo?.id === repoId && STAR.some((field) => !isCardBlank(version[FIELD_KEY[field]] ?? ""));
  });
  if (cards.length === 0) return null;
  const own = [...new Set(cards.flatMap((c) => (c.techs ?? []) as string[]))];
  const techs = own.length < 3 ? [...new Set([...own, ...DEMO_TECHS])] : own;
  const mine = new Set(techs);
  const list = new Map(mockMatchList().map((x) => [x.id, x]));
  const titleFor = (tech: string, i: number) => (cards.find((c) => ((c.techs ?? []) as string[]).includes(tech)) ?? cards[i % cards.length]).title as string;
  const candidates = PICK_ORDER.map(([kind, index]): PickCandidate => {
    if (kind === "fill") {
      const f = FILLER[index];
      const deadline = new Date(Date.now() + f.days * 86400000).toISOString();
      return { companyId: f.companyId, companyName: f.companyName, logoUrl: null, posting: { id: `mock-${f.companyId}`, title: f.title, deadline }, overlap: f.overlap, hits: [], matched: [] };
    }
    const x = list.get(listExample.data[index].id)!;
    const matched = x.techs.filter((tech) => mine.has(tech));
    const hits = x.techs.flatMap((tech, i): LiveHit[] => mine.has(tech)
      ? [{ type: i < x.required.total ? "REQUIRED" : "PREFERRED", text: `${techName(tech)} 사용 경험`, cardTitle: titleFor(tech, i) }] : []);
    return { companyId: x.companyId, companyName: x.company, logoUrl: x.logoUrl, posting: { id: x.id, title: x.role, deadline: x.source.expiresAt }, overlap: hits.length, hits, matched };
  });
  const companies = rankPicks(candidates.map((c) => ({ ...c, state: "DONE" as const, failed: false }))).map((c) => {
    const found = candidates.find((x) => x.posting.id === c.posting.id)!;
    return {
      companyId: c.companyId, companyName: c.companyName, logoUrl: c.logoUrl, posting: { ...c.posting, closed: false },
      techs: found.matched.slice(0, 3),
      requiredMet: c.hits.filter((h) => h.type === "REQUIRED").length, preferredMet: c.hits.filter((h) => h.type === "PREFERRED").length,
    };
  });
  return { cards, techs, candidates, companies, signature: cards.map((c) => c.id as string).sort().join(",") };
}

/**
 * Mock server only: 레포 카드로 맞는 기업을 고르는 모습. 처음 읽은 때부터의 경과 시간으로, 1초쯤 예약(COMPUTING + queued)했다가 후보를 하나씩 확인하고(회사마다 2.4초쯤) 끝나면 READY 가 된다.
 * 그 레포의 카드가 달라지면 처음부터 다시 한다. 카드가 없으면 계산한 적 없음(NONE).
 * 고른 기업(companies)은 끝난 모습을 늘 주는데, 홈의 추천 공고가 읽는 즉시 보이게 하려는 것이다.
 */
export function mockRepoRecommendation(repoId: string) {
  const picks = repoPicks(repoId);
  if (!picks) return { status: "NONE", computedAt: null, queued: false, live: null, companies: [] };
  // 다시 고르기를 요청한 뒤에는 위 회사 하나를 지난 결과에 없던 회사로 준다
  const companies = picks.companies.map((company, index) => ({ ...company, fresh: isRefreshedRepo(repoId) && index === 0 }));
  const now = Date.now();
  const startedAt = repoRunStart(repoId, picks.signature, now);
  const elapsed = (now - startedAt) / 1000;
  if (elapsed < PICK_QUEUE) return { status: "COMPUTING", computedAt: null, queued: true, live: null, companies };
  const progress = elapsed - PICK_QUEUE;
  const starts = picks.candidates.map((_, i) => PICK_LEAD + PICK_STEP * i);
  const total = starts[starts.length - 1] + PICK_CHECK + 0.3;
  const finished = progress >= total;
  const candidates = picks.candidates.map(({ matched, ...c }, i) => {
    void matched;
    const state = progress >= starts[i] + PICK_CHECK ? "DONE" : progress >= starts[i] ? "CHECKING" : "WAITING";
    return { ...c, state, failed: false, hits: state === "DONE" ? c.hits : [] };
  });
  return {
    status: finished ? "READY" : "COMPUTING",
    computedAt: finished ? new Date(startedAt + (PICK_QUEUE + total) * 1000).toISOString() : null,
    queued: false,
    live: { cardCount: picks.cards.length, techs: picks.techs, candidates, finished },
    companies,
  };
}
/** Mock server only: 새 카드를 넣어 다시 고르기 — 계산 중 → 진행 → 끝 흉내를 처음부터 다시 시작하고, 응답은 읽기와 같다 */
export function mockRefreshRepoRecommendation(repoId: string) {
  const picks = repoPicks(repoId);
  if (picks) restartRepoRun(repoId, picks.signature, Date.now());
  return mockRepoRecommendation(repoId);
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
    repoPicks(repoId)?.companies.forEach((c) => ids.add(c.posting.id)));
  return { matchedPostings: ids.size };
}
/** Mock server only: 실서버처럼 누르면 배경에서 기업을 하나씩 확인하고(진행 개수), 다시 계산하는 동안에는 지난 기업을 그대로 준다 */
export function mockOverall() {
  const state = db.overall;
  if (!state) return { status: "NONE", computedAt: null, stale: false, progress: null, checked: 0, companies: [] };
  const companies = overallCompanies();
  const elapsed = (Date.now() - state.startedAt) / 1000;
  if (elapsed < OVERALL_SEC) {
    return { status: "COMPUTING", computedAt: state.computedAt, stale: false,
      progress: { done: Math.floor((elapsed / OVERALL_SEC) * companies.length), total: companies.length },
      checked: state.computedAt ? OVERALL_CHECKED : 0,
      companies: state.computedAt ? companies : [] };
  }
  state.computedAt = new Date(state.startedAt + OVERALL_SEC * 1000).toISOString();
  return { status: "READY", computedAt: state.computedAt, stale: false, progress: null, checked: OVERALL_CHECKED, companies };
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

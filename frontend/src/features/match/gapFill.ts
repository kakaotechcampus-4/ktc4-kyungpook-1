import { useEffect, useState } from "react";
import { useMutation, useQueries, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
import type { GapFill, GapFillCondition, GapFillProposal } from "@/api/matching";
import { endpoints } from "@/api/endpoints";
import { keys } from "@/api/keys";
import { useStartAnalysis } from "@/api/queries";
import { MAX_CARDS_PER_REQUEST } from "@/lib/candidatePicks";
import { toast } from "@/lib/toast";

export type GapStep = GapFillCondition["steps"][number];

/** AI 가 한 일 한 줄. kind 값은 서버(#652)가 정한 다섯 가지이고, 모르는 값은 일반 문장으로 보인다 */
export function stepText(step: GapStep): string {
  const n = step.count;
  const q = step.query ? `'${step.query}' 로 ` : "";
  switch (step.kind) {
    case "CANDIDATES_CHECKED":
      return n === null ? "카드로 안 만든 후보를 읽었어요" : `카드로 안 만든 후보 ${n}개를 읽었어요`;
    case "DRAFT_CARDS_CHECKED":
      return n === null ? "작성 중인 카드를 읽었어요" : `작성 중인 카드 ${n}장을 읽었어요`;
    case "GITHUB_SEARCHED":
      return n === null ? `GitHub 에서 ${q}내 커밋을 찾았어요` : `GitHub 에서 ${q}내 커밋 ${n}개를 찾았어요`;
    case "GITHUB_FAILED":
      return `GitHub 검색이 잠시 안 돼 ${q}찾는 건 건너뛰었어요`;
    case "OUTSIDE_REPO_FOUND":
      return `${step.repoName ? `${step.repoName} 는 ` : ""}다른 사람 레포라 분석할 수 없어 뺐어요${n === null ? "" : ` (커밋 ${n}개)`}`;
    default:
      return n === null ? `${q}자료를 살펴봤어요` : `${q}자료 ${n}개를 살펴봤어요`;
  }
}

export const actionLabel: Record<GapFillProposal["action"], string> = {
  CREATE_CARD: "후보로 카드 만들기",
  CONTINUE_CARD: "작성 중인 카드 마저 채우기",
  ANALYZE_REPO: "레포 분석하기",
};

/** 끝났을 때 조건 아래에 적는 결론. 찾았으면 제안이, 찾지 않기로 했으면 그 이유 줄이 대신 보여서 글이 없다 */
export const resultText: Partial<Record<NonNullable<GapFillCondition["result"]>, string>> = {
  NOT_FOUND: "채울 자료를 찾지 못했어요.",
  TIMED_OUT: "시간이 부족해 여기까지 찾았어요.",
};

/**
 * 제안의 후보 중 이미 카드로 쓰인 것 — 채우기 결과는 찾은 때 그대로 저장돼 있어, 지금 후보 보드에서 상태를 읽는다.
 * 쓰인 후보 id → 그 후보로 만든 카드 id(모르면 null)
 */
export function useUsedCandidates(proposals: GapFillProposal[]): ReadonlyMap<string, string | null> {
  const repoIds = [...new Set(proposals.filter((p) => p.action === "CREATE_CARD" && p.candidateId && p.repoId).map((p) => p.repoId!))];
  const boards = useQueries({ queries: repoIds.map((repoId) => ({ queryKey: keys.candidates(repoId), queryFn: () => endpoints.candidates(repoId) })) });
  const used = new Map<string, string | null>();
  for (const board of boards) for (const c of board.data?.candidates ?? []) if (c.status === "USED") used.set(c.id, c.usedByCardId);
  return used;
}

/** 제안을 지금 있는 API 로 넘길 수 있는지 — 후보로 카드를 만들려면 후보와 그 레포 id 가, 카드를 마저 채우려면 카드 id 가, 레포 분석에는 레포 id 가 있어야 한다 */
export const canProceed = (p: GapFillProposal) =>
  p.action === "CREATE_CARD" ? !!p.candidateId && !!p.repoId : p.action === "CONTINUE_CARD" ? !!p.cardId : !!p.repoId;

/** 조건마다 생긴 일(찾아볼지 → 한 일 → 결론)의 키. 한 번 보인 줄을 다시 숨기지 않으려고 자리 수가 아니라 키로 센다 */
export const gapKey = (conditionId: string, kind: "decision" | "step" | "result", index = 0) => `${conditionId}\u0001${kind}\u0001${index}`;
export function gapKeys(gapFill: GapFill): string[] {
  return gapFill.conditions.flatMap((c) => [
    ...(c.decision !== null ? [gapKey(c.conditionId, "decision")] : []),
    ...c.steps.map((_, i) => gapKey(c.conditionId, "step", i)),
    ...(c.result !== null ? [gapKey(c.conditionId, "result")] : []),
  ]);
}

/** 끝난 뒤 요약 — '제안 1개 · 찾지 못함 1개 · 찾지 않음 1개 · 시간 초과 1개' 에서 0 인 것은 뺀다 */
export function gapSummary(gapFill: GapFill): string {
  const count = (result: GapFillCondition["result"]) => gapFill.conditions.filter((c) => c.result === result).length;
  const parts = [
    [count("PROPOSED"), "제안"],
    [count("NOT_FOUND"), "찾지 못함"],
    [count("SKIPPED"), "찾지 않음"],
    [count("TIMED_OUT"), "시간 초과"],
  ] as const;
  const text = parts.filter(([n]) => n > 0).map(([n, label]) => `${label} ${n}개`).join(", ");
  return text || "채울 조건이 없어요";
}

/** 맞춰 보기 결과를 펼치는 간격 — 조건이 적어도 전체가 6초쯤은 되게, 많아도 1초 안팎으로 */
export const revealIntervalMs = (count: number, minTotalMs = 6000) => Math.max(900, Math.ceil(minTotalMs / Math.max(count, 1)));

/**
 * 받은 것을 차례로 펼친다. active 가 켜진 뒤에 새로 생긴 키만 intervalMs 마다 순서대로 하나씩 보이고, 켜질 때 이미 있던 키와 꺼져 있을 때는 바로 다 보인다.
 * 끝난 일을 '하는 중' 으로 꾸미지 않는다 — 받은 결과를 순서대로 보이는 것뿐이고, 한 번 보인 키는 중간에 새 일이 끼어들어도 숨기지 않는다.
 */
export function useRevealed(keys: readonly string[], intervalMs: number, active: boolean): ReadonlySet<string> {
  const [shown, setShown] = useState<ReadonlySet<string> | null>(null);
  const seeded = active && shown !== null;
  const next = seeded ? keys.find((k) => !shown.has(k)) : undefined;
  useEffect(() => {
    if (active && shown === null) setShown(new Set(keys));
  }, [active, shown, keys]);
  useEffect(() => {
    if (next === undefined) return;
    const t = setTimeout(() => setShown((prev) => new Set(prev ?? []).add(next)), intervalMs);
    return () => clearTimeout(t);
  }, [next, intervalMs]);
  return seeded ? shown : new Set(keys);
}

// [진행하기] 뒤 돌아왔는지 — 서버는 어디서 왔는지 모르니 화면이 세션 저장소에 모자랐던 조건을 적어 두고, 새 카드로 다시 맞춰 본 뒤에 지운다
// 돌아왔을 때 새 카드가 아직 작성 중이면 지우지 않는다 — 지우면 나중에 확정하고 와도 '새로 채움' 이 붙지 않는다
const returnKey = (matchId: string) => `gitory.gapfill.return:${matchId}`;
export function rememberProceeded(matchId: string, notMetIds: string[]) {
  try {
    sessionStorage.setItem(returnKey(matchId), JSON.stringify(notMetIds));
  } catch {
    /* 저장소를 못 쓰면 돌아와서 다시 맞춰 보기만 건너뛴다 */
  }
}
export function readProceeded(matchId: string): string[] | null {
  try {
    const raw = sessionStorage.getItem(returnKey(matchId));
    if (raw === null) return null;
    const ids: unknown = JSON.parse(raw);
    return Array.isArray(ids) ? ids.filter((id): id is string => typeof id === "string") : [];
  } catch {
    return null;
  }
}
export function clearProceeded(matchId: string) {
  try {
    sessionStorage.removeItem(returnKey(matchId));
  } catch {
    /* 못 지우면 다음에 돌아왔을 때 한 번 더 견준다 */
  }
}

/** 고른 제안을 지금 있는 API 로 넘길 묶음 — 후보로 카드 만들기는 레포마다 부르되, 서버가 한 번에 받는 수를 넘으면 나눠 부른다 */
export function planProceed(chosen: GapFillProposal[]) {
  const createCards = new Map<string, Set<string>>();
  const continueCards: string[] = [];
  const analyzeRepos: string[] = [];
  for (const p of chosen.filter(canProceed)) {
    if (p.action === "CREATE_CARD") createCards.set(p.repoId!, (createCards.get(p.repoId!) ?? new Set<string>()).add(p.candidateId!));
    else if (p.action === "CONTINUE_CARD") continueCards.push(p.cardId!);
    else analyzeRepos.push(p.repoId!);
  }
  return {
    createCards: [...createCards].flatMap(([repoId, ids]) => {
      const all = [...ids];
      return Array.from({ length: Math.ceil(all.length / MAX_CARDS_PER_REQUEST) }, (_, i) => ({
        repoId,
        candidateIds: all.slice(i * MAX_CARDS_PER_REQUEST, (i + 1) * MAX_CARDS_PER_REQUEST),
      }));
    }),
    continueCards: [...new Set(continueCards)],
    analyzeRepos: [...new Set(analyzeRepos)],
  };
}

export type ProceedResult = {
  created: { repoId: string; candidateIds: string[]; jobId: string; cardIds: string[] }[];
  continueCards: string[];
  started: { repoId: string; jobId: string }[];
  failed: number;
};
/** 넘긴 뒤 갈 곳 — 하나면 그 화면(카드 초안 · 되묻기 · 레포 분석)으로, 여럿이면 카드 목록에서 진행을 본다 */
export function proceedDestinations(result: ProceedResult): { path: string; state?: unknown }[] {
  return [
    ...result.created.map((g) =>
      g.cardIds.length
        ? { path: `/cards/${encodeURIComponent(g.cardIds[0])}`, state: { cardOrder: g.cardIds } }
        : { path: `/cards/repo/${encodeURIComponent(g.repoId)}` },
    ),
    ...result.continueCards.map((id) => ({ path: `/cards/${encodeURIComponent(id)}/interview` })),
    ...result.started.map((s) => ({ path: `/repos/${encodeURIComponent(s.repoId)}/run?job=${encodeURIComponent(s.jobId)}` })),
  ];
}

const fulfilled = <T>(results: PromiseSettledResult<T>[]) =>
  results.flatMap((r) => (r.status === "fulfilled" ? [r.value] : []));

/**
 * 고른 제안을 한 번에 넘긴다. 새 API 없이 카드 만들기 · 되묻기 화면 · 레포 분석 시작을 그대로 쓴다
 * 일부만 실패해도 성공한 것(만들어진 카드, 시작한 분석)은 그 화면으로 보낸다 — 쓴 후보는 USED 라 다시 눌러도 같은 카드를 만들 수 없다
 * 넘긴 것이 하나도 없을 때만 실패로 끝난다
 */
export function useProceed() {
  const qc = useQueryClient();
  const nav = useNavigate();
  const analysis = useStartAnalysis();
  return useMutation({
    mutationFn: async (chosen: GapFillProposal[]): Promise<ProceedResult> => {
      const plan = planProceed(chosen);
      const createdAll = await Promise.allSettled(
        plan.createCards.map(async (g) => ({ ...g, ...(await endpoints.createCardsFromCandidates(g.repoId, g.candidateIds)) })),
      );
      const startedAll = await Promise.allSettled(plan.analyzeRepos.map(async (repoId) => ({ repoId, jobId: (await analysis.mutateAsync(repoId)).jobId })));
      const created = fulfilled(createdAll);
      const started = fulfilled(startedAll);
      const failures = [...createdAll, ...startedAll].flatMap((r) => (r.status === "rejected" ? [r.reason] : []));
      if (failures.length > 0 && created.length + started.length + plan.continueCards.length === 0) throw failures[0];
      return { created, continueCards: plan.continueCards, started, failed: failures.length };
    },
    onSuccess: (result) => {
      void qc.invalidateQueries({ queryKey: keys.cards });
      void qc.invalidateQueries({ queryKey: keys.activeJobs });
      result.created.forEach((g) => void qc.invalidateQueries({ queryKey: keys.candidates(g.repoId) }));
      if (result.failed > 0) toast(`${result.failed}개는 넘기지 못했어요. 공고로 돌아와 다시 골라 주세요.`, { tone: "danger" });
      const to = proceedDestinations(result);
      if (to.length === 1) nav(to[0].path, { state: to[0].state });
      else nav("/cards");
    },
  });
}

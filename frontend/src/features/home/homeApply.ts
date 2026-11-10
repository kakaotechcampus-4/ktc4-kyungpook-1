import type { CoverLetterSummary } from '@/api/coverLetters';
import type { MatchDetail, OverallRecommendation } from '@/api/matching';
import type { CardSummary } from '@/api/schemas';
import { seoulDay, type HomePick } from '@/lib/homeModel';

export const MAX_PICKS = 5;
export const MAX_CONDITIONS = 5;
const URGENT_DAYS = 7;

export type ApplyItem = { pick: HomePick; isNew: boolean; daysLeft: number | null; letter: CoverLetterSummary | null };

const daysLeftOf = (deadline: string | null, now: number): number | null => {
  if (!deadline || !Number.isFinite(Date.parse(deadline))) return null;
  return seoulDay(Date.parse(deadline)) - seoulDay(now);
};

const latestLetter = (letters: readonly CoverLetterSummary[], postingId: string) =>
  letters.filter((letter) => letter.posting?.id === postingId).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))[0] ?? null;

/**
 * 새 공고, 마감 7일 안이면서 자소서가 없는 공고, 쓰던 자소서가 있는 공고, 나머지 순서이고 같은 묶음 안에서는 마감이 가까운 순이다
 * 서버가 아직 닫지 않았어도 마감일이 지난 공고는 지원할 수 없어 뺀다
 */
export function orderApplyList(picks: readonly HomePick[], newIds: ReadonlySet<string>, letters: readonly CoverLetterSummary[], now: number = Date.now()): ApplyItem[] {
  const open = picks.filter((pick) => (daysLeftOf(pick.deadline, now) ?? 0) >= 0);
  const items = open.map((pick, index) => {
    const daysLeft = daysLeftOf(pick.deadline, now);
    const letter = latestLetter(letters, pick.postingId);
    const isNew = newIds.has(pick.postingId);
    const group = isNew ? 0 : daysLeft !== null && daysLeft <= URGENT_DAYS && !letter ? 1 : letter ? 2 : 3;
    return { item: { pick, isNew, daysLeft, letter } satisfies ApplyItem, group, index };
  });
  const left = (daysLeft: number | null) => daysLeft ?? Number.POSITIVE_INFINITY;
  return items
    .sort((a, b) => a.group - b.group || left(a.item.daysLeft) - left(b.item.daysLeft) || a.index - b.index)
    .slice(0, MAX_PICKS)
    .map(({ item }) => item);
}

/** D-day 와 마감 날짜 — 마감이 없으면 상시, 7일 안이면 soon */
export function deadlineLabel(daysLeft: number | null, deadline: string | null) {
  if (daysLeft === null || !deadline) return { dday: '상시', date: '마감일 없음', soon: false };
  const date = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'long', day: 'numeric' }).format(Date.parse(deadline));
  return { dday: daysLeft <= 0 ? 'D-day' : `D-${daysLeft}`, date: `${date} 마감`, soon: daysLeft <= URGENT_DAYS };
}

export const todayLabel = (now: number = Date.now()) =>
  new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: 'long', day: 'numeric', weekday: 'long' }).format(now);

export type LetterState = 'NONE' | 'DRAFTED' | 'WRITING';
export const letterStateOf = (letter: Pick<CoverLetterSummary, 'stage'> | null): LetterState =>
  !letter ? 'NONE' : letter.stage === 'DRAFTED' ? 'DRAFTED' : 'WRITING';

/** 카드마다 진한 버튼은 하나 — 자소서가 없으면 쓰기, 초안이 있으면 보기, 그 밖은 이어 쓰기 */
export function applyAction(postingId: string, letter: Pick<CoverLetterSummary, 'id' | 'stage'> | null) {
  const state = letterStateOf(letter);
  if (!letter) return { label: '자소서 쓰기', to: `/cover-letters/new?postingId=${encodeURIComponent(postingId)}` };
  return { label: state === 'DRAFTED' ? '초안 보기' : '이어 쓰기', to: `/cover-letters/${encodeURIComponent(letter.id)}` };
}

export const statusSentence = (item: Pick<ApplyItem, 'isNew' | 'letter'>) => {
  const state = letterStateOf(item.letter);
  if (state === 'DRAFTED') return '자소서 초안이 있어요';
  if (state === 'WRITING') return '쓰던 자소서가 있어요';
  return item.isNew ? '오늘 새로 올라왔어요' : '아직 자소서가 없어요';
};

export type ConditionRow = { id: string; text: string; cardTitle: string | null };

/** 채운 조건을 먼저, 못 채운 조건을 뒤에 두고 5개까지만 보인다 — 넘치면 채운 조건 쪽을 줄여 못 채운 조건은 늘 남긴다 */
export function conditionRows(detail: Pick<MatchDetail, 'conditions'>) {
  const required = detail.conditions.filter((condition) => condition.type === 'REQUIRED' && condition.generalType === null);
  const met = required.filter((condition) => condition.metBy.length > 0)
    .map((condition): ConditionRow => ({ id: condition.id, text: condition.text, cardTitle: condition.metBy[0].cardTitle }));
  const missing = required.filter((condition) => condition.metBy.length === 0)
    .map((condition): ConditionRow => ({ id: condition.id, text: condition.text, cardTitle: null }));
  const shownMet = met.slice(0, Math.max(0, MAX_CONDITIONS - missing.length));
  return { rows: [...shownMet, ...missing.slice(0, MAX_CONDITIONS)], total: required.length, metCount: met.length, hiddenMet: met.length - shownMet.length };
}

export type SetupInput = {
  analyzedRepos: number;
  cards: readonly Pick<CardSummary, 'id' | 'status'>[];
  overallStatus: OverallRecommendation['status'] | undefined;
  matching: boolean;
};
export type SetupView = {
  /** 0 레포 정리하기 · 1 카드 확정하기 · 2 맞는 공고 받기 */
  current: 0 | 1 | 2;
  mode: 'start' | 'busy' | 'zero' | 'failed' | 'ready';
  draftCount: number;
  firstDraftId: string | null;
};

/**
 * 레포 → 카드 → 공고 순서에서 지금 어느 단계인지 고르며, 레포 없이 직접 만든 카드라도 확정했으면 공고 단계다
 * 매칭이 꺼진 배포는 두 단계가 끝나면 끝(ready)이다
 */
export function setupStep({ analyzedRepos, cards, overallStatus, matching }: SetupInput): SetupView {
  const drafts = cards.filter((card) => card.status === 'DRAFT');
  const confirmed = cards.length - drafts.length;
  const base = { draftCount: drafts.length, firstDraftId: drafts[0]?.id ?? null };
  if (confirmed === 0) return { ...base, current: analyzedRepos === 0 && drafts.length === 0 ? 0 : 1, mode: 'start' };
  if (!matching) return { ...base, current: 2, mode: 'ready' };
  if (overallStatus === 'FAILED') return { ...base, current: 2, mode: 'failed' };
  return { ...base, current: 2, mode: overallStatus === 'COMPUTING' || overallStatus === 'NONE' || overallStatus === undefined ? 'busy' : 'zero' };
}


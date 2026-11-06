import type { ActiveJob, CardSummary, RepoUpdate } from '@/api/schemas';
import type { CoverLetterSummary } from '@/api/coverLetters';
import { isCoverLetterRunning } from '@/api/coverLetters';
import type { OverallRecommendation } from '@/api/matching';
import { activeJobLink } from './jobLinks';
import { folderPath } from './cardFolders';

const DAY = 86_400_000;
const STAR = ['S', 'T', 'A', 'R'] as const;

/** 홈의 '방금, 20분 전, 어제' — 이틀이 넘으면 날짜로 적는다 */
export function agoLabel(iso: string, now: Date = new Date()): string {
  const then = new Date(iso);
  const minutes = Math.floor((now.getTime() - then.getTime()) / 60_000);
  if (!Number.isFinite(minutes)) return '';
  if (minutes < 5) return '방금';
  if (minutes < 60) return `${minutes}분 전`;
  const startOf = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const days = Math.round((startOf(now) - startOf(then)) / DAY);
  if (days <= 0) return `${Math.floor(minutes / 60)}시간 전`;
  if (days === 1) return '어제';
  return `${then.getMonth() + 1}월 ${then.getDate()}일`;
}

// ───────── 이어서 하기 ─────────
export type ContinueItem =
  | { kind: 'job'; id: string; title: string; sub: string; to: string; job: ActiveJob }
  | { kind: 'card'; id: string; title: string; sub: string; to: string; card: CardSummary }
  | { kind: 'letter'; id: string; title: string; sub: string; to: string; running: boolean };

const cardSub = (card: CardSummary) => {
  if (STAR.some((f) => card.star[f] === 'NEEDS_REVIEW')) return '확인이 필요한 칸이 있어요';
  const empty = STAR.filter((f) => card.star[f] === 'EMPTY').length;
  return empty ? `빈 칸 ${empty}개만 채우면 돼요` : '확정만 하면 돼요';
};
const LETTER_SUB: Partial<Record<CoverLetterSummary['stage'], string>> = {
  ASSIGNED: '고른 카드를 확인해 주세요', ASKED: '몇 가지만 답하면 초안을 써요', FAILED: '쓰다가 멈췄어요',
};
export const letterTitle = (letter: Pick<CoverLetterSummary, 'jobRole' | 'posting'>) =>
  [letter.posting?.company, letter.jobRole].filter(Boolean).join(' · ');

/**
 * 사람이 이어서 봐야 끝나는 일만 모은다 — 돌고 있는 작업, 확정하지 않은 카드, 초안이 아직 안 나온 자소서.
 * 초안이 나온 자소서는 여기 넣지 않는다(목록 응답만으로는 채울 곳이 남았는지 알 수 없다).
 */
export function continueItems(input: { jobs: readonly ActiveJob[]; cards: readonly CardSummary[]; letters: readonly CoverLetterSummary[] }, limit = 5): ContinueItem[] {
  // 정리는 한 번에 하나만 돌아서, 앞 정리가 돌고 있으면 기다리는 정리는 그게 끝나야 시작한다
  const analyzing = input.jobs.some((job) => job.type === 'ANALYZE' && job.state === 'RUNNING');
  const waitingSub = (job: ActiveJob) => (job.type === 'ANALYZE' && analyzing ? '앞 정리가 끝나면 시작해요' : '곧 시작해요');
  const jobs: ContinueItem[] = input.jobs.map((job) => ({
    kind: 'job', id: job.jobId, job, to: activeJobLink(job),
    title: job.repoName ?? (job.type === 'DRAFT' ? '카드 초안' : '레포 정리'),
    sub: job.state === 'QUEUED' ? waitingSub(job) : job.type === 'DRAFT' ? '카드를 쓰고 있어요' : '레포를 읽고 있어요',
  }));
  const cards: ContinueItem[] = input.cards.filter((card) => card.status === 'DRAFT')
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).slice(0, 3)
    .map((card) => ({ kind: 'card', id: card.id, card, title: card.title, sub: cardSub(card), to: `/cards/${encodeURIComponent(card.id)}` }));
  const letters: ContinueItem[] = input.letters.filter((letter) => letter.stage !== 'DRAFTED')
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).slice(0, 2)
    .map((letter) => ({
      kind: 'letter', id: letter.id, running: isCoverLetterRunning(letter.stage), to: `/cover-letters/${encodeURIComponent(letter.id)}`,
      title: `${letterTitle(letter)} 자소서`, sub: isCoverLetterRunning(letter.stage) ? '초안을 쓰고 있어요' : LETTER_SUB[letter.stage] ?? '이어서 쓸 수 있어요',
    }));
  return [...jobs, ...cards, ...letters].slice(0, limit);
}

// ───────── 새로 써 둔 카드 ─────────
export const repoUpdateText = (update: Pick<RepoUpdate, 'repoName' | 'cardCount'>) =>
  `${update.repoName}에서 새로 한 일로 카드 ${update.cardCount}장을 써 뒀어요`;

/** 매칭이 켜져 있으면 새 카드를 넣어 기업을 다시 고르는 화면, 꺼져 있으면 그 레포의 카드 폴더로 보낸다 */
export const repoUpdatePath = (update: Pick<RepoUpdate, 'repoId' | 'cardCount'>, matching: boolean) =>
  matching ? `${folderPath.repoMatch(update.repoId)}?again=1&cards=${update.cardCount}` : folderPath.repo(update.repoId);

// ───────── 최근에 한 일 ─────────
export type RecentItem =
  | { kind: 'card'; at: string; title: string; meta: string; to: string; count: number; sample: string }
  | { kind: 'match'; at: string; title: string; meta: string; to: string; company: string; met: number; total: number }
  | { kind: 'letter'; at: string; title: string; meta: string; to: string; sample: string | null };

const folderOf = (card: CardSummary) =>
  card.repo ? { key: `repo:${card.repo.id}`, name: card.repo.name, to: folderPath.repo(card.repo.id) }
    : card.activity ? { key: `activity:${card.activity.id}`, name: card.activity.name, to: folderPath.activity(card.activity.id) }
    : { key: 'direct', name: '직접 쓴 카드', to: folderPath.direct };

/** 종류가 다른 일을 시각순으로 섞되, 카드는 폴더마다 한 줄로 묶는다(카드 다섯 장이 다섯 줄을 차지하지 않게) */
export function recentItems(input: { cards: readonly CardSummary[]; letters: readonly CoverLetterSummary[]; overall?: OverallRecommendation | null }, limit = 3): RecentItem[] {
  const groups = new Map<string, { name: string; to: string; latest: string; sample: string; times: string[] }>();
  for (const card of input.cards) {
    const folder = folderOf(card);
    const group = groups.get(folder.key) ?? { name: folder.name, to: folder.to, latest: card.updatedAt, sample: card.title, times: [] };
    group.times.push(card.updatedAt);
    if (card.updatedAt > group.latest) { group.latest = card.updatedAt; group.sample = card.title; }
    groups.set(folder.key, group);
  }
  const cards: RecentItem[] = [...groups.values()].map((group) => {
    // 같은 날 손본 카드만 센다 — 지난달에 만든 카드까지 '방금 정리한 카드' 로 세지 않는다
    const count = group.times.filter((time) => Date.parse(group.latest) - Date.parse(time) < DAY).length;
    return { kind: 'card', at: group.latest, count, sample: group.sample, title: `카드 ${count}장을 정리했어요`, meta: group.name, to: group.to };
  });
  const top = input.overall?.status === 'READY' && input.overall.computedAt ? input.overall.companies[0] : undefined;
  const match: RecentItem[] = top && input.overall?.computedAt ? [{
    kind: 'match', at: input.overall.computedAt, title: '공고를 맞춰 봤어요', to: '/match',
    meta: `${top.companyName} · 필수 ${top.requiredTotal}개 중 ${top.requiredMet}개`, company: top.companyName, met: top.requiredMet, total: top.requiredTotal,
  }] : [];
  const letters: RecentItem[] = input.letters.map((letter) => ({
    kind: 'letter', at: letter.updatedAt, meta: letterTitle(letter), sample: letter.firstQuestion, to: `/cover-letters/${encodeURIComponent(letter.id)}`,
    title: letter.stage === 'DRAFTED' ? '자소서 초안을 썼어요' : isCoverLetterRunning(letter.stage) ? '자소서를 쓰고 있어요' : '자소서를 시작했어요',
  }));
  return [...cards, ...match, ...letters].sort((a, b) => b.at.localeCompare(a.at)).slice(0, limit);
}

// ───────── 다가오는 마감 ─────────
/** 홈에 보일 '나와 맞는 공고' 한 건. 전체 매칭에서 왔으면 필수 조건 수(total)를 알고, 레포 추천에서 왔으면 모른다 */
export type HomePick = { postingId: string; company: string; title: string; deadline: string | null; closed: boolean; met: number; total: number | null };
export type DeadlineItem = {
  postingId: string; company: string; title: string; daysLeft: number; met: number; total: number | null;
  letter: { id: string; done: boolean } | null;
};
export const metLabel = (item: { met: number; total: number | null }) => (item.total === null ? `필수 ${item.met}개 맞음` : `필수 ${item.total}개 중 ${item.met}개`);

/** 서울 날짜(자정 기준)의 일 번호 — 마감 시각이 밤 11시여도 그날을 0일 남음으로 센다 */
const seoulDay = (time: number) => {
  const [y, m, d] = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(time).split('-').map(Number);
  return Date.UTC(y, m - 1, d) / DAY;
};
export const daysLeftLabel = (days: number) => (days <= 0 ? '오늘 마감' : days === 1 ? '내일 마감' : `${days}일 남음`);

/** 나와 맞는 기업으로 뽑힌 공고 가운데 마감이 남은 것을 빠른 순으로 — 공고마다 자소서를 썼는지 붙인다 */
export function deadlineItems(input: { picks: readonly HomePick[]; letters: readonly CoverLetterSummary[] }, now: number = Date.now(), limit = 3): DeadlineItem[] {
  const today = seoulDay(now);
  return input.picks.flatMap((pick) => {
    if (pick.closed || !pick.deadline || !Number.isFinite(Date.parse(pick.deadline))) return [];
    const daysLeft = seoulDay(Date.parse(pick.deadline)) - today;
    if (daysLeft < 0) return [];
    const letter = input.letters.filter((item) => item.posting?.id === pick.postingId).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))[0];
    return [{
      postingId: pick.postingId, company: pick.company, title: pick.title, daysLeft, met: pick.met, total: pick.total,
      letter: letter ? { id: letter.id, done: letter.stage === 'DRAFTED' } : null,
    }];
  }).sort((a, b) => a.daysLeft - b.daysLeft).slice(0, limit);
}

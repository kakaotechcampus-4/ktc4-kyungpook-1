import type { RepoRecommendation } from '@/api/matching';
import { techLabels } from '@/lib/cardTags';

export type Live = NonNullable<RepoRecommendation['live']>;
export type LiveCandidate = Live['candidates'][number];
export type LiveHit = LiveCandidate['hits'][number];
export type PickCompany = RepoRecommendation['companies'][number];

export const techName = (tech: string) => (techLabels as Record<string, string>)[tech] ?? tech;
const hitCount = (candidate: LiveCandidate, type: LiveHit['type']) => candidate.hits.filter((hit) => hit.type === type).length;

/** 확인을 마친 회사 가운데 맞은 조건이 있는 위 3곳. 필수를 많이 채운 순, 같으면 우대, 그래도 같으면 먼저 확인한 순이다 */
export function rankPicks(candidates: readonly LiveCandidate[], limit = 3): LiveCandidate[] {
  return candidates
    .map((candidate, order) => ({ candidate, order }))
    .filter(({ candidate }) => candidate.state === 'DONE' && !candidate.failed && candidate.hits.length > 0)
    .sort((a, b) => hitCount(b.candidate, 'REQUIRED') - hitCount(a.candidate, 'REQUIRED')
      || hitCount(b.candidate, 'PREFERRED') - hitCount(a.candidate, 'PREFERRED')
      || a.order - b.order)
    .slice(0, limit)
    .map(({ candidate }) => candidate);
}

export type PickPhase = 'queued' | 'running' | 'done' | 'none' | 'failed' | 'idle';

/**
 * 예약(queued)은 지난 진행이 남아 있어도 곧 새로 고르는 것이고, 계산 중이어도 진행(live)이 없거나 이미 끝난 진행뿐이면 막 시작한 순간이라 예약과 같은 모양이다.
 * idle 은 계산한 적도 예약도 없는 레포다
 */
export function phaseOf(rec: RepoRecommendation): PickPhase {
  if (rec.queued) return 'queued';
  if (rec.status === 'COMPUTING') return rec.live && !rec.live.finished ? 'running' : 'queued';
  if (rec.status === 'FAILED') return 'failed';
  if (rec.status === 'READY') return rec.companies.length > 0 ? 'done' : 'none';
  return 'idle';
}

/** 알림에서 들어온 주소(again=1&cards=n)이고, cards 는 1 이상의 정수일 때만 쓰며 아니면 null 이라 장수를 빼고 말한다 */
export type Again = { cards: number | null };
export function againOf(params: URLSearchParams): Again | null {
  if (params.get('again') !== '1') return null;
  const raw = params.get('cards') ?? '';
  return { cards: /^\d+$/.test(raw) && Number(raw) > 0 ? Number(raw) : null };
}

export function pickHead(phase: PickPhase, { repoName, cardCount, picked, again }: { repoName: string; cardCount: number; picked: number; again?: Again | null }): { title: string; lead: string } {
  const cards = `${repoName} 레포의 카드 ${cardCount}장`;
  const added = again ? (again.cards === null ? '새 카드를 더해' : `새 카드 ${again.cards}장을 더해`) : '';
  switch (phase) {
    case 'queued': return { title: again ? `${added} 다시 고르고 있어요` : '맞는 기업을 곧 고르기 시작해요', lead: `${cards}을 지금 지원할 수 있는 공고와 곧 맞춰 봐요.` };
    case 'running': return { title: again ? `${added} 다시 고르고 있어요` : '맞는 기업을 고르고 있어요', lead: `${cards}을 지금 지원할 수 있는 공고와 맞춰 보고 있어요. 보통 1~2분 걸려요.` };
    case 'done': return { title: again ? `${added} 다시 골랐어요` : `맞는 기업 ${picked}곳을 골랐어요`, lead: `${cards}과 맞는 조건이 많은 순서예요.` };
    case 'none': return { title: '맞는 기업을 찾지 못했어요', lead: '지금 올라온 공고 가운데 이 레포의 카드와 맞는 조건이 있는 곳이 없었어요.' };
    case 'failed': return { title: '기업을 고르지 못했어요', lead: '카드를 고치거나 확정하면 다시 골라요.' };
    case 'idle': return { title: '아직 고른 기업이 없어요', lead: '이 레포의 카드로는 아직 맞는 기업을 고르지 않았어요.' };
  }
}

/** 끝난 과정을 다시 보이는 장면들. 회사마다 확인 중, 확인 끝 두 장면이 이어지고 마지막 장면은 끝난 과정 그대로다 */
export function replayFrames(live: Live): Live[] {
  const waiting = live.candidates.map((candidate): LiveCandidate => ({ ...candidate, state: 'WAITING', failed: false, hits: [] }));
  const frame = (candidates: LiveCandidate[], finished: boolean): Live => ({ ...live, candidates, finished });
  const frames = [frame(waiting, false)];
  live.candidates.forEach((candidate, i) => {
    const before = [...frames[frames.length - 1].candidates];
    frames.push(frame(before.map((item, j) => (j === i ? { ...item, state: 'CHECKING' } : item)), false));
    frames.push(frame(before.map((item, j) => (j === i ? candidate : item)), false));
  });
  frames[frames.length - 1] = live;
  return frames;
}

/** 다시 보기 장면 하나가 머무는 시간(ms). 회사 10곳이면 12초쯤 걸린다 */
export const REPLAY_FRAME_MS = 600;

export const replayHead = (repoName: string, cardCount: number) => ({
  title: '맞는 기업을 이렇게 골랐어요',
  lead: `${repoName} 레포의 카드 ${cardCount}장과 맞는 조건이 많은 회사를 찾은 과정이에요.`,
});

const replayedKey = (repoId: string, computedAt: string | null) => `repo-pick-replayed:${repoId}:${computedAt ?? ''}`;
const replayedInPage = new Set<string>();

/** 이 브라우저에서 이미 다시 본 결과인지. 저장소를 못 쓰면 이 화면을 연 동안만 기억한다 */
export function isReplayed(repoId: string, computedAt: string | null): boolean {
  const key = replayedKey(repoId, computedAt);
  try { return localStorage.getItem(key) !== null; } catch { return replayedInPage.has(key); }
}

export function markReplayed(repoId: string, computedAt: string | null) {
  const key = replayedKey(repoId, computedAt);
  try { localStorage.setItem(key, '1'); } catch { replayedInPage.add(key); }
}

// ───────── 원 그림 ─────────
/** 0 바깥(후보), 1 가운데(맞은 조건 1개), 2 안쪽(맞은 조건 2개 이상) */
export type Ring = 0 | 1 | 2;
/** 560 × 560 칸에 그릴 때의 고리 반지름 */
export const RING_RADIUS = [228, 168, 104] as const;

const ringByMet = (met: number): Ring => (met >= 2 ? 2 : met === 1 ? 1 : 0);
/** 확인하기 전이나 확인 중인 회사는 후보 자리(바깥)에 있고, 확인이 끝나야 맞은 조건 수에 따라 안쪽으로 들어온다 */
export const ringOf = (candidate: Pick<LiveCandidate, 'state' | 'hits'>): Ring => (candidate.state === 'DONE' ? ringByMet(candidate.hits.length) : 0);

export type OrbitTile = { key: string; name: string; logoUrl: string | null; state: LiveCandidate['state']; ring: Ring; miss: boolean; rank: number | null };

/** 고른 순위는 다 골랐을 때만 단다. 진행이 남아 있지 않은 결과(live 없음)는 고른 회사만 안쪽 고리들에 놓는다 */
export function orbitTiles(rec: RepoRecommendation, phase: PickPhase): OrbitTile[] {
  if (phase === 'queued' || phase === 'idle') return [];
  const rankOf = (postingId: string) => (phase === 'done' ? rec.companies.findIndex((company) => company.posting.id === postingId) + 1 || null : null);
  if (!rec.live) {
    return rec.companies.map((company): OrbitTile => ({
      key: company.posting.id, name: company.companyName, logoUrl: company.logoUrl, state: 'DONE',
      ring: ringByMet(company.requiredMet + company.preferredMet), miss: false, rank: rankOf(company.posting.id),
    }));
  }
  return rec.live.candidates.map((candidate): OrbitTile => ({
    key: candidate.posting.id, name: candidate.companyName, logoUrl: candidate.logoUrl, state: candidate.state, ring: ringOf(candidate),
    miss: candidate.state === 'DONE' && (candidate.failed || candidate.hits.length === 0), rank: rankOf(candidate.posting.id),
  }));
}

const TAU = Math.PI * 2;
/**
 * 원 그림 안에서 한 칸이 차지하는 크기. scale 은 560 칸을 실제 폭으로 줄인 비율이고, 휴대폰 폭에서는 타일과 이름이 작다.
 * 휴대폰 폭에서는 자리가 좁아 바깥 고리(아직 맞은 조건이 없는 회사)의 이름을 보이지 않는다(outerLabels 가 false)
 */
export type OrbitRef = { scale: number; logo: number; label: number; outerLabels: boolean };
/** 원 그림이 가장 클 때(가로 520px 쯤)의 한 칸 크기. 고리 사이가 좁아서 같은 방향에 놓이면 옆 고리의 이름까지 겹친다 */
export const ORBIT_REF: OrbitRef = { scale: 0.93, logo: 52, label: 80, outerLabels: true };
const TURNS = 36;
type Box = { x0: number; x1: number; y0: number; y1: number };

/** 안쪽 고리의 이름은 위쪽 반에 있을 때 로고 위에 둔다 — 로고 아래에 두면 가운데의 내 사진을 가린다 */
export const labelAbove = (ring: Ring, angle: number) => ring === 2 && Math.sin(angle) < 0;

function tileBoxes(ring: Ring, angle: number, ref: OrbitRef): Box[] {
  const r = RING_RADIUS[ring] * ref.scale;
  const x = Math.cos(angle) * r;
  const y = Math.sin(angle) * r;
  const half = ref.logo / 2;
  const logo = { x0: x - half, x1: x + half, y0: y - half, y1: y + half };
  if (ring === 0 && !ref.outerLabels) return [logo];
  const label = labelAbove(ring, angle) ? { y0: y - half - 22, y1: y - half - 4 } : { y0: y + half + 4, y1: y + half + 22 };
  return [logo, { x0: x - ref.label / 2, x1: x + ref.label / 2, ...label }];
}
const touches = (a: Box, b: Box) => a.x0 < b.x1 && b.x0 < a.x1 && a.y0 < b.y1 && b.y0 < a.y1;

/** 로고나 이름이 서로 닿는 회사 쌍의 수. angles 는 화면에 그려지는 각도다 */
export function overlapCount(rings: readonly Ring[], angles: readonly number[], ref: OrbitRef = ORBIT_REF): number {
  const boxes = rings.map((ring, i) => tileBoxes(ring, angles[i], ref));
  let count = 0;
  for (let i = 0; i < boxes.length; i += 1) for (let j = i + 1; j < boxes.length; j += 1) {
    if (boxes[i].some((a) => boxes[j].some((b) => touches(a, b)))) count += 1;
  }
  return count;
}

/**
 * 회사마다 놓일 각도(라디안, 12시 방향에서 시계 방향). 바깥 고리는 후보 순서대로 고르게 놓은 제 자리 그대로라 확인하는 동안 움직이지 않는다.
 * 가운데·안쪽 고리는 그 고리의 회사끼리 고르게 벌리고, 회사가 하나뿐이어도 시작각을 돌려 가며 옆 고리와 겹침이 가장 적은 배치를 고른다(적게 돌린 쪽이 우선).
 * 원 그림은 천천히 돌기 때문에, 겹침은 지금까지 돈 각도(spin)를 더한 화면 위의 자리로 센다.
 */
export function ringAngles(rings: readonly Ring[], spin = 0, ref: OrbitRef = ORBIT_REF): number[] {
  const n = rings.length;
  const slot = (i: number) => -Math.PI / 2 + (TAU / n) * i;
  const base = rings.map((_, i) => slot(i));
  const membersOf = (ring: Ring) => rings.flatMap((r, i) => (r === ring ? [i] : []));
  const middle = membersOf(1);
  const inner = membersOf(2);
  const turnsOf = (members: number[]) => (members.length > 0 ? Array.from({ length: TURNS }, (_, t) => t) : [0]);
  const place = (angles: number[], members: number[], turn: number) => {
    members.forEach((member, j) => { angles[member] = slot(members[0]) + (TAU / TURNS) * turn + (TAU / members.length) * j; });
  };

  let best = { score: Infinity, angles: base };
  for (const t1 of turnsOf(middle)) for (const t2 of turnsOf(inner)) {
    const angles = [...base];
    place(angles, middle, t1);
    place(angles, inner, t2);
    const score = overlapCount(rings, angles.map((angle) => angle + spin), ref) * 100 + Math.min(t1, TURNS - t1) + Math.min(t2, TURNS - t2);
    if (score < best.score) best = { score, angles };
  }
  return best.angles;
}

// ───────── 오른쪽 칸 ─────────
/** 지금까지 고른 곳 · 고른 기업의 한 줄 */
export type PickRow = {
  key: string;
  postingId: string;
  name: string;
  logoUrl: string | null;
  role: string;
  deadline: string | null;
  closed: boolean;
  required: number;
  preferred: number;
  /** 조건을 채운 카드 제목(진행이 남아 있을 때만 안다) */
  cards: string[];
  /** 진행이 남아 있지 않을 때 서버가 준 겹친 기술 */
  techs: string[];
  /** 지난 결과에 없던 회사 */
  fresh: boolean;
};

const cardTitles = (hits: readonly LiveHit[]) => [...new Set(hits.map((hit) => hit.cardTitle))].slice(0, 3);

/** 고르는 중에는 지금까지 확인한 회사의 위 3곳이 실시간으로 바뀐다 */
export const liveRows = (live: Live): PickRow[] => rankPicks(live.candidates).map((candidate) => ({
  key: candidate.posting.id, postingId: candidate.posting.id, name: candidate.companyName, logoUrl: candidate.logoUrl,
  role: candidate.posting.title, deadline: candidate.posting.deadline, closed: false,
  required: hitCount(candidate, 'REQUIRED'), preferred: hitCount(candidate, 'PREFERRED'), cards: cardTitles(candidate.hits), techs: [], fresh: false,
}));

/** 끝난 결과는 서버가 준 기업이 기준이고, 진행이 남아 있으면 그 회사의 맞은 카드 제목을 이유에 붙인다 */
export const resultRows = (companies: readonly PickCompany[], live: Live | null): PickRow[] => companies.map((company) => {
  const candidate = live?.candidates.find((item) => item.posting.id === company.posting.id);
  return {
    key: company.posting.id, postingId: company.posting.id, name: company.companyName, logoUrl: company.logoUrl,
    role: company.posting.title, deadline: company.posting.deadline, closed: company.posting.closed,
    required: company.requiredMet, preferred: company.preferredMet, cards: candidate ? cardTitles(candidate.hits) : [], techs: company.techs, fresh: company.fresh,
  };
});

export const fitLabel = (required: number, preferred: number) =>
  [required ? `필수 ${required}개` : '', preferred ? `우대 ${preferred}개` : ''].filter(Boolean).join(' · ');

/** 고른 기업 아래의 이유 한 줄 */
export function pickReason(row: Pick<PickRow, 'required' | 'preferred' | 'cards' | 'techs'>): string {
  const fit = fitLabel(row.required, row.preferred);
  const head = fit ? `${fit} 조건이 맞아요.` : '맞는 조건이 있어요.';
  if (row.cards.length > 0) return `${head} ${row.cards.map((title) => `「${title}」`).join(' ')} 카드로 채웠어요.`;
  return row.techs.length > 0 ? `${head} 카드의 ${row.techs.map(techName).join(', ')} 기술로 채웠어요.` : head;
}

export type LogLine =
  | { key: string; kind: 'techs'; cardCount: number; techs: string[] }
  | { key: string; kind: 'found'; count: number }
  | { key: string; kind: 'checking'; candidate: LiveCandidate }
  | { key: string; kind: 'hit'; candidate: LiveCandidate; hit: LiveHit }
  | { key: string; kind: 'miss'; candidate: LiveCandidate; why: 'NO_MATCH' | 'NO_OVERLAP' | 'FAILED' }
  | { key: string; kind: 'picked'; count: number };

/** 진행을 읽은 순서대로 한 줄씩. 확인 중인 회사의 줄은 확인이 끝나면 맞은 조건 줄(또는 맞는 게 없다는 줄)로 바뀐다 */
export function logLines(live: Live): LogLine[] {
  const lines: LogLine[] = [];
  if (live.techs.length > 0) lines.push({ key: 'techs', kind: 'techs', cardCount: live.cardCount, techs: live.techs });
  if (live.candidates.length > 0) lines.push({ key: 'found', kind: 'found', count: live.candidates.length });
  for (const candidate of live.candidates) {
    const id = candidate.posting.id;
    if (candidate.state === 'CHECKING') lines.push({ key: `checking:${id}`, kind: 'checking', candidate });
    if (candidate.state !== 'DONE') continue;
    if (candidate.failed) lines.push({ key: `miss:${id}`, kind: 'miss', candidate, why: 'FAILED' });
    else if (candidate.hits.length === 0) lines.push({ key: `miss:${id}`, kind: 'miss', candidate, why: candidate.overlap === 0 ? 'NO_OVERLAP' : 'NO_MATCH' });
    else candidate.hits.forEach((hit, i) => lines.push({ key: `hit:${id}:${i}`, kind: 'hit', candidate, hit }));
  }
  if (live.finished) lines.push({ key: 'picked', kind: 'picked', count: rankPicks(live.candidates).length });
  return lines;
}

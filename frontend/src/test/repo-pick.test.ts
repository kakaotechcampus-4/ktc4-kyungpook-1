import { describe, expect, it } from 'vitest';
import { RepoRecommendation } from '@/api/matching';
import { labelAbove, logLines, overlapCount, phaseOf, rankPicks, ringAngles, ringOf, type Live, type LiveCandidate } from '@/lib/repoPick';

const hits = (required: number, preferred: number): LiveCandidate['hits'] => [
  ...Array.from({ length: required }, (_, i) => ({ type: 'REQUIRED' as const, text: `필수 조건 ${i + 1}`, cardTitle: `카드 ${i + 1}` })),
  ...Array.from({ length: preferred }, (_, i) => ({ type: 'PREFERRED' as const, text: `우대 조건 ${i + 1}`, cardTitle: `카드 ${i + 1}` })),
];
const candidate = (id: string, over: Partial<LiveCandidate> = {}): LiveCandidate => ({
  companyId: `co_${id}`, companyName: `회사 ${id}`, logoUrl: null, posting: { id: `p_${id}`, title: '백엔드 개발자', deadline: null },
  overlap: 2, state: 'DONE', failed: false, hits: [], ...over,
});
const live = (candidates: LiveCandidate[], over: Partial<Live> = {}): Live => ({ cardCount: 5, techs: ['SPRING', 'JPA'], candidates, finished: false, ...over });
const ids = (list: LiveCandidate[]) => list.map((item) => item.posting.id);

describe('맞는 기업 순위', () => {
  it('필수 조건을 많이 채운 회사가 먼저고, 같으면 우대, 그래도 같으면 먼저 확인한 회사다', () => {
    const ranked = rankPicks([
      candidate('a', { hits: hits(1, 0) }),
      candidate('b', { hits: hits(1, 2) }),
      candidate('c', { hits: hits(2, 0) }),
      candidate('d', { hits: hits(1, 2) }),
    ]);

    expect(ids(ranked)).toEqual(['p_c', 'p_b', 'p_d']);
  });

  it('확인을 마친 회사 가운데 맞은 조건이 있는 곳만 순위에 든다', () => {
    const ranked = rankPicks([
      candidate('checking', { state: 'CHECKING', hits: hits(3, 0) }),
      candidate('waiting', { state: 'WAITING' }),
      candidate('none', { hits: [] }),
      candidate('failed', { failed: true, hits: [] }),
      candidate('one', { hits: hits(1, 0) }),
    ]);

    expect(ids(ranked)).toEqual(['p_one']);
  });
});

describe('원 그림의 고리', () => {
  it('맞은 조건이 2개 이상이면 안쪽, 1개면 가운데, 없으면 바깥 고리다', () => {
    expect(ringOf(candidate('a', { hits: hits(1, 1) }))).toBe(2);
    expect(ringOf(candidate('b', { hits: hits(1, 0) }))).toBe(1);
    expect(ringOf(candidate('c', { hits: [] }))).toBe(0);
    expect(ringOf(candidate('d', { state: 'CHECKING' }))).toBe(0); // 확인이 끝나기 전에는 후보 자리다
  });

  it('같은 안쪽 고리의 회사들은 고르게 벌어지고, 바깥 고리의 회사는 후보 순서대로 제 자리에 있다', () => {
    const rings = [0, 2, 0, 2, 0, 2, 0, 0] as const;
    const angles = ringAngles(rings);
    const full = Math.PI * 2;
    const slot = (i: number) => -Math.PI / 2 + (full / rings.length) * i;

    for (const i of [0, 2, 4, 6, 7]) expect(angles[i]).toBeCloseTo(slot(i), 8);
    const gaps = [angles[3] - angles[1], angles[5] - angles[3], angles[1] + full - angles[5]].map((gap) => ((gap % full) + full) % full);
    for (const gap of gaps) expect(gap).toBeCloseTo(full / 3, 8);
  });

  it('안쪽 고리의 이름은 위쪽 반에 있을 때만 로고 위에 놓여 가운데 사진을 가리지 않는다', () => {
    const top = -Math.PI / 2;
    expect(labelAbove(2, top)).toBe(true);
    expect(labelAbove(2, -top)).toBe(false); // 아래쪽 반에서는 로고 아래가 이미 가운데 반대쪽이다
    expect(labelAbove(1, top)).toBe(false);
    expect(labelAbove(0, top)).toBe(false);
  });

  it('안쪽·가운데 고리에 회사가 하나씩만 있어도 서로 닿지 않는 자리로 돌려 놓는다', () => {
    const spins = Array.from({ length: 6 }, (_, i) => (Math.PI * 2 * i) / 6);
    const others = [0, 1, 2, 3, 4, 5, 6, 7];
    let worst = 0;
    for (const inner of others) for (const middle of others) {
      if (inner === middle) continue;
      const rings = others.map((i) => (i === inner ? 2 : i === middle ? 1 : 0));
      for (const spin of spins) worst = Math.max(worst, overlapCount(rings, ringAngles(rings, spin).map((angle) => angle + spin)));
    }
    expect(worst).toBe(0);
  });

  it('원 그림이 돈 뒤에는 돈 각도의 화면에서 겹치지 않는 자리를 고른다', () => {
    const rings = [0, 2, 0, 2, 0, 1, 0, 0] as const;
    const spin = Math.PI / 2;
    const shown = (angles: number[]) => overlapCount(rings, angles.map((angle) => angle + spin));

    expect(shown(ringAngles(rings))).toBe(1); // 돌기 전 기준으로 고른 자리는 90도 돈 화면에서 한 쌍이 겹친다
    expect(shown(ringAngles(rings, spin))).toBe(0);
  });

  it('휴대폰 폭에서는 보이지 않는 바깥 고리의 이름을 겹침에 세지 않는다', () => {
    const small = { scale: 0.575, logo: 44, label: 64, outerLabels: false };
    const top = -Math.PI / 2;

    expect(overlapCount([0, 2], [top, top], { ...small, outerLabels: true })).toBe(1); // 바깥 회사의 이름이 안쪽 회사의 이름에 닿는다
    expect(overlapCount([0, 2], [top, top], small)).toBe(0);
  });
});

describe('로그 줄', () => {
  it('로그는 읽은 기술, 찾은 회사 수, 회사마다 맞은 조건이나 맞는 게 없다는 줄, 끝나면 고른 수를 차례로 담는다', () => {
    const kinds = (lines: ReturnType<typeof logLines>) => lines.map((line) =>
      line.kind === 'hit' ? `hit:${line.hit.type}` : line.kind === 'miss' ? `miss:${line.why}` : line.kind);
    const checked = [
      candidate('a', { hits: hits(1, 1) }),
      candidate('b', { overlap: 2, hits: [] }),
      candidate('c', { overlap: 0, hits: [] }),
      candidate('d', { failed: true }),
    ];

    const running = logLines(live([...checked, candidate('e', { state: 'CHECKING' }), candidate('f', { state: 'WAITING' })]));
    expect(kinds(running)).toEqual(['techs', 'found', 'hit:REQUIRED', 'hit:PREFERRED', 'miss:NO_MATCH', 'miss:NO_OVERLAP', 'miss:FAILED', 'checking']);
    expect(running[0]).toMatchObject({ cardCount: 5, techs: ['SPRING', 'JPA'] });
    expect(running[1]).toMatchObject({ count: 6 });

    const done = logLines(live(checked, { finished: true }));
    expect(kinds(done).at(-1)).toBe('picked');
    expect(done.at(-1)).toMatchObject({ count: 1 });
  });
});

describe('화면 상태 판정', () => {
  const rec = (over: Record<string, unknown> = {}) => RepoRecommendation.parse({ status: 'COMPUTING', computedAt: null, queued: false, companies: [], live: null, ...over });
  const company = { companyId: 'co_1', companyName: '회사 1', logoUrl: null, posting: { id: 'p_1', title: '공고', deadline: null, closed: false }, techs: [], requiredMet: 1, preferredMet: 0 };
  const running = live([candidate('a', { state: 'CHECKING' })]);
  const finished = live([candidate('a', { hits: hits(1, 0) })], { finished: true });

  it('예약은 지난 진행이 남아 있어도 예약이고, 계산 중이어도 진행이 없거나 끝난 진행뿐이면 막 시작한 순간이라 예약과 같다', () => {
    expect(phaseOf(rec({ queued: true, live: finished }))).toBe('queued');
    expect(phaseOf(rec({ queued: true, status: 'READY', companies: [company] }))).toBe('queued');
    expect(phaseOf(rec({ live: null }))).toBe('queued');
    expect(phaseOf(rec({ live: finished }))).toBe('queued');
  });

  it('계산 중이고 끝나지 않은 진행이 있으면 고르는 중이고, 끝난 뒤에는 고른 기업이 있는지, 실패했는지, 계산한 적이 있는지로 가른다', () => {
    expect(phaseOf(rec({ live: running }))).toBe('running');
    expect(phaseOf(rec({ status: 'READY', companies: [company], live: finished }))).toBe('done');
    expect(phaseOf(rec({ status: 'READY', companies: [] }))).toBe('none');
    expect(phaseOf(rec({ status: 'FAILED', companies: [company] }))).toBe('failed');
    expect(phaseOf(rec({ status: 'NONE' }))).toBe('idle');
  });
});

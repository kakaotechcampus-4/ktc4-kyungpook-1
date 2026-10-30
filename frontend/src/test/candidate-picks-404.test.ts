import { describe, expect, it } from 'vitest';
import type { Candidate } from '@/api/schemas';
import { addTopRecommended, MAX_CARDS_PER_REQUEST, RECOMMENDED_LIMIT, splitRecommended } from '@/lib/candidatePicks';

const candidate = (id: string, score: number, weak = false, status: Candidate['status'] = 'NEW') =>
  ({ id, score, weak, status }) as Candidate;

describe('#404 후보 추천 15개와 한 번에 8개', () => {
  it('추천도 낮음이 아닌 후보 중 점수 높은 15개만 추천이고, 나머지 추천 후보 뒤에 추천도 낮은 후보가 더 보기로 간다', () => {
    const strong = Array.from({ length: 18 }, (_, i) => candidate(`s${i}`, i / 100));
    const weak = [candidate('w0', 0.99, true)];

    const { recommended, more } = splitRecommended([...weak, ...strong]);

    expect(recommended).toHaveLength(RECOMMENDED_LIMIT);
    expect(recommended[0].id).toBe('s17');
    expect(recommended.map((c) => c.id)).not.toContain('w0');
    expect(more.map((c) => c.id)).toEqual(['s2', 's1', 's0', 'w0']);
  });

  it('추천 후보 선택은 아직 고르지 않은 추천 후보를 점수 순으로, 고른 수가 8개가 될 때까지만 더한다', () => {
    const list = [candidate('a', 0.9), candidate('b', 0.8), candidate('c', 0.7), candidate('weak', 0.95, true),
      candidate('used', 0.99, false, 'USED'), ...Array.from({ length: 10 }, (_, i) => candidate(`x${i}`, 0.5 - i / 100))];

    const picked = addTopRecommended(new Set(['b']), 1, list);

    expect(picked.size).toBe(MAX_CARDS_PER_REQUEST);
    expect([...picked]).toEqual(['b', 'a', 'c', 'x0', 'x1', 'x2', 'x3', 'x4']);
  });

  it('이미 8개를 골랐으면 더하지 않는다', () => {
    const selected = new Set(Array.from({ length: 8 }, (_, i) => `x${i}`));

    expect(addTopRecommended(selected, 8, [candidate('a', 0.9)])).toBe(selected);
  });
});

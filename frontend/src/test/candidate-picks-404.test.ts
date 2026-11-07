import { describe, expect, it } from 'vitest';
import type { Candidate } from '@/api/schemas';
import { RECOMMENDED_LIMIT, splitRecommended } from '@/lib/candidatePicks';

const candidate = (id: string, score: number, weak = false, status: Candidate['status'] = 'NEW') =>
  ({ id, score, weak, status }) as Candidate;

describe('#753 후보 추천 5개와 나머지', () => {
  it('추천도 낮음이 아닌 후보 중 점수 높은 5개만 추천이고, 나머지 추천 후보 뒤에 추천도 낮은 후보가 더 보기로 간다', () => {
    const strong = Array.from({ length: 8 }, (_, i) => candidate(`s${i}`, i / 100));
    const weak = [candidate('w0', 0.99, true)];

    const { recommended, more } = splitRecommended([...weak, ...strong]);

    expect(recommended).toHaveLength(RECOMMENDED_LIMIT);
    expect(recommended.map((c) => c.id)).toEqual(['s7', 's6', 's5', 's4', 's3']);
    expect(more.map((c) => c.id)).toEqual(['s2', 's1', 's0', 'w0']);
  });

  it('추천도 낮은 후보뿐이면 그중 점수 높은 5개가 추천이다', () => {
    const weak = Array.from({ length: 7 }, (_, i) => candidate(`w${i}`, i / 100, true));

    const { recommended, more } = splitRecommended(weak);

    expect(recommended.map((c) => c.id)).toEqual(['w6', 'w5', 'w4', 'w3', 'w2']);
    expect(more.map((c) => c.id)).toEqual(['w1', 'w0']);
  });
});

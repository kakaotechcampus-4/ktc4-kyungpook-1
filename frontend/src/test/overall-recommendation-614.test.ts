import { describe, expect, it } from 'vitest';
import { OverallRecommendation } from '@/api/matching';

const company = (extra: Record<string, unknown> = {}) => ({
  companyId: 'toss', companyName: '토스', logoUrl: 'https://static.toss.im/logo.png',
  posting: { id: 'p_1', title: '서버 개발자', deadline: null, closed: false },
  requiredMet: 3, requiredTotal: 4, preferredMet: 1, preferredTotal: 3, ...extra,
});

describe('#614 overall recommendation contract', () => {
  it('reads a computing result with its progress and keeps the last companies with required counts', () => {
    const parsed = OverallRecommendation.parse({
      status: 'COMPUTING', computedAt: '2026-10-31T12:00:00Z', stale: false,
      progress: { done: 6, total: 15 }, companies: [company(), company({ companyId: 'kakao', logoUrl: 'javascript:alert(1)' })],
    });
    expect(parsed.progress).toEqual({ done: 6, total: 15 });
    expect(parsed.companies[0]).toMatchObject({ requiredMet: 3, requiredTotal: 4, preferredMet: 1, preferredTotal: 3 });
    expect(parsed.companies[1].logoUrl).toBeNull();
  });

  it('reads a never computed user as NONE without progress, and a changed input as stale', () => {
    expect(OverallRecommendation.parse({ status: 'NONE', computedAt: null, stale: false, progress: null, companies: [] }))
      .toEqual({ status: 'NONE', computedAt: null, stale: false, progress: null, companies: [] });
    expect(OverallRecommendation.parse({
      status: 'READY', computedAt: '2026-10-31T12:00:00Z', stale: true, progress: null, companies: [company()],
    }).stale).toBe(true);
  });
});

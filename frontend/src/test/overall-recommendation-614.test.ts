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
      .toEqual({ status: 'NONE', computedAt: null, stale: false, progress: null, checked: 0, companies: [] });
    expect(OverallRecommendation.parse({
      status: 'READY', computedAt: '2026-10-31T12:00:00Z', stale: true, progress: null, companies: [company()],
    }).stale).toBe(true);
  });

  it('reads the checked count and the named met/missing conditions, and fills empty values when an older server leaves them out', () => {
    const named = OverallRecommendation.parse({
      status: 'READY', computedAt: '2026-11-04T01:00:00Z', stale: false, progress: null, checked: 15,
      companies: [company({ met: [{ text: 'Java 서버 개발 경험', techs: ['JAVA'] }], missing: [{ text: '메시지 큐 운영 경험', techs: [] }] })],
    });
    expect(named.checked).toBe(15);
    expect(named.companies[0].met).toEqual([{ text: 'Java 서버 개발 경험', techs: ['JAVA'] }]);
    expect(named.companies[0].missing).toEqual([{ text: '메시지 큐 운영 경험', techs: [] }]);

    const older = OverallRecommendation.parse({
      status: 'READY', computedAt: '2026-11-04T01:00:00Z', stale: false, progress: null, companies: [company()],
    });
    expect(older.checked).toBe(0);
    expect(older.companies[0]).toMatchObject({ met: [], missing: [] });
  });
});

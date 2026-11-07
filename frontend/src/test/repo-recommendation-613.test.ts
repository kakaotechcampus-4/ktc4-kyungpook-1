import { describe, expect, it } from 'vitest';
import { RepoRecommendation } from '@/api/matching';

const company = (extra: Record<string, unknown> = {}) => ({
  companyId: 'toss', companyName: '토스', logoUrl: 'https://static.toss.im/logo.png',
  posting: { id: 'p_1', title: '서버 개발자', deadline: '2026-11-30T14:59:59Z', closed: false },
  techs: ['JAVA', 'SPRING'], requiredMet: 2, preferredMet: 1, ...extra,
});

describe('#613 repo recommendation contract', () => {
  it('reads a computed list and turns a missing or non-https logo into null (screen shows initials)', () => {
    const parsed = RepoRecommendation.parse({
      status: 'READY', computedAt: '2026-10-31T12:00:00Z',
      companies: [company(), company({ companyId: '', logoUrl: 'http://insecure.example/logo.png' })],
    });
    expect(parsed.companies[0]).toMatchObject({ companyId: 'toss', logoUrl: 'https://static.toss.im/logo.png', requiredMet: 2 });
    expect(parsed.companies[1]).toMatchObject({ companyId: null, logoUrl: null });
  });

  it('reads a repo that was never computed as NONE with no time and no companies', () => {
    expect(RepoRecommendation.parse({ status: 'NONE', computedAt: null, companies: [] }))
      .toEqual({ status: 'NONE', computedAt: null, companies: [], queued: false, live: null });
  });

  it('진행 칸(live)과 예약 칸(queued)을 읽고, 없거나 모양이 다르면 지난 결과만 읽는다', () => {
    const live = {
      cardCount: 3, techs: ['SPRING'], finished: false,
      candidates: [{ companyId: 'toss', companyName: '토스', logoUrl: null, posting: { id: 'p_1', title: '서버 개발자', deadline: null }, overlap: 2, state: 'CHECKING', failed: false, hits: [] }],
    };
    const parsed = RepoRecommendation.parse({ status: 'COMPUTING', computedAt: null, queued: true, companies: [], live });
    expect(parsed.queued).toBe(true);
    expect(parsed.live?.candidates[0]).toMatchObject({ companyId: 'toss', state: 'CHECKING', hits: [] });

    const broken = RepoRecommendation.parse({ status: 'READY', computedAt: '2026-10-31T12:00:00Z', companies: [company()], queued: 'yes', live: { cardCount: 'many' } });
    expect(broken).toMatchObject({ status: 'READY', queued: false, live: null });
    expect(broken.companies).toHaveLength(1);
  });
});

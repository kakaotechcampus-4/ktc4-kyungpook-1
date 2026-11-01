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
      .toEqual({ status: 'NONE', computedAt: null, companies: [] });
  });
});

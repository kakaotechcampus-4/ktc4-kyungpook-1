import { describe, expect, it } from 'vitest';
import { CardSummary } from '@/api/schemas';
import { filledCount, groupCards, hasRepoInfo } from '@/lib/cardGroups';

const card = (id: string, over: Record<string, unknown> = {}) => CardSummary.parse({
  id, kind: 'TECH', status: 'DRAFT', title: id, versionNo: 1, areas: [], techs: [],
  star: { S: 'FILLED', T: 'EMPTY', A: 'FILLED', R: 'NEEDS_REVIEW' }, updatedAt: '2026-10-20T00:00:00Z', ...over,
});
const auth = { id: 'r_auth', owner: 'hong-dev', name: 'auth-service' };
const web = { id: 'r_web', owner: 'hong-dev', name: 'web-client' };

describe('카드 묶어 보기', () => {
  it('레포별: 최근에 손댄 레포가 위, 직접 쓴 카드는 맨 아래, 묶음 안은 최근순', () => {
    const groups = groupCards([
      card('a1', { repo: auth, updatedAt: '2026-10-01T00:00:00Z' }),
      card('m1', { repo: null, updatedAt: '2026-10-25T00:00:00Z' }),
      card('w1', { repo: web, updatedAt: '2026-10-10T00:00:00Z' }),
      card('a2', { repo: auth, updatedAt: '2026-10-05T00:00:00Z' }),
    ], 'repo');
    expect(groups.map((g) => g.title)).toEqual(['hong-dev / web-client', 'hong-dev / auth-service', '직접 쓴 카드']);
    expect(groups[1].cards.map((c) => c.id)).toEqual(['a2', 'a1']);
    expect(groups[1].to).toBe('/repos/r_auth/candidates');
  });

  it('최근순: 수정한 달로 묶고 최신 달이 위', () => {
    const groups = groupCards([card('x', { updatedAt: '2026-09-30T10:00:00Z' }), card('y', { updatedAt: '2026-10-02T10:00:00Z' })], 'recent');
    expect(groups.map((g) => g.title)).toEqual(['2026년 10월', '2026년 9월']);
  });

  it('기술별: 여러 기술이면 여러 묶음에, 기술이 없으면 "기술 정보 없음"(지어내지 않는다)', () => {
    const groups = groupCards([card('j', { techs: ['JAVA', 'DOCKER'] }), card('n')], 'tech');
    expect(groups.map((g) => g.title)).toEqual(['Java', 'Docker', '기술 정보 없음']);
  });

  it('레포 정보가 없는 이전 서버(필드 생략)와 직접 작성(null)을 구분한다', () => {
    expect(hasRepoInfo([card('old')])).toBe(false);
    expect(hasRepoInfo([card('manual', { repo: null })])).toBe(true);
  });

  it('채운 칸은 EMPTY 가 아닌 칸 수', () => {
    expect(filledCount(card('f'))).toBe(3);
  });
});

import { describe, expect, it } from 'vitest';
import { CardSummary } from '@/api/schemas';
import { filledCount, groupCards, hasRepoInfo } from '@/lib/cardGroups';

const card = (id: string, over: Record<string, unknown> = {}) => CardSummary.parse({
  id, kind: 'TECH', status: 'DRAFT', title: id, versionNo: 1, areas: [], techs: [],
  star: { S: 'FILLED', T: 'EMPTY', A: 'FILLED', R: 'NEEDS_REVIEW' }, updatedAt: '2026-10-20T00:00:00Z', ...over,
});

describe('카드 묶어 보기', () => {
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

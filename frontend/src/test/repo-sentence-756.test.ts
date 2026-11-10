import { describe, expect, it } from 'vitest';
import type { RepoSummary } from '@/api/schemas';
import { repoMeta, repoPeriod, repoSentence } from '@/lib/repoSentence';

const repo = (over: Partial<RepoSummary> = {}): RepoSummary => ({
  id: 'r1', owner: 'kakaotechcampus-4', name: 'ktc4-kyungpook-1',
  contribution: { mine: 581, team: 597, ratio: 0.97, level: 'MAJOR' },
  prCount: 257, reviewCount: 92, language: 'Java', activeFrom: '2026-08-03', activeTo: '2026-11-02',
  lastAnalyzedAt: null, countedAt: '2026-11-03T00:00:00Z', candidateCount: null, cardCount: 0, recommended: true,
  ...over,
});
const text = (r: RepoSummary) => { const s = repoSentence(r); return `${s.before}${s.caution ?? ''}${s.after ?? ''}`; };

/** #756 레포 줄의 숫자는 한 문장 안에 넣는다 — 갈래마다 문장 하나 */
describe('repoSentence', () => {
  it('못 센 레포는 숫자 없이 세는 중이라고만 말한다', () => {
    const s = repoSentence(repo({ countedAt: null, contribution: { mine: 0, team: 0, ratio: 0, level: 'NONE' }, prCount: 0, reviewCount: 0 }));
    expect(s.before).toBe('기여 개수를 세는 중이에요');
    expect(s.caution).toBeUndefined();
    expect(s.before).not.toMatch(/\d개|PR/);
    expect(s).not.toHaveProperty('hint');
  });

  it('기여가 적으면 팀 커밋 중 내 커밋 수를 말하고 내 커밋 수만 호박색 자리에 둔다', () => {
    const s = repoSentence(repo({ contribution: { mine: 13, team: 116, ratio: 0.11, level: 'PARTIAL' }, prCount: 4 }));
    expect(text(repo({ contribution: { mine: 13, team: 116, ratio: 0.11, level: 'PARTIAL' }, prCount: 4 }))).toBe('팀 커밋 116개 중 내 커밋이 13개라 확인되는 일이 적어요');
    expect(s.caution).toBe('내 커밋이 13개');
    expect(s).not.toHaveProperty('hint');
  });

  it('PR 이 없으면 커밋을 묶어 후보를 만든다고 말한다', () => {
    expect(text(repo({ prCount: 0, reviewCount: 0, contribution: { mine: 40, team: 40, ratio: 1, level: 'MAJOR' } }))).toBe('PR 없이 커밋 40개를 올린 레포라 커밋을 묶어 후보를 만들어요');
  });

  it('PR 과 리뷰가 있으면 기간·PR 수·리뷰 수를 한 문장에 넣는다', () => {
    expect(text(repo())).toBe('8월부터 11월까지 PR 257개를 올리고 리뷰를 92번 주고받은 레포예요');
  });

  it('리뷰가 없으면 PR 수만 말한다', () => {
    expect(text(repo({ prCount: 12, reviewCount: 0 }))).toBe('8월부터 11월까지 PR 12개를 올린 레포예요');
  });

  it('기간이 한 달 안이면 그 달만, 해가 다르면 해까지 적는다', () => {
    expect(repoPeriod('2026-05-20', '2026-05-28')).toBe('5월');
    expect(text(repo({ activeFrom: '2026-05-20', activeTo: '2026-05-28', prCount: 1, reviewCount: 0 }))).toBe('5월에 PR 1개를 올린 레포예요');
    expect(repoPeriod('2025-03-02', '2026-10-01')).toBe('2025년 3월부터 2026년 10월까지');
  });

  it('날짜의 앞 연월만 읽어 시간대 때문에 달이 밀리지 않는다', () => {
    expect(repoPeriod('2026-08-01T00:00:00Z', '2026-11-30T23:30:00Z')).toBe('8월부터 11월까지');
  });

  it('메타 줄은 언어만 두고(기간은 문장이 이미 말한다), 언어가 없으면 비운다', () => {
    expect(repoMeta(repo())).toBe('Java');
    expect(repoMeta(repo({ language: null }))).toBe('');
  });
});

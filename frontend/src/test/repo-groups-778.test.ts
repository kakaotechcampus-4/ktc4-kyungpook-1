import { describe, expect, it } from 'vitest';
import { RepoSummary } from '@/api/schemas';
import { groupRepos } from '@/lib/repoGroups';

const repo = (id: string, over: Record<string, unknown> = {}) => RepoSummary.parse({
  id, owner: 'hong-dev', name: id, contribution: { mine: 1, team: 1, ratio: 0.5, level: 'SHARED' }, prCount: 0, reviewCount: 0, language: null,
  activeFrom: '2026-01-01', activeTo: '2026-02-01', lastAnalyzedAt: null, candidateCount: null, cardCount: 0, recommended: false, ...over,
});
const ids = (list: RepoSummary[]) => list.map((r) => r.id);
const DONE = '2026-10-01T00:00:00Z';

describe('레포 목록 묶기 (#778)', () => {
  it('추천이고 아직 정리하지 않은 레포는 첫 묶음이다', () => {
    const groups = groupRepos([repo('rec-1', { recommended: true }), repo('plain'), repo('rec-done', { recommended: true, lastAnalyzedAt: DONE }), repo('rec-2', { recommended: true })]);

    expect(ids(groups.recommended)).toEqual(['rec-1', 'rec-2']);
  });

  it('추천이 아니고 아직 정리하지 않은 레포는 둘째 묶음이다', () => {
    const groups = groupRepos([repo('plain-1'), repo('rec', { recommended: true }), repo('plain-done', { lastAnalyzedAt: DONE }), repo('plain-2')]);

    expect(ids(groups.fresh)).toEqual(['plain-1', 'plain-2']);
  });

  it('정리한 레포는 추천이어도 셋째 묶음이고 입력 순서를 지킨다', () => {
    const groups = groupRepos([repo('done-1', { lastAnalyzedAt: DONE }), repo('rec', { recommended: true }), repo('done-2', { recommended: true, lastAnalyzedAt: DONE }), repo('plain'), repo('done-3', { lastAnalyzedAt: DONE })]);

    expect(ids(groups.done)).toEqual(['done-1', 'done-2', 'done-3']);
  });
});

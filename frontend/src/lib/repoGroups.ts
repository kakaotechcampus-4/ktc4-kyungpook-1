import type { RepoSummary } from '@/api/schemas';

export type RepoGroups = { recommended: RepoSummary[]; fresh: RepoSummary[]; done: RepoSummary[] };

/** 정리한 적이 있으면 추천이어도 done 이다 — 이미 정리한 레포를 '먼저 정리해 볼 레포'로 다시 권하지 않는다 */
export function groupRepos(list: RepoSummary[]): RepoGroups {
  const open = list.filter((r) => !r.lastAnalyzedAt);
  return {
    recommended: open.filter((r) => r.recommended),
    fresh: open.filter((r) => !r.recommended),
    done: list.filter((r) => r.lastAnalyzedAt),
  };
}

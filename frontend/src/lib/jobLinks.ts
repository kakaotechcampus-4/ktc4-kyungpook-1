import type { ActiveJob, Job } from '@/api/schemas';

/** Keep previous/next within the actual creation batch. */
export function jobResultState(job: Job): { cardOrder: string[] } | undefined {
  const ids = job.type === 'DRAFT' ? job.result?.cardIds : undefined;
  return ids?.length ? { cardOrder: [...ids] } : undefined;
}

/** 끝난 작업의 결과를 볼 곳 — 카드를 여러 장 만들었으면 다음 일이 여러 장을 훑어 확정하는 것이라 그 레포의 카드 폴더다 */
export function jobResultLink(job: Job, knownRepoId?: string | null): string | undefined {
  if (job.type === 'DRAFT') {
    const ids = job.result?.cardIds ?? [];
    const draftRepoId = job.result?.repoId ?? knownRepoId;
    if (ids.length > 1 && draftRepoId) return `/cards/repo/${encodeURIComponent(draftRepoId)}`;
    return ids.length ? `/cards/${encodeURIComponent(ids[0])}` : '/cards';
  }
  const repoId = job.result?.repoId ?? knownRepoId;
  if (!repoId) return undefined;
  return job.state === 'FAILED' || job.state === 'CANCELED' || job.partial
    ? `/repos/${encodeURIComponent(repoId)}/run?job=${encodeURIComponent(job.jobId)}`
    : `/repos/${encodeURIComponent(repoId)}/candidates`;
}

/** 진행 중인 작업은 시작한 화면에서 이어 본다 — 레포 정리는 4단계 화면, 카드 초안은 만든 카드 id 를 받기 전까지 그 레포의 카드 폴더 */
export function activeJobLink(job: Pick<ActiveJob, 'jobId' | 'type' | 'userRepositoryId'>): string {
  const repoId = job.userRepositoryId;
  if (job.type === 'DRAFT') return repoId ? `/cards/repo/${encodeURIComponent(repoId)}` : '/cards';
  return repoId ? `/repos/${encodeURIComponent(repoId)}/run?job=${encodeURIComponent(job.jobId)}` : '/repos';
}

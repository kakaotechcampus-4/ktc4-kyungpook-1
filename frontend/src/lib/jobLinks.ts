import type { ActiveJob, Job } from '@/api/schemas';

/** Keep previous/next within the actual creation batch. */
export function jobResultState(job: Job): { cardOrder: string[] } | undefined {
  const ids = job.type === 'DRAFT' ? job.result?.cardIds : undefined;
  return ids?.length ? { cardOrder: [...ids] } : undefined;
}

export function jobResultLink(job: Job, knownRepoId?: string | null): string | undefined {
  if (job.type === 'DRAFT') return job.result?.cardIds?.length ? `/cards/${encodeURIComponent(job.result.cardIds[0])}` : '/cards';
  const repoId = job.result?.repoId ?? knownRepoId;
  if (!repoId) return undefined;
  return job.state === 'FAILED' || job.state === 'CANCELED' || job.partial
    ? `/repos/${encodeURIComponent(repoId)}/run?job=${encodeURIComponent(job.jobId)}`
    : `/repos/${encodeURIComponent(repoId)}/candidates`;
}

/** 진행 중인 작업은 시작한 화면에서 이어 본다 — 레포 정리는 4단계 화면, 카드 초안은 그 레포의 카드 폴더 */
export function activeJobLink(job: Pick<ActiveJob, 'jobId' | 'type' | 'userRepositoryId'>): string {
  const repoId = job.userRepositoryId;
  if (job.type === 'DRAFT') return repoId ? `/cards/repo/${encodeURIComponent(repoId)}` : '/cards';
  return repoId ? `/repos/${encodeURIComponent(repoId)}/run?job=${encodeURIComponent(job.jobId)}` : '/repos';
}

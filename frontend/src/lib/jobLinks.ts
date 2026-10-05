import type { Job } from '@/api/schemas';

export function jobResultLink(job: Job, knownRepoId?: string | null): string | undefined {
  if (job.type === 'DRAFT') return job.result?.cardIds?.length ? `/cards/${encodeURIComponent(job.result.cardIds[0])}` : '/cards';
  const repoId = job.result?.repoId ?? knownRepoId;
  if (!repoId) return undefined;
  return job.state === 'FAILED' || job.state === 'CANCELED' || job.partial
    ? `/repos/${encodeURIComponent(repoId)}/run?job=${encodeURIComponent(job.jobId)}`
    : `/repos/${encodeURIComponent(repoId)}/candidates`;
}

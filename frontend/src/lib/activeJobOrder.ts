import type { ActiveJob } from '@/api/schemas';

/** Running work first, then FIFO waits; never change the server array. */
export function orderedActiveJobs(jobs: readonly ActiveJob[]): ActiveJob[] {
  return jobs.filter(job => job.state === 'RUNNING' || job.state === 'QUEUED')
    .sort((a, b) => Number(b.state === 'RUNNING') - Number(a.state === 'RUNNING')
      || Date.parse(a.startedAt) - Date.parse(b.startedAt));
}

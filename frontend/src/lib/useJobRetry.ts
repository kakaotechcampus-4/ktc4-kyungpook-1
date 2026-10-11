import { useEffect, useReducer } from 'react';
import type { Job } from '@/api/schemas';
function retryDeadline(job: Job | undefined, now: number) {
  return job?.errorCode === 'GITHUB_RATE_LIMITED' && job.retryAfterSec != null && job.retryAfterSec > 0
    ? Date.parse(job.finishedAt ?? job.updatedAt) + job.retryAfterSec * 1000 : now;
}
export const requiresRetryRefresh = (job: Job | undefined) => !Number.isFinite(retryDeadline(job, Date.now()));

/** Retry permission comes from the server, not from the error name. */
export function jobRetryState(job: Job | undefined, now = Date.now()) {
  const terminalFailure = job?.state === 'FAILED' || (job?.state === 'SUCCEEDED' && job.partial);
  const deadline = retryDeadline(job, now);
  const valid = Number.isFinite(deadline);
  const waitSec = valid ? Math.max(0, Math.ceil((deadline - now) / 1000)) : 0;
  return { canRetry: !!terminalFailure && job?.retryable === true && valid && waitSec === 0, waitSec };
}

export function useJobRetry(job: Job | undefined) {
  const [, tick] = useReducer((n: number) => n + 1, 0);
  const { waitSec, canRetry } = jobRetryState(job);
  useEffect(() => {
    if (waitSec === 0) return;
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, [waitSec > 0]);
  return { waitSec, canRetry };
}

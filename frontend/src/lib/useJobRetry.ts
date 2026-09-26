import { useEffect, useReducer } from 'react';
import type { Job } from '@/api/schemas';

/** Retry permission comes from the server, not from the error name. */
export function jobRetryState(job: Job | undefined, now = Date.now()) {
  const terminalFailure = job?.state === 'FAILED' || (job?.state === 'SUCCEEDED' && job.partial);
  const cooldown = job?.errorCode === 'GITHUB_RATE_LIMITED' && job.retryAfterSec != null && job.retryAfterSec > 0;
  const deadline = cooldown ? Date.parse(job.finishedAt ?? job.updatedAt) + job.retryAfterSec! * 1000 : now;
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

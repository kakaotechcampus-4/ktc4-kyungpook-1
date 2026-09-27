import { newIdempotencyKey } from '@/lib/uuid';
import { isUnknownOutcome } from './errorView';
import type { StartedJob } from './schemas';

/** In-memory only, scoped to a mounted action. Preserve identity only when the response is unknown. */
export function createAnalysisRequest(send: (repoId: string, key: string) => Promise<StartedJob>) {
  const attempts = new Map<string, { key: string; inFlight?: Promise<StartedJob> }>();
  return (repoId: string, explicitKey?: string): Promise<StartedJob> => {
    let attempt = attempts.get(repoId);
    if (attempt?.inFlight) return attempt.inFlight;
    if (!attempt || (explicitKey && explicitKey !== attempt.key)) {
      attempt = { key: explicitKey ?? newIdempotencyKey() };
      attempts.set(repoId, attempt);
    }
    const current = attempt;
    current.inFlight = Promise.resolve().then(() => send(repoId, current.key)).then(
      (result) => { attempts.delete(repoId); return result; },
      (error: unknown) => {
        if (!isUnknownOutcome(error)) attempts.delete(repoId);
        throw error;
      },
    ).finally(() => { current.inFlight = undefined; });
    return current.inFlight;
  };
}

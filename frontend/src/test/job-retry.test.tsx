import { act, cleanup, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Job } from '@/api/schemas';
import { jobRetryState, useJobRetry } from '@/lib/useJobRetry';

const now = Date.parse('2026-09-26T00:00:00Z');
const job: Job = {
  jobId: 'job-a', type: 'ANALYZE', state: 'FAILED', partial: false, steps: [],
  errorCode: 'GITHUB_RATE_LIMITED', retryable: true, retryAfterSec: 60,
  startedAt: new Date(now).toISOString(), updatedAt: new Date(now).toISOString(),
  finishedAt: new Date(now).toISOString(), result: null, pollAfterMs: 0,
};
afterEach(() => { cleanup(); vi.useRealTimers(); });

describe('server-controlled Job retry', () => {
  it.each([false, null])('does not infer permission from retryable=%s', (retryable) => {
    expect(jobRetryState({ ...job, errorCode: 'INTERNAL_ERROR', retryable }, now).canRetry).toBe(false);
  });
  it.each(['FAILED', 'SUCCEEDED'] as const)('holds %s until the server deadline', (state) => {
    const current = { ...job, state, partial: state === 'SUCCEEDED' };
    expect(jobRetryState(current, now)).toEqual({ canRetry: false, waitSec: 60 });
    expect(jobRetryState(current, now + 60_000)).toEqual({ canRetry: true, waitSec: 0 });
    expect(jobRetryState({ ...current, retryable: false }, now + 60_000).canRetry).toBe(false);
  });
  it('allows an explicit retry without a cooldown and blocks running/completed jobs', () => {
    expect(jobRetryState({ ...job, retryAfterSec: null }, now).canRetry).toBe(true);
    for (const state of ['QUEUED', 'RUNNING', 'CANCELED', 'SUCCEEDED'] as const)
      expect(jobRetryState({ ...job, state, retryAfterSec: null }, now).canRetry).toBe(false);
  });
  it('does not open the button on first render or when switching jobs', () => {
    vi.useFakeTimers(); vi.setSystemTime(now);
    const { result, rerender } = renderHook(({ value }) => useJobRetry(value), { initialProps: { value: job } });
    expect(result.current.canRetry).toBe(false);
    act(() => vi.advanceTimersByTime(60_000));
    expect(result.current.canRetry).toBe(true);
    rerender({ value: { ...job, jobId: 'job-b', finishedAt: new Date(now + 60_000).toISOString() } });
    expect(result.current).toEqual({ canRetry: false, waitSec: 60 });
  });
});

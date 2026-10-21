import { expect, it } from 'vitest';
import { ActiveJob, Job } from '@/api/schemas';
import { orderedActiveJobs } from '@/lib/activeJobOrder';
import { jobResultState } from '@/lib/jobLinks';
import { errorView, isUnknownOutcome } from '@/api/errorView';
import { ApiError } from '@/api/client';

it('puts running work first and queues in FIFO order without mutating server data', () => {
  const make = (id: string, state: string, startedAt: string) => ActiveJob.parse({ jobId: id, state, type: 'ANALYZE', startedAt, userRepositoryId: null, repoName: id, pollAfterMs: 2000 });
  const jobs = [make('new', 'QUEUED', '2026-10-21T01:03:00Z'), make('running', 'RUNNING', '2026-10-21T01:01:00Z'), make('old', 'QUEUED', '2026-10-21T01:02:00Z'), make('done', 'SUCCEEDED', '2026-10-21T01:00:00Z')];
  expect(orderedActiveJobs(jobs).map(j => j.jobId)).toEqual(['running', 'old', 'new']);
  expect(jobs[0].jobId).toBe('new');
});
it('only attaches batch card navigation to draft job results with actual cards', () => {
  const job = { type: 'DRAFT', result: { cardIds: ['second', 'first'] } } as Job;
  expect(jobResultState(job)).toEqual({ cardOrder: ['second', 'first'] });
  expect(jobResultState({ ...job, type: 'ANALYZE' })).toBeUndefined();
  expect(jobResultState({ ...job, result: { cardIds: [] } })).toBeUndefined();
});
it('distinguishes request throttling from GitHub job failures without automatic retry', () => {
  const error = new ApiError('RATE_LIMITED', 'internal message', 429, { retryAfterSec: 90 });
  expect(errorView(error)).toEqual({ title: '요청이 많아요', message: '90초 뒤 다시 시도해 주세요. 입력한 내용은 그대로 남아 있어요.', canRetry: false });
  expect(isUnknownOutcome(error)).toBe(false);
  expect(errorView(error, 'read')).toEqual({ title: '요청이 많아요', message: '90초 뒤 다시 시도해 주세요.', canRetry: true });
  expect(errorView(new ApiError('RATE_LIMITED', 'hidden', 429)).message).toBe('잠시 뒤 다시 시도해 주세요. 입력한 내용은 그대로 남아 있어요.');
  expect(errorView(new ApiError('RATE_LIMITED', 'hidden', 429, { retryAfterSec: -1 })).message).not.toContain('-1');
});

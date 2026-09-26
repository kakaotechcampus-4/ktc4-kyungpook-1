import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { AnalyzePage } from '@/features/repos/AnalyzePage';
import { keys } from '@/api/keys';
import { Job, JobErrorCode } from '@/api/schemas';
import { handle } from '@/mock/router';
import { ApiError } from '@/api/client';
import { errorView } from '@/api/errorView';

vi.mock('@/lib/jobWatcher', () => ({ releaseJobToast: vi.fn(), suppressJobToast: vi.fn() }));
afterEach(cleanup);
for (const state of ['FAILED', 'SUCCEEDED'] as const) {
  it.each([true, false, null])(`${state} uses server retryable=%s`, (retryable) => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    client.setQueryData(keys.repo('r_auth'), handle('GET', '/repos/r_auth', new URLSearchParams(), {}, true).data);
    client.setQueryData(keys.job('job'), Job.parse({
      jobId: 'job', type: 'ANALYZE', state, partial: state === 'SUCCEEDED', steps: [],
      errorCode: 'INTERNAL_ERROR', retryable, retryAfterSec: null, result: null,
      startedAt: '2026-09-26T00:00:00Z', updatedAt: '2026-09-26T00:00:00Z', finishedAt: null, pollAfterMs: 0,
    }));
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/repos/r_auth/run?job=job']}><Routes>
      <Route path="/repos/:repoId/run" element={<AnalyzePage />} />
    </Routes></MemoryRouter></QueryClientProvider>);
    const retry = screen.getByRole('button', { name: state === 'FAILED' ? '다시 시도' : '이어 읽기' });
    if (retryable === true) expect(retry).toBeEnabled();
    else expect(retry).toBeDisabled();
    client.clear();
  });
}
it('recognizes the confirmed GitHub rate-limit code only', () => {
  expect(JobErrorCode.safeParse('GITHUB_RATE_LIMITED').success).toBe(true);
  expect(JobErrorCode.safeParse('RATE_LIMITED').success).toBe(false);
});
it('does not label unrelated INVALID_REQUEST conflicts as idempotency mismatches', () => {
  expect(errorView(new ApiError('IDEMPOTENCY_KEY_MISMATCH', '', 409)).title).toBe('분석 요청이 충돌했어요');
  expect(errorView(new ApiError('INVALID_REQUEST', '', 409)).title).not.toBe('분석 요청이 충돌했어요');
});

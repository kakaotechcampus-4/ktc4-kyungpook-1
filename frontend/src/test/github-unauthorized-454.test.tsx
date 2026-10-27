import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { Job } from '@/api/schemas';
import { JobStatusPage } from '@/features/jobs/JobStatusPage';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

// 서버가 토큰 거절로 끝낸 Job(#454). 목록에 없는 값이면 화면이 응답을 읽지 못한다
const rejected = {
  jobId: 'job_rejected', type: 'ANALYZE', state: 'FAILED', partial: false, steps: [],
  errorCode: 'GITHUB_UNAUTHORIZED', retryable: false, retryAfterSec: null,
  startedAt: '2026-10-27T00:00:00Z', updatedAt: '2026-10-27T00:01:00Z', finishedAt: '2026-10-27T00:01:00Z',
  result: null, pollAfterMs: 2000,
};

describe('#454 a job GitHub rejected the token for', () => {
  it('reads the GITHUB_UNAUTHORIZED error code from the server', () => {
    expect(Job.parse(rejected).errorCode).toBe('GITHUB_UNAUTHORIZED');
  });

  it('asks to reconnect GitHub instead of showing an internal error', () => {
    const job = Job.parse(rejected);
    vi.spyOn(endpoints, 'job').mockResolvedValue(job);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    client.setQueryData(keys.job(job.jobId), job);
    const router = createMemoryRouter([{ path: '/jobs/:jobId', element: <JobStatusPage /> }], { initialEntries: [`/jobs/${job.jobId}`] });
    render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);

    expect(screen.getByText('GitHub 연결을 다시 해 주세요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'GitHub 다시 연결' })).toBeInTheDocument();
  });
});

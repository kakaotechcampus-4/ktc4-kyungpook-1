import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { keys } from '@/api/keys';
import { Job, RepoDetail } from '@/api/schemas';
import { AnalyzePage } from '@/features/repos/AnalyzePage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/jobWatcher', () => ({ releaseJobToast: vi.fn(), suppressJobToast: vi.fn() }));
// 다 읽으면 고른 이유를 보여 준 뒤 후보 보드로 넘어간다. 이 시험은 넘어가기 전의 화면을 본다
const navigate = vi.hoisted(() => vi.fn());
vi.mock('react-router-dom', async (importOriginal) => ({ ...(await importOriginal<typeof import('react-router-dom')>()), useNavigate: () => navigate }));

const steps = (state: 'DONE' | 'RUNNING') => ['PR_REVIEW', 'COMMITS', 'COMPRESS', 'REASON'].map((key) => ({ key, state, done: 4, total: null }));
const job = (state: 'RUNNING' | 'SUCCEEDED') => Job.parse({
  jobId: 'job', type: 'ANALYZE', state, partial: false, steps: steps(state === 'SUCCEEDED' ? 'DONE' : 'RUNNING'), errorCode: null, retryable: null, retryAfterSec: null,
  result: null, startedAt: '2026-11-02T00:00:00Z', updatedAt: '2026-11-02T00:00:10Z', finishedAt: state === 'SUCCEEDED' ? '2026-11-02T00:00:10Z' : null, pollAfterMs: 2000,
});

function mount(state: 'RUNNING' | 'SUCCEEDED') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(keys.repo('r_auth'), RepoDetail.parse(handle('GET', '/repos/r_auth', new URLSearchParams(), {}, true).data));
  client.setQueryData(keys.job('job'), job(state));
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/repos/r_auth/run?job=job']}>
    <Routes><Route path="/repos/:repoId/run" element={<AnalyzePage />} /></Routes></MemoryRouter></QueryClientProvider>);
}

beforeEach(resetDb);
afterEach(cleanup);

describe('분석 진행 화면 — 다 읽은 뒤 고른 이유를 보여 주는 동안', () => {
  it('읽는 중에는 "읽고 있어요"와 "정리 중", 떠나도 된다는 말과 취소 버튼을 보인다', () => {
    mount('RUNNING');
    expect(screen.getByRole('heading', { name: '레포를 읽고 있어요' })).toBeInTheDocument();
    expect(screen.getByText('정리 중')).toBeInTheDocument();
    expect(screen.getByText('새 기록을 읽는 중이에요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '정리 취소' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '후보 보드로 바로 가기' })).not.toBeInTheDocument();
  });

  it('다 읽은 작업은 제목·위치·한 줄 설명이 끝난 일을 말하고, 이미 끝난 작업을 취소하라고 하지 않는다', () => {
    mount('SUCCEEDED');
    expect(screen.getByRole('heading', { name: '레포를 다 읽었어요' })).toBeInTheDocument();
    expect(screen.queryByText('레포를 읽고 있어요')).not.toBeInTheDocument();
    expect(screen.getByText('정리 완료')).toBeInTheDocument();
    expect(screen.queryByText('정리 중')).not.toBeInTheDocument();
    expect(screen.getByText('후보를 골랐어요')).toBeInTheDocument();
    expect(screen.queryByText('새 기록을 읽는 중이에요')).not.toBeInTheDocument();
    expect(screen.queryByText('떠나도 돼요')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '정리 취소' })).not.toBeInTheDocument();
  });

  it('기다리지 않고 후보 보드로 바로 갈 수 있다', () => {
    mount('SUCCEEDED');
    expect(screen.getByRole('link', { name: '후보 보드로 바로 가기' })).toHaveAttribute('href', '/repos/r_auth/candidates');
  });
});

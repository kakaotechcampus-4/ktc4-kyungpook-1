// 11/3 배포 사이트 점검(Codex)에서 재현된 저장소 흐름: 끝난 레포 /run 은 재분석만, 미분석·분석 중 후보 화면이 '후보 0개', 끝난 작업 재방문 8초 재생, 없는 레포에서 '정리 시작' 깜빡임
import { act, cleanup, render, renderHook, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { CandidateBoard, RepoDetail, type Job } from '@/api/schemas';
import { AnalyzePage } from '@/features/repos/AnalyzePage';
import { CandidateBoardPage } from '@/features/candidates/CandidateBoardPage';
import { REVEAL_MS, useActivityFeed } from '@/lib/agentActivity';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/jobWatcher', () => ({ releaseJobToast: vi.fn(), suppressJobToast: vi.fn() }));
beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers(); });

const repoOf = (patch: Partial<RepoDetail>) => ({ ...RepoDetail.parse(handle('GET', '/repos/r_auth', new URLSearchParams(), {}, true).data), ...patch });
const emptyBoard = (): CandidateBoard => ({ repoId: 'r_auth', verdict: 'EMPTY', partial: false, retryAfterSeconds: null, readCoverage: null, emptyReasons: ['아직 정리하지 않은 저장소예요'], candidates: [] });
const noJobs = { jobs: [] } as unknown as Awaited<ReturnType<typeof endpoints.activeJobs>>;

function mount(path: string, seed: (c: QueryClient) => void) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  seed(client);
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/repos/:repoId/run" element={<AnalyzePage />} />
    <Route path="/repos/:repoId/candidates" element={<CandidateBoardPage />} />
  </Routes></MemoryRouter></QueryClientProvider>);
}

describe('정리 시작 화면', () => {
  it('정리한 적 있는 레포는 지난 결과 보기를 먼저, 다시 정리는 따로 둔다', async () => {
    mount('/repos/r_auth/run', (c) => {
      c.setQueryData(keys.repo('r_auth'), repoOf({ lastAnalyzedAt: '2026-11-01T00:00:00Z' }));
      c.setQueryData(keys.activeJobs, noJobs);
    });
    expect(await screen.findByRole('heading', { name: '전에 정리한 레포예요' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '지난 결과 보기' })).toHaveAttribute('href', '/repos/r_auth/candidates');
    expect(screen.getByRole('button', { name: '다시 정리' })).toBeInTheDocument();
  });

  it('레포를 확인하기 전에는 정리 시작 버튼을 그리지 않는다', () => {
    vi.spyOn(endpoints, 'repo').mockImplementation(() => new Promise(() => {}));
    mount('/repos/zzz/run', (c) => c.setQueryData(keys.activeJobs, noJobs));
    expect(screen.queryByRole('button', { name: '정리 시작' })).not.toBeInTheDocument();
  });
});

describe('후보 0개 화면', () => {
  it('한 번도 정리하지 않은 레포는 파일 보며 찾기 대신 정리하러 가기를 권한다(직접 추가·직접 작성은 그대로)', async () => {
    mount('/repos/r_auth/candidates', (c) => {
      c.setQueryData(keys.repo('r_auth'), repoOf({ lastAnalyzedAt: null }));
      c.setQueryData(keys.candidates('r_auth'), emptyBoard());
      c.setQueryData(keys.activeJobs, noJobs);
    });
    expect(await screen.findByRole('heading', { name: '아직 정리하지 않은 레포예요' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '정리하러 가기' })).toHaveAttribute('href', '/repos/r_auth/run');
    expect(screen.queryByRole('link', { name: '파일 보며 찾기' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '+ 직접 추가' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '직접 작성' })).toBeInTheDocument();
  });

  it('분석이 도는 중이면 후보 0개라고 하지 않고 진행 화면으로 보낸다', async () => {
    mount('/repos/r_auth/candidates', (c) => {
      c.setQueryData(keys.repo('r_auth'), repoOf({ lastAnalyzedAt: null }));
      c.setQueryData(keys.candidates('r_auth'), emptyBoard());
      c.setQueryData(keys.activeJobs, { jobs: [{ jobId: 'j1', state: 'RUNNING', type: 'ANALYZE', userRepositoryId: 'r_auth', repoName: 'x', startedAt: '2026-11-03T00:00:00Z', pollAfterMs: 1000 }] });
    });
    expect(await screen.findByRole('heading', { name: '아직 후보를 찾는 중이에요' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '진행 상황 보기' })).toHaveAttribute('href', '/repos/r_auth/run?job=j1');
    expect(screen.queryByText('자동으로 찾은 후보가 없어요')).not.toBeInTheDocument();
  });

  it('정리했는데 후보가 0개면 지금처럼 판정과 다음 행동을 보여 준다', async () => {
    mount('/repos/r_auth/candidates', (c) => {
      c.setQueryData(keys.repo('r_auth'), repoOf({ lastAnalyzedAt: '2026-11-01T00:00:00Z' }));
      c.setQueryData(keys.candidates('r_auth'), emptyBoard());
      c.setQueryData(keys.activeJobs, noJobs);
    });
    expect(await screen.findByRole('heading', { name: '자동으로 찾은 후보가 없어요' })).toBeInTheDocument();
  });
});

describe('고른 이유 재생', () => {
  const job = (state: string, finishedAt: string | null = state === 'RUNNING' ? null : '2026-10-01T00:00:00Z') => ({ jobId: 'j1', state, finishedAt, activity: [1, 2, 3].map((seq) => ({ seq, step: 'DESCRIBE', kind: 'DESCRIBED', title: `이유 ${seq}`, reason: null })) }) as unknown as Job;

  it('방금 끝난 작업은 처음 열어도 차례로 보여 준다', () => {
    const { result } = renderHook(() => useActivityFeed(job('SUCCEEDED', new Date().toISOString())));
    expect(result.current.drained).toBe(false);
  });

  it('오래전에 끝난 작업을 다시 열면 기다리지 않고 한꺼번에 보여 준다', () => {
    const { result } = renderHook(() => useActivityFeed(job('SUCCEEDED')));
    expect(result.current.drained).toBe(true);
    expect(result.current.lines).toHaveLength(3);
  });

  it('지켜보던 작업은 끝나도 차례로 다 보여 준 뒤에 넘어간다', () => {
    vi.useFakeTimers();
    const { result, rerender } = renderHook(({ j }) => useActivityFeed(j), { initialProps: { j: job('RUNNING') } });
    rerender({ j: job('SUCCEEDED') });
    expect(result.current.drained).toBe(false);
    // 줄마다 타이머가 새로 걸리므로 한 줄씩 넘긴다
    for (let i = 0; i < 5; i++) act(() => { vi.advanceTimersByTime(REVEAL_MS); });
    expect(result.current.drained).toBe(true);
  });

  it('고른 이유가 20줄 밀려 있어도 앞은 건너뛰고 마지막 3줄만 한 줄 간격씩 보인 뒤 끝난다', () => {
    vi.useFakeTimers();
    const many = { jobId: 'j1', state: 'SUCCEEDED', finishedAt: new Date().toISOString(), activity: Array.from({ length: 20 }, (_, i) => ({ seq: i + 1, step: 'REASON', kind: 'DESCRIBED', title: `이유 ${i + 1}`, reason: null })) } as unknown as Job;
    const { result } = renderHook(() => useActivityFeed(many));
    for (let i = 0; i < 3; i++) act(() => { vi.advanceTimersByTime(REVEAL_MS); });
    expect(result.current.drained).toBe(true);
    expect(result.current.lines).toHaveLength(20);
  });
});

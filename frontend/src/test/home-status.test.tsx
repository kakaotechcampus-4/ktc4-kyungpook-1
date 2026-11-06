import type { ReactNode } from 'react';
import { cleanup, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { matchingApi, OverallRecommendation, RepoRecommendation } from '@/api/matching';
import { CardSummary } from '@/api/schemas';
import { AppShell } from '@/components/layout/AppShell';
import { HomePage } from '@/features/home/HomePage';
import { HomeHero } from '@/features/home/HomeSections';
import { useHomePicks } from '@/features/home/useHomePicks';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';
import { connectMockServer, mountApp, page, type Call } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/jobWatcher', () => ({ JobWatcher: () => null, releaseJobToast: vi.fn(), suppressJobToast: vi.fn() }));
beforeEach(() => { resetDb(); localStorage.clear(); });
afterEach(() => { cleanup(); vi.unstubAllEnvs(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

const mockCards = () => handle('GET', '/cards', new URLSearchParams(), {}, true).data as CardSummary[];
const json = (data: unknown) => new Response(JSON.stringify({ data, error: null }), { status: 200 });
const runningJob = { jobId: 'job_1', state: 'RUNNING', type: 'ANALYZE', userRepositoryId: 'r_auth', repoName: 'auth-service', startedAt: '2026-11-05T00:00:00Z', pollAfterMs: 60_000 };
const noCards = (jobs: unknown[]) => (call: Call) => (call.path === '/cards' ? json([]) : call.path.startsWith('/jobs?') ? json({ jobs }) : undefined);

describe('홈의 상태 화면', () => {
  it('처음 온 사람(카드 0장, 도는 작업 없음)에게는 이어서 하기 자리에 레포를 하나 골라 보라는 안내와 레포 고르기 링크가 보인다', async () => {
    connectMockServer(noCards([]));
    mountApp([page(<HomePage />, '/')], '/');

    const todo = await screen.findByRole('region', { name: '이어서 하기' });
    await within(todo).findByText('레포를 하나 골라 보세요');
    expect(within(todo).getByRole('link', { name: /레포 고르기/ })).toHaveAttribute('href', '/repos');
    cleanup();

    connectMockServer(noCards([runningJob]));
    mountApp([page(<HomePage />, '/')], '/');
    const running = await screen.findByRole('region', { name: /^이어서 하기/ });
    await within(running).findByText('auth-service');
    expect(within(running).queryByText('레포를 하나 골라 보세요')).not.toBeInTheDocument();
  });

  it('지금 상태는 레포, 경험 카드, 공고, 자소서 순서이고, 확정하지 않은 카드가 있으면 경험 카드 칸에 확인할 카드 수가 보이며 가장 먼저 걸린 작성 중 카드로 간다', async () => {
    const drafts = mockCards().filter((card) => card.status === 'DRAFT');
    expect(drafts.length).toBeGreaterThan(1);
    connectMockServer();
    mountApp([page(<HomePage />, '/')], '/');

    await screen.findByText(`확인할 카드 ${drafts.length}장`);
    const tiles = within(screen.getByRole('region', { name: '지금 상태' })).getAllByRole('link');
    expect(tiles.map((tile) => tile.textContent?.match(/^(레포|경험 카드|공고|자소서)/)?.[0])).toEqual(['레포', '경험 카드', '공고', '자소서']);
    expect(tiles[1]).toHaveAttribute('href', `/cards/${drafts[0].id}`);
  });

  it('한 줄 입력은 VITE_ASSISTANT_ENABLED 가 true 일 때만 보이고, 꺼져 있으면 자주 하는 일 버튼만 보인다', () => {
    vi.stubEnv('VITE_ASSISTANT_ENABLED', 'false');
    mountApp([page(<HomeHero />, '/')], '/');
    expect(screen.queryByRole('textbox', { name: '할 일' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: /레포 정리하기/ })).toHaveAttribute('href', '/repos');
    expect(screen.getByRole('link', { name: /자소서 쓰기/ })).toHaveAttribute('href', '/cover-letters/new');
    cleanup();

    vi.stubEnv('VITE_ASSISTANT_ENABLED', 'true');
    mountApp([page(<HomeHero />, '/')], '/');
    expect(screen.getByRole('textbox', { name: '할 일' })).toBeInTheDocument();
  });
});

describe('홈에 보일 맞는 공고', () => {
  const company = (n: number) => ({
    companyId: `co_${n}`, companyName: `회사 ${n}`, logoUrl: null, techs: [],
    posting: { id: `p_${n}`, title: `공고 ${n}`, deadline: null, closed: false },
    requiredMet: n, requiredTotal: 5, preferredMet: 0, preferredTotal: 0,
  });
  const overall = (status: string, companies: unknown[]) => OverallRecommendation.parse({ status, computedAt: null, stale: false, progress: null, companies });
  const confirmed = (id: string, repoId: string) => CardSummary.parse({
    id, kind: 'TECH', status: 'CONFIRMED', title: id, versionNo: 1, updatedAt: '2026-11-05T00:00:00Z',
    star: { S: 'FILLED', T: 'FILLED', A: 'FILLED', R: 'FILLED' }, repo: { id: repoId, owner: 'gitory', name: repoId },
  });
  const picksOf = (cards: CardSummary[]) => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
    const { result } = renderHook(() => useHomePicks(cards), { wrapper });
    return () => result.current.picks.map((pick) => pick.postingId);
  };

  it('전체 매칭 결과가 있으면 그것을, 없으면 레포 추천을 맞은 필수 조건이 많은 순으로 쓰고, 같은 공고는 한 번만 두며 마감된 공고는 뺀다', async () => {
    const cards = [confirmed('a', 'r_a'), confirmed('b', 'r_b')];
    vi.spyOn(matchingApi, 'repoRecommendations').mockImplementation(async (repoId: string) => RepoRecommendation.parse({
      status: 'READY', computedAt: '2026-11-02T12:00:00Z', companies: repoId === 'r_a' ? [company(1), company(4), company(2)] : [company(3), company(2)],
    }));

    const closed = { ...company(5), posting: { id: 'p_5', title: '공고 5', deadline: null, closed: true } };
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(overall('READY', [company(1), closed, company(2)]));
    const fromOverall = picksOf(cards);
    await waitFor(() => expect(fromOverall()).toEqual(['p_1', 'p_2']));
    cleanup();

    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(overall('NONE', []));
    const fromRepos = picksOf(cards);
    await waitFor(() => expect(fromRepos()).toEqual(['p_4', 'p_3', 'p_2', 'p_1']));
  });
});

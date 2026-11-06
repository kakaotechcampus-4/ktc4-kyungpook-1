import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { keys } from '@/api/keys';
import { matchingApi, RepoRecommendation } from '@/api/matching';
import { RepoDetail, RepoSummary } from '@/api/schemas';
import { queryClient } from '@/app/queryClient';
import { HomePage } from '@/features/home/HomePage';
import { RepoPickPage } from '@/features/match/RepoPickPage';
import { ReposPage } from '@/features/repos/ReposPage';
import { againOf, pickHead } from '@/lib/repoPick';
import { toast } from '@/lib/toast';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';
import { connectMockServer, jsonError, mountApp, page } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/toast', async (original) => ({ ...(await original<typeof import('@/lib/toast')>()), toast: vi.fn() }));
beforeEach(() => {
  resetDb();
  localStorage.clear();
  queryClient.setDefaultOptions({ queries: { ...queryClient.getDefaultOptions().queries, retry: false } });
});
afterEach(() => { cleanup(); queryClient.clear(); vi.unstubAllEnvs(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

const FOLDER = '/cards/repo/r_auth';
const addUpdate = (over: Partial<(typeof db.repoUpdates)[number]> = {}) =>
  db.repoUpdates.push({ repoId: 'r_auth', repoName: 'hong-dev/auth-service', cardCount: 3, finishedAt: '2026-11-06T00:00:00Z', seen: false, ...over });
const notice = () => screen.queryByRole('region', { name: '새로 써 둔 카드' });
const pickPages = [page(<RepoPickPage />, '/cards/repo/:repoId/match'), page(<p>폴더 화면</p>, '/cards/repo/:repoId')];
const company = (n: number, over: Record<string, unknown> = {}) => ({
  companyId: `co_${n}`, companyName: `회사 ${n}`, logoUrl: null,
  posting: { id: `p_${n}`, title: `백엔드 개발자 ${n}`, deadline: null, closed: false }, techs: ['SPRING'], requiredMet: 1, preferredMet: 0, ...over,
});
const ready = (companies: unknown[]) => RepoRecommendation.parse({
  status: 'READY', computedAt: '2026-11-06T00:00:00Z', queued: false, live: null, companies,
});

describe('서버가 스스로 다시 읽은 레포', () => {
  it('C1 홈은 레포 목록을 읽은 뒤 새 활동 확인을 한 번만 부르고, 실패해도 홈은 그대로이며 오류 알림이 뜨지 않는다', async () => {
    let reposFail = false;
    const { calls } = connectMockServer((call) => {
      if (call.path === '/repo-updates/check') return jsonError(500, 'INTERNAL_ERROR');
      if (call.path === '/repo-updates') return jsonError(404, 'NOT_FOUND');
      if (call.path === '/repos' && reposFail) return jsonError(500, 'INTERNAL_ERROR');
    });
    const checks = () => calls.filter((call) => call.method === 'POST' && call.path === '/repo-updates/check');
    mountApp([page(<HomePage />, '/')], '/', queryClient);

    await screen.findByRole('heading', { level: 1, name: '오늘은 무엇을 할까요?' });
    await waitFor(() => expect(checks()).toHaveLength(1));
    expect(calls.findIndex((call) => call.path === '/repos')).toBeLessThan(calls.findIndex((call) => call.path === '/repo-updates/check'));
    expect(notice()).not.toBeInTheDocument();

    // 레포 목록을 다시 읽다 한 번 실패했다 돌아와도 같은 홈 화면에서는 다시 부르지 않는다
    reposFail = true;
    await act(() => queryClient.invalidateQueries({ queryKey: keys.repos }));
    await waitFor(() => expect(queryClient.getQueryState(keys.repos)?.status).toBe('error'));
    reposFail = false;
    await act(() => queryClient.invalidateQueries({ queryKey: keys.repos }));
    await waitFor(() => expect(queryClient.getQueryState(keys.repos)?.status).toBe('success'));
    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));

    expect(checks()).toHaveLength(1);
    expect(screen.getByRole('heading', { level: 1, name: '오늘은 무엇을 할까요?' })).toBeInTheDocument();
    expect(toast).not.toHaveBeenCalled();
  });

  it('C2 알림이 있으면 홈 맨 위에 레포마다 한 줄과 보러 가기 버튼을 보이고, 없으면 아무것도 보이지 않는다', async () => {
    connectMockServer();
    mountApp([page(<HomePage />, '/')], '/');
    await screen.findByRole('region', { name: '지금 상태' });
    expect(notice()).not.toBeInTheDocument();
    cleanup();

    addUpdate();
    addUpdate({ repoId: 'r_web', repoName: 'hong-dev/web-client', cardCount: 1, finishedAt: '2026-11-05T00:00:00Z' });
    connectMockServer();
    mountApp([page(<HomePage />, '/')], '/');

    const lines = await screen.findByRole('region', { name: '새로 써 둔 카드' });
    expect(within(lines).getAllByRole('listitem').map((item) => item.textContent?.trim())).toEqual([
      'hong-dev/auth-service에서 새로 한 일로 카드 3장을 써 뒀어요보러 가기',
      'hong-dev/web-client에서 새로 한 일로 카드 1장을 써 뒀어요보러 가기',
    ]);
    const after = (node: Element) => Boolean(screen.getByRole('heading', { level: 1 }).compareDocumentPosition(node) & Node.DOCUMENT_POSITION_FOLLOWING);
    expect(after(lines)).toBe(true);
    expect(lines.compareDocumentPosition(screen.getByRole('region', { name: '지금 상태' })) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('C3 보러 가기는 매칭이 켜져 있으면 다시 고르는 화면으로, 꺼져 있으면 카드 폴더로 가고 폴더로 갈 때만 그 자리에서 본 것으로 표시한다', async () => {
    const routes = [page(<HomePage />, '/'), ...pickPages];
    addUpdate();
    connectMockServer();
    let view = mountApp(routes, '/');

    fireEvent.click(await screen.findByRole('link', { name: '보러 가기' }));
    await screen.findByRole('heading', { level: 1 });
    expect(view.router.state.location.pathname + view.router.state.location.search).toBe(`${FOLDER}/match?again=1&cards=3`);
    cleanup();

    vi.stubEnv('VITE_API_MOCK', 'false');
    db.repoUpdates = [];
    addUpdate();
    const { calls } = connectMockServer();
    view = mountApp(routes, '/');

    fireEvent.click(await screen.findByRole('link', { name: '보러 가기' }));
    expect(await screen.findByText('폴더 화면')).toBeInTheDocument();
    expect(view.router.state.location.pathname + view.router.state.location.search).toBe(FOLDER);
    await waitFor(() => expect(calls.filter((call) => call.method === 'POST' && call.path === '/repo-updates/r_auth/seen')).toHaveLength(1));
  });

  it('C4 다시 고르러 들어오면 본 것으로 표시와 다시 고르기 요청을 한 번씩만 부르고, 둘 다 실패해도 지금 결과를 그대로 보인다', async () => {
    vi.spyOn(matchingApi, 'repoRecommendations').mockResolvedValue(ready([company(1)]));
    const { calls } = connectMockServer((call) => (call.path === '/repo-updates/r_auth/seen' || call.path === '/repos/r_auth/recommendations/refresh' ? jsonError(500, 'INTERNAL_ERROR') : undefined));
    mountApp(pickPages, `${FOLDER}/match?again=1&cards=3`, queryClient);

    expect(await screen.findByRole('heading', { level: 1, name: '새 카드 3장을 더해 다시 골랐어요' })).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: /고른 기업/ })).getByText('회사 1')).toBeInTheDocument();
    await act(() => new Promise((resolve) => setTimeout(resolve, 50)));
    expect(calls.filter((call) => call.method === 'POST' && call.path === '/repo-updates/r_auth/seen')).toHaveLength(1);
    expect(calls.filter((call) => call.method === 'POST' && call.path === '/repos/r_auth/recommendations/refresh')).toHaveLength(1);
    expect(toast).not.toHaveBeenCalled();
  });

  it('C5 다시 고르는 화면의 제목은 고르는 중이면 다시 고르고 있어요, 다 고르면 다시 골랐어요이고 장수가 없거나 숫자가 아니면 장수를 뺀다', () => {
    const head = (phase: Parameters<typeof pickHead>[0], search: string) =>
      pickHead(phase, { repoName: 'auth-service', cardCount: 5, picked: 2, again: againOf(new URLSearchParams(search)) }).title;

    expect(head('running', 'again=1&cards=3')).toBe('새 카드 3장을 더해 다시 고르고 있어요');
    expect(head('queued', 'again=1&cards=3')).toBe('새 카드 3장을 더해 다시 고르고 있어요');
    expect(head('done', 'again=1&cards=3')).toBe('새 카드 3장을 더해 다시 골랐어요');
    expect(head('done', 'again=1')).toBe('새 카드를 더해 다시 골랐어요');
    expect(head('running', 'again=1&cards=abc')).toBe('새 카드를 더해 다시 고르고 있어요');
    expect(head('done', 'cards=3')).toBe('맞는 기업 2곳을 골랐어요');
    expect(head('running', '')).toBe('맞는 기업을 고르고 있어요');
  });

  it('C6 고른 기업 가운데 fresh 인 회사에만 새로 들어옴 표시가 붙고, fresh 칸이 없으면 붙지 않는다', async () => {
    const answer = ready([company(1, { fresh: true }), company(2, { fresh: false }), company(3)]);
    expect(answer.companies.map((item) => item.fresh)).toEqual([true, false, false]);
    vi.spyOn(matchingApi, 'repoRecommendations').mockResolvedValue(answer);
    connectMockServer();
    mountApp(pickPages, `${FOLDER}/match`);

    const picks = await screen.findByRole('region', { name: '고른 기업 3곳' });
    const rows = within(picks).getAllByRole('listitem');
    expect(rows.map((row) => within(row).queryByText('새로 들어옴') !== null)).toEqual([true, false, false]);
  });

  it('C7 읽는 범위 확인 창에 정리한 뒤 새 커밋이 생기면 다시 읽어 카드 초안을 써 둔다는 한 줄이 있다', () => {
    const get = (path: string) => handle('GET', path, new URLSearchParams(), {}, true).data;
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    client.setQueryData(keys.repos, (get('/repos') as unknown[]).map((item) => RepoSummary.parse(item)));
    client.setQueryData(keys.repo('r_web'), RepoDetail.parse(get('/repos/r_web')));
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/repos?select=r_web&disclose=1']}><Routes><Route path="/repos" element={<ReposPage />} /></Routes></MemoryRouter></QueryClientProvider>);

    const dialog = screen.getByRole('dialog', { name: '읽는 범위 확인' });
    expect(within(dialog).getByText('정리한 뒤에 새 커밋이 생기면 Gitory가 다시 읽어 카드 초안을 써 둬요')).toBeInTheDocument();
  });
});

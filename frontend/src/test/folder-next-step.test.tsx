import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { Card, type InterviewTurn } from '@/api/schemas';
import { CardPage } from '@/features/cards/CardPage';
import { ActivityFolderPage, DirectFolderPage, RepoFolderPage } from '@/features/cards/FolderPage';
import { InterviewPage } from '@/features/cards/InterviewPage';
import { answeredBy, appliedReply } from './replyResult';
import { folderNext } from '@/lib/folderNext';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';
import { connectMockServer } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

const routes = (): RouteObject[] => [
  { path: '/cards/repo/:repoId', element: <RepoFolderPage /> },
  { path: '/cards/activity/:activityId', element: <ActivityFolderPage /> },
  { path: '/cards/direct', element: <DirectFolderPage /> },
  { path: '/cards/:cardId', element: <CardPage /> },
  { path: '/cards/:cardId/interview', element: <InterviewPage /> },
];
const newClient = () => new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });

function mount(entry: string | { pathname: string; state: unknown }, options: { client?: QueryClient; routes?: RouteObject[] } = {}) {
  const client = options.client ?? newClient();
  const router = createMemoryRouter(options.routes ?? routes(), { initialEntries: [entry] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  return { client, router };
}
const load = (id: string) => Card.parse(handle('GET', `/cards/${id}`, new URLSearchParams(), {}, true).data);
const strip = () => screen.findByLabelText('다음에 할 일');
const stripTitle = (element: HTMLElement) => element.querySelector('strong')!;

describe('확정하면 카드가 든 폴더로 돌아가 다음에 할 일 하나를 듣는다', () => {
  it('확정 창에서 확정하면 그 카드의 폴더로 가고 방금 확정한 카드를 표시한다', async () => {
    connectMockServer();
    const { router } = mount('/cards/card_01?confirm=1');
    // 폴더로 가기 전에 카드 화면이 폴더로 돌아가기 안내를 잠깐이라도 띄우지 않는다
    let flashed = false;
    const watch = new MutationObserver(() => { flashed ||= document.body.textContent!.includes('폴더로 돌아가기'); });
    watch.observe(document.body, { childList: true, subtree: true, characterData: true });

    fireEvent.click(await screen.findByRole('button', { name: '확정하기' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'auth-service' })).toBeInTheDocument();
    watch.disconnect();
    expect(flashed).toBe(false);
    expect(router.state.location.pathname).toBe('/cards/repo/r_auth');
    expect(router.state.location.state).toEqual({ confirmed: 'card_01' });
    const marked = document.querySelectorAll('.experience-item--just');
    expect(marked).toHaveLength(1);
    expect(marked[0]).toHaveAttribute('href', '/cards/card_01');
    expect(within(marked[0] as HTMLElement).getByText('방금 확정한 카드')).toBeInTheDocument();
  });

  it('확인할 카드가 있으면 장수를 말하고 버튼은 화면 순서로 첫 초안 카드를 연다', async () => {
    connectMockServer();
    // 경험 카드를 더 최근에 고쳤어도 화면은 기술 카드 구역이 먼저라, 첫 초안은 기술 카드의 것이다
    db.cards.find((card) => card.id === 'card_02')!.updatedAt = new Date().toISOString();
    mount('/cards/repo/r_auth');

    const next = await strip();
    expect(stripTitle(next)).toHaveTextContent('확인할 카드가 2장 있어요');
    expect(next).toHaveTextContent('2장은 바로 쓸 수 있어요. 나머지는 한두 가지만 알려 주면 돼요.');
    expect(within(next).getByRole('link', { name: '확인할 카드 보기' })).toHaveAttribute('href', '/cards/card_01');
  });

  it('카드가 모두 확정이면 공고로 보내고, 공고 매칭이 꺼져 있으면 자소서 쓰기로 보낸다', async () => {
    connectMockServer();
    vi.stubEnv('VITE_API_MOCK', 'true');
    mount('/cards/activity/act_edu');

    const on = await strip();
    expect(stripTitle(on)).toHaveTextContent('카드 1장이 준비됐어요');
    expect(within(on).getByRole('link', { name: '맞는 공고 보기' })).toHaveAttribute('href', '/match');
    expect(within(on).getByRole('link', { name: '자소서 쓰기' })).toHaveAttribute('href', '/cover-letters/new');
    cleanup();

    vi.stubEnv('VITE_API_MOCK', 'false');
    vi.stubEnv('VITE_MATCH_ENABLED', 'false');
    mount('/cards/activity/act_edu');
    const off = await strip();
    expect(within(off).queryByRole('link', { name: '맞는 공고 보기' })).not.toBeInTheDocument();
    expect(within(off).getByRole('link', { name: '자소서 쓰기' })).toHaveAttribute('href', '/cover-letters/new');
    expect(within(off).getByRole('link', { name: '자소서 쓰기' })).toHaveClass('btn--primary');
  });

  it("방금 확정하고 돌아오면 제목이 '카드를 확정했어요' 로 시작한다", async () => {
    connectMockServer();
    mount({ pathname: '/cards/repo/r_auth', state: { confirmed: 'card_03' } });
    const review = await strip();
    expect(stripTitle(review)).toHaveTextContent(/^카드를 확정했어요. 확인할 카드가 2장 남았어요$/);
    expect(within(review).getByRole('link', { name: '다음 카드 보기' })).toBeInTheDocument();
    expect(review).toHaveAttribute('role', 'status');
    cleanup();

    mount({ pathname: '/cards/activity/act_edu', state: { confirmed: 'card_a3' } });
    const ready = await strip();
    expect(stripTitle(ready)).toHaveTextContent(/^카드를 확정했어요. 이 폴더의 카드를 모두 쓸 수 있어요$/);
    expect(ready).toHaveAttribute('role', 'status');
    cleanup();

    // 돌아온 표시가 없거나 이 폴더의 카드가 아니면 평소 제목이고 스크린리더에도 읽어 주지 않는다
    mount({ pathname: '/cards/activity/act_edu', state: { confirmed: 'card_01' } });
    const other = await strip();
    expect(stripTitle(other)).toHaveTextContent(/^카드 1장이 준비됐어요$/);
    expect(other).not.toHaveAttribute('role');
    expect(document.querySelector('.experience-item--just')).toBeNull();
  });

  it('폴더에 카드가 없으면 다음 걸음 줄이 없다', async () => {
    connectMockServer();
    mount({ pathname: '/cards/activity/act_contest', state: { confirmed: 'card_01' } });

    await screen.findByRole('button', { name: '경험 카드 만들기' });
    expect(screen.queryByLabelText('다음에 할 일')).not.toBeInTheDocument();
    expect(folderNext({ cards: [], justConfirmed: true, matchOn: true })).toBeNull();
  });

  it('되묻기 답으로 확정되면 폴더로 돌아가기 버튼이 보인다', async () => {
    const original = load('card_01');
    const card = { ...original, version: { ...original.version, task: null, result: null } };
    const template = handle('POST', '/cards/card_01/interview', new URLSearchParams(), { field: 'R' }, true).data as InterviewTurn;
    let turns: InterviewTurn[] = [{ ...template, field: 'R', turnNo: 1, question: 'R question', answer: null, outcome: null }];
    vi.spyOn(endpoints, 'interviewTurns').mockImplementation(async () => turns);
    vi.spyOn(endpoints, 'replyInterview').mockImplementation(async (_id, no, body) => {
      turns = turns.map((turn) => turn.turnNo === no ? answeredBy(turn, body) : turn);
      return appliedReply({ ...card, status: 'CONFIRMED', confirmedAt: '2026-11-01T00:00:00Z' });
    });
    const client = newClient();
    client.setQueryData(keys.card(card.id), card);
    client.setQueryData(keys.interview(card.id), turns);
    const { router } = mount(`/cards/${card.id}/interview`, { client, routes: [
      { path: '/cards/:cardId/interview', element: <InterviewPage /> },
      { path: '/cards/repo/:repoId', element: <p>레포 폴더 화면</p> },
    ] });

    fireEvent.change(await screen.findByRole('textbox', { name: '답변' }), { target: { value: 'R answer' } });
    fireEvent.click(screen.getByRole('button', { name: '답 보내기' }));

    expect(await screen.findByText('카드를 확정했어요')).toBeInTheDocument();
    expect(screen.getByText('상황과 행동이 있어서 바로 쓸 수 있어요.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '카드 보기' })).toHaveClass('btn--secondary');
    expect(screen.queryByRole('button', { name: '직접 수정하기' })).not.toBeInTheDocument();
    expect(within(screen.getByRole('navigation', { name: 'breadcrumb' })).getByRole('link', { name: 'auth-service' })).toHaveAttribute('href', '/cards/repo/r_auth');
    fireEvent.click(screen.getByRole('button', { name: '폴더로 돌아가기' }));
    await screen.findByText('레포 폴더 화면');
    expect(router.state.location.pathname).toBe('/cards/repo/r_auth');
    expect(router.state.location.state).toEqual({ confirmed: card.id });
  });

  it('카드 화면에서 초안이 확정으로 바뀌면 폴더로 돌아가기가 보이고, 처음부터 확정된 카드에는 보이지 않는다', async () => {
    connectMockServer();
    const draft = load('card_01');
    const { client, router } = mount('/cards/card_01');
    await screen.findByRole('button', { name: '확정' });
    expect(screen.queryByRole('link', { name: '폴더로 돌아가기' })).not.toBeInTheDocument();

    // 서버가 확정한 상황이라 서버(목)의 카드도 확정이다 — 화면이 카드를 다시 읽어도 확정이 돌아온다
    Object.assign(db.cards.find((card) => card.id === draft.id)!, { status: 'CONFIRMED', confirmedAt: '2026-11-01T00:00:00Z' });
    act(() => { client.setQueryData(keys.card(draft.id), { ...draft, status: 'CONFIRMED', confirmedAt: '2026-11-01T00:00:00Z' }); });

    const back = await screen.findByRole('link', { name: '폴더로 돌아가기' });
    expect(back).toHaveAttribute('href', '/cards/repo/r_auth');
    const panel = back.closest('.done-card') as HTMLElement;
    expect(panel).toHaveAttribute('role', 'status');
    expect(panel).toHaveTextContent('카드를 확정했어요');
    expect(panel).toHaveTextContent('상황과 행동이 있어서 바로 쓸 수 있어요.');
    expect(within(panel).getAllByRole('link')).toHaveLength(1);
    expect(screen.queryByRole('button', { name: /남은 후보/ })).not.toBeInTheDocument();

    // 다른 카드로 옮기면 안내는 사라진다
    await act(async () => { await router.navigate('/cards/card_03'); });
    await screen.findByRole('heading', { level: 1, name: '토큰 만료 자동 재발급' });
    expect(screen.queryByRole('link', { name: '폴더로 돌아가기' })).not.toBeInTheDocument();
    cleanup();

    mount('/cards/card_03');
    await screen.findByRole('heading', { level: 1, name: '토큰 만료 자동 재발급' });
    expect(screen.queryByRole('link', { name: '폴더로 돌아가기' })).not.toBeInTheDocument();
    expect(document.querySelector('.done-card')).toBeNull();
  });

  it('카드 화면의 위치 줄과 나중에는 폴더로 간다', async () => {
    connectMockServer();
    const reviewed = (id: string) => {
      const card = load(id);
      return { ...card, version: { ...card.version, situation: '상황', task: '과제', action: '행동', result: '결과' }, lowConfidenceFields: [], droppedFields: [] };
    };
    const crumbs = () => within(screen.getByRole('navigation', { name: 'breadcrumb' }));
    const open = async (card: Card) => {
      const client = newClient();
      client.setQueryData(keys.card(card.id), card);
      mount(`/cards/${card.id}`, { client });
      return screen.findByRole('link', { name: '나중에' });
    };

    const repoCard = reviewed('card_01');
    const later = await open(repoCard);
    expect(later).toHaveAttribute('href', '/cards/repo/r_auth');
    expect(crumbs().getByRole('link', { name: '경험 카드' })).toHaveAttribute('href', '/cards');
    expect(crumbs().getByRole('link', { name: 'auth-service' })).toHaveAttribute('href', '/cards/repo/r_auth');
    expect(crumbs().queryByRole('link', { name: '후보 보드' })).not.toBeInTheDocument();
    cleanup();

    const activityCard = reviewed('card_a2');
    expect(await open(activityCard)).toHaveAttribute('href', '/cards/activity/act_club');
    expect(crumbs().getByRole('link', { name: '교내 개발 동아리' })).toHaveAttribute('href', '/cards/activity/act_club');
    cleanup();

    expect(await open({ ...activityCard, activity: null })).toHaveAttribute('href', '/cards/direct');
    expect(crumbs().getAllByRole('link').map((link) => link.getAttribute('href'))).toEqual(['/', '/cards']);
  });
});

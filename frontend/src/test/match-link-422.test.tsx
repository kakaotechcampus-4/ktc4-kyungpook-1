import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { matchingApi, MatchDetail, MatchTarget } from '@/api/matching';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { Card, ConfirmResult } from '@/api/schemas';
import { CardPage } from '@/features/cards/CardPage';
import { MatchDetailPage } from '@/features/match/MatchDetailPage';
import { MatchListPage } from '@/features/match/MatchListPage';
import { track } from '@/lib/track';
import { handle } from '@/mock/router';
import { cardKindLabel } from '@/lib/labels';
import { resetDb } from '@/mock/store';
import sampleList from '../../docs/examples/matching/list.json';
import sampleDetail from '../../docs/examples/matching/detail.json';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/jobWatcher', () => ({ releaseJobToast: vi.fn(), suppressJobToast: vi.fn() }));

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.mocked(track).mockClear(); vi.unstubAllEnvs(); });

function mount(path: string, seed?: (client: QueryClient) => void) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  seed?.(client);
  const router = createMemoryRouter([
    { path: '/match', element: <MatchListPage /> },
    { path: '/match/:matchId', element: <MatchDetailPage /> },
    { path: '/cards/:cardId', element: <CardPage /> },
  ], { initialEntries: [path] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  return { client, router };
}

const detail = MatchDetail.parse(sampleDetail.data);
const firstMet = detail.conditions.flatMap((condition) => condition.metBy)[0];

it('sends match_opened once when the match list loads', async () => {
  vi.spyOn(matchingApi, 'list').mockResolvedValue(sampleList.data.map((x) => MatchTarget.parse(x)));
  mount('/match');
  await screen.findByText('가온소프트');
  await waitFor(() => expect(track).toHaveBeenCalledWith('match_opened', { view: 'list', count: sampleList.data.length }));
  expect(vi.mocked(track).mock.calls.filter(([name]) => name === 'match_opened')).toHaveLength(1);
});

it('sends match_opened for the detail and records evidence card and source clicks', async () => {
  vi.spyOn(matchingApi, 'detail').mockResolvedValue(detail);
  mount(`/match/${detail.id}`);
  await screen.findByTitle(firstMet.reason);
  await waitFor(() => expect(track).toHaveBeenCalledWith('match_opened', { view: 'detail', matchId: detail.id, fit: detail.fit }));

  fireEvent.click(screen.getByRole('link', { name: /공고 원문 보기/ }));
  expect(track).toHaveBeenCalledWith('match_source_opened', { matchId: detail.id });

  fireEvent.click(screen.getByTitle(firstMet.reason));
  expect(track).toHaveBeenCalledWith('match_card_opened', { matchId: detail.id, cardId: firstMet.cardId });
});

it('shows each evidence card kind on the match detail', async () => {
  vi.spyOn(matchingApi, 'detail').mockResolvedValue(detail);
  mount(`/match/${detail.id}`);
  const link = await screen.findByTitle(firstMet.reason);
  expect(within(link).getByText(cardKindLabel[firstMet.cardKind])).toBeInTheDocument();
});

async function confirmCard() {
  const card = Card.parse(handle('GET', '/cards/card_01', new URLSearchParams(), {}, true).data);
  const result = ConfirmResult.parse(handle('POST', '/cards/card_01/confirm', new URLSearchParams(), { edited: false, maskedFields: [] }, true).data);
  vi.spyOn(endpoints, 'confirm').mockResolvedValue(result);
  const detail = vi.spyOn(endpoints, 'card').mockResolvedValue(card);
  mount(`/cards/${card.id}?confirm=1`, (client) => client.setQueryData(keys.card(card.id), card));
  detail.mockResolvedValue({ ...card, status: 'CONFIRMED', confirmedAt: '2026-10-25T00:00:00Z' });
  fireEvent.click(await screen.findByRole('button', { name: '확정하기' }));
  // 확정 직후 배너가 떠야 다음 행동을 고를 수 있다
  await waitFor(() => expect(document.querySelector('.done-card')).not.toBeNull());
}

it('offers matching postings right after confirming when matching is on', async () => {
  vi.stubEnv('VITE_API_MOCK', 'false'); vi.stubEnv('VITE_MATCH_ENABLED', 'true');
  await confirmCard();
  expect(screen.getByRole('link', { name: '이 경험과 맞는 공고 보기' })).toHaveAttribute('href', '/match');
});

it('does not offer matching postings after confirming when matching is off', async () => {
  vi.stubEnv('VITE_API_MOCK', 'false'); vi.stubEnv('VITE_MATCH_ENABLED', 'false');
  await confirmCard();
  expect(screen.queryByRole('link', { name: '이 경험과 맞는 공고 보기' })).not.toBeInTheDocument();
});

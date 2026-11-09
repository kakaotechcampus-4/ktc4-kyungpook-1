import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { useCard } from '@/api/queries';
import { matchingApi, MatchTarget, OverallRecommendation } from '@/api/matching';
import { Me, type Card } from '@/api/schemas';
import { MatchListPage } from '@/features/match/MatchListPage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';
import sampleList from '../../docs/examples/matching/list.json';

// 멘토 점검(11/4): 카드를 만들고 공고 매칭에 들어가면 "확정한 카드가 없어요"가 뜨고, 새로고침하면 풀린다.
// 서버가 카드를 스스로 확정해도(자동 확정) 내 정보(me)는 5분 동안 새로 읽지 않아 0장으로 남아 있었다.
beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const meWith = (confirmedCards: number) => {
  const me = handle('GET', '/me', new URLSearchParams(), {}, true).data as Record<string, unknown> & { stats: object };
  return Me.parse({ ...me, stats: { ...me.stats, confirmedCards } });
};
const none = OverallRecommendation.parse({ status: 'NONE', computedAt: null, stale: false, progress: null, companies: [] });
const done = OverallRecommendation.parse({ status: 'READY', computedAt: '2026-11-02T08:27:00Z', stale: false, progress: null, checked: 15, companies: [] });
const postings = [MatchTarget.parse({ ...sampleList.data[0], id: 'p1', fit: null })];

function mountMatch(cached: number | null, server: number) {
  vi.spyOn(matchingApi, 'list').mockResolvedValue(postings);
  vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(none);
  // 확정 카드가 있으면 들어오자마자 스스로 맞춰 보기를 시작한다 — 이 시험은 카드 수를 보므로 시작은 바로 끝난 것으로 둔다
  const start = vi.spyOn(matchingApi, 'startRecommendations').mockResolvedValue(done);
  const me = vi.spyOn(endpoints, 'me').mockResolvedValue(meWith(server));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  if (cached !== null) client.setQueryData(keys.me, meWith(cached));
  const router = createMemoryRouter([{ path: '/match', element: <MatchListPage /> }], { initialEntries: ['/match'] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  return { me, start, client };
}
const box = () => screen.getByRole('region', { name: '내 카드로 맞춰 본 공고' });

describe('공고 매칭의 확정 카드 수', () => {
  it('0장으로 남은 값이 있어도 서버의 새 값을 읽고, 읽는 동안에는 "없어요"라고 말하지 않는다', async () => {
    const { me, start } = mountMatch(0, 2);
    // 다시 읽는 중에는 아직 모른다 — 틀린 안내(없어요)도, 카드 확정하러 가기 버튼도 뜨지 않는다
    expect(box()).not.toHaveTextContent('확정한 카드가 없어요');
    expect(screen.queryByRole('link', { name: '카드 확정하러 가기' })).not.toBeInTheDocument();
    await waitFor(() => expect(box()).toHaveTextContent('확정 카드 2장과'));
    expect(me).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('link', { name: '카드 확정하러 가기' })).not.toBeInTheDocument();
    // 확정 카드가 있다고 확인되면 누르지 않아도 맞춰 보기를 시작하고, 끝나면 버튼은 다시 맞춰 보기가 된다
    expect(await screen.findByRole('button', { name: '다시 맞춰 보기' })).toBeInTheDocument();
    expect(start).toHaveBeenCalledTimes(1);
  });

  it('정말 확정한 카드가 없으면 그대로 알려 주고 카드 확정으로 안내한다', async () => {
    mountMatch(0, 0);
    await waitFor(() => expect(box()).toHaveTextContent('확정한 카드가 없어요'));
    expect(screen.getByRole('link', { name: '카드 확정하러 가기' })).toBeInTheDocument();
  });

  it('이미 확정한 카드가 있다고 캐시돼 있으면 내 정보를 다시 읽지 않는다', async () => {
    const { me } = mountMatch(3, 3);
    await waitFor(() => expect(box()).toHaveTextContent('확정 카드 3장과'));
    expect(me).not.toHaveBeenCalled();
  });
});

describe('카드 화면에서 카드가 확정으로 바뀌면', () => {
  const draft = () => {
    const original = handle('GET', '/cards/card_01', new URLSearchParams(), {}, true).data as Card;
    return { ...original, status: 'DRAFT', generation: null } as Card;
  };
  function Probe({ id }: { id: string }) { useCard(id); return null; }
  function mountCard(initial: Card) {
    vi.spyOn(endpoints, 'card').mockResolvedValue(initial);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
    client.setQueryData(keys.card(initial.id), initial);
    client.setQueryData(keys.me, meWith(0));
    client.setQueryData(keys.cards, []);
    render(<QueryClientProvider client={client}><Probe id={initial.id} /></QueryClientProvider>);
    return client;
  }
  const stale = (client: QueryClient, key: readonly unknown[]) => client.getQueryState(key)?.isInvalidated === true;

  it('서버가 자동으로 확정하면 확정 카드 수와 카드 목록을 다시 읽게 한다', async () => {
    const card = draft();
    const client = mountCard(card);
    expect(stale(client, keys.me)).toBe(false);
    act(() => { client.setQueryData(keys.card(card.id), { ...card, status: 'CONFIRMED' }); });
    await waitFor(() => expect(stale(client, keys.me)).toBe(true));
    expect(stale(client, keys.cards)).toBe(true);
  });

  it('처음부터 확정된 카드를 열 때는 아무것도 다시 읽지 않는다', async () => {
    const card = { ...draft(), status: 'CONFIRMED' } as Card;
    const client = mountCard(card);
    await act(async () => { await Promise.resolve(); });
    expect(stale(client, keys.me)).toBe(false);
    expect(stale(client, keys.cards)).toBe(false);
  });
});

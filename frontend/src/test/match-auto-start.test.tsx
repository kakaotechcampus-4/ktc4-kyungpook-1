import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, MemoryRouter, Route, RouterProvider, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { MatchDetail, matchingApi, matchKeys, MatchTarget, OverallRecommendation } from '@/api/matching';
import { CardSummary, Me } from '@/api/schemas';
import { MatchDetailPage } from '@/features/match/MatchDetailPage';
import { MatchListPage } from '@/features/match/MatchListPage';
import { rememberProceeded } from '@/features/match/gapFill';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';
import sampleDetail from '../../docs/examples/matching/detail.json';
import sampleList from '../../docs/examples/matching/list.json';

// 멘토 점검(11/4): 카드를 만든 뒤에도 "내 조건으로 맞춰 보기"와 "이 공고와 맞춰 보기"를 눌러야 했다.
// 화면에 들어오면 필요할 때 스스로 시작하고, 버튼은 시작하지 못했을 때를 위해 남긴다.
vi.mock('@/lib/track', () => ({ track: vi.fn() }));
beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

// 한 번도 더 불리지 않았는지 보려고, 늦게 오는 호출이 있을 만큼 기다린다
const settle = () => act(async () => { await new Promise((resolve) => setTimeout(resolve, 30)); });

const state = (over: Record<string, unknown> = {}) =>
  OverallRecommendation.parse({ status: 'NONE', computedAt: null, stale: false, progress: null, companies: [], ...over });
const ready = (over: Record<string, unknown> = {}) => state({ status: 'READY', computedAt: '2026-11-02T08:27:00Z', checked: 15, ...over });
const computing = state({ status: 'COMPUTING', progress: { done: 1, total: 5 } });
const postings = [MatchTarget.parse({ ...sampleList.data[0], id: 'p1', fit: null })];
const meWith = (confirmedCards: number) => {
  const me = handle('GET', '/me', new URLSearchParams(), {}, true).data as Record<string, unknown> & { stats: object };
  return Me.parse({ ...me, stats: { ...me.stats, confirmedCards } });
};

/** confirmed 가 'unknown' 이면 내 정보가 오지 않아 확정 카드 수를 끝내 모른다 */
function mountList(overall: OverallRecommendation, confirmed: number | 'unknown') {
  vi.spyOn(matchingApi, 'list').mockResolvedValue(postings);
  vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(overall);
  vi.spyOn(endpoints, 'me').mockImplementation(() => (confirmed === 'unknown' ? new Promise(() => {}) : Promise.resolve(meWith(confirmed))));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  if (confirmed !== 'unknown') client.setQueryData(keys.me, meWith(confirmed));
  const router = createMemoryRouter([{ path: '/match', element: <MatchListPage /> }], { initialEntries: ['/match'] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
}

describe('공고 목록 위 맞춰 본 공고 상자', () => {
  it('확정 카드가 있고 맞춰 본 적이 없으면 들어오자마자 맞춰 보기를 시작한다', async () => {
    const start = vi.spyOn(matchingApi, 'startRecommendations').mockResolvedValue(computing);
    mountList(state(), 3);
    expect(await screen.findByText('공고 5곳 중 1곳을 확인했어요')).toBeInTheDocument();
    expect(start).toHaveBeenCalledTimes(1);
  });

  it('결과가 있어도 카드나 인적사항이 바뀌었으면 들어오자마자 다시 맞춰 본다', async () => {
    const start = vi.spyOn(matchingApi, 'startRecommendations').mockResolvedValue(computing);
    mountList(ready({ stale: true }), 3);
    expect(await screen.findByText('공고 5곳 중 1곳을 확인했어요')).toBeInTheDocument();
    expect(start).toHaveBeenCalledTimes(1);
  });

  it('결과가 최신이거나 이미 맞춰 보는 중이면 시작하지 않는다', async () => {
    const start = vi.spyOn(matchingApi, 'startRecommendations').mockResolvedValue(computing);
    mountList(ready(), 3);
    expect(await screen.findByRole('button', { name: '다시 맞춰 보기' })).toBeEnabled();
    cleanup();
    mountList(computing, 3);
    expect(await screen.findByText('공고 5곳 중 1곳을 확인했어요')).toBeInTheDocument();
    await settle();
    expect(start).not.toHaveBeenCalled();
  });

  it('확정 카드가 없거나 아직 모르면 시작하지 않는다', async () => {
    const start = vi.spyOn(matchingApi, 'startRecommendations').mockResolvedValue(computing);
    mountList(state(), 0);
    expect(await screen.findByText(/확정한 카드가 없어요/)).toBeInTheDocument();
    cleanup();
    mountList(state(), 'unknown');
    await waitFor(() => expect(screen.getByRole('button', { name: '내 조건으로 맞춰 보기' })).toBeEnabled());
    await settle();
    expect(start).not.toHaveBeenCalled();
  });

  it('스스로 시작은 한 번뿐이라 실패하면 알림과 버튼만 남는다', async () => {
    // 실제 요청처럼 잠시 걸린 뒤 거절돼야 '맞춰 보는 중' 이 화면에 그려졌다 사라지는 사이에도 다시 시작하지 않는지 볼 수 있다
    const start = vi.spyOn(matchingApi, 'startRecommendations').mockImplementation(
      () => new Promise((_, reject) => setTimeout(() => reject(new ApiError('RATE_LIMITED', '잠시 뒤에 다시 시도해 주세요.', 429)), 10)),
    );
    mountList(state(), 3);
    expect(await screen.findByRole('alert')).toHaveTextContent('맞춰 보기를 시작하지 못했어요.');
    expect(screen.getByRole('button', { name: '내 조건으로 맞춰 보기' })).toBeEnabled();
    await settle();
    expect(start).toHaveBeenCalledTimes(1);
  });
});

const judged = () => MatchDetail.parse(sampleDetail.data);
const unjudged = (): MatchDetail => {
  const detail = judged();
  return {
    ...detail, needsJudgement: true, summary: null,
    conditions: detail.conditions.map((c) => (c.status === 'MET' || c.status === 'NOT_MET' ? { ...c, status: 'UNJUDGED' as const, metBy: [] } : c)),
  };
};
const confirmedCards = () => (handle('GET', '/cards', new URLSearchParams(), {}, true).data as unknown[]).map((c) => CardSummary.parse(c)).filter((c) => c.status === 'CONFIRMED');
const draftCards = () => confirmedCards().map((card) => ({ ...card, status: 'DRAFT' as const }));

function mountDetail(detail: MatchDetail, cards: CardSummary[]) {
  vi.spyOn(matchingApi, 'detail').mockResolvedValue(detail);
  vi.spyOn(endpoints, 'cards').mockResolvedValue(cards);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  client.setQueryData(keys.cards, cards);
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/match/${detail.id}`]}>
        <Routes><Route path="/match/:matchId" element={<MatchDetailPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return client;
}

describe('공고 상세', () => {
  // 진행하기에서 돌아왔다는 표시는 세션 저장소에 남으므로 다음 시험으로 넘기지 않는다
  afterEach(() => { sessionStorage.clear(); });

  it('확정 카드가 있고 아직 맞춰 보지 않은 공고를 열면 누르지 않아도 맞춰 본다', async () => {
    const detail = unjudged();
    const judge = vi.spyOn(matchingApi, 'judge').mockReturnValue(new Promise<MatchDetail>(() => {}));
    mountDetail(detail, confirmedCards());
    expect(await screen.findByText(/읽고 조건과 견주고 있어요/)).toBeInTheDocument();
    expect(judge).toHaveBeenCalledTimes(1);
    expect(judge).toHaveBeenCalledWith(detail.id);
  });

  it('확정 카드가 없으면 맞춰 보지 않는다', async () => {
    const judge = vi.spyOn(matchingApi, 'judge');
    mountDetail(unjudged(), draftCards());
    await screen.findByRole('button', { name: '이 공고와 맞춰 보기' });
    await settle();
    expect(judge).not.toHaveBeenCalled();
  });

  it('이미 맞춰 본 공고는 다시 맞춰 보지 않는다', async () => {
    const judge = vi.spyOn(matchingApi, 'judge');
    mountDetail(judged(), confirmedCards());
    await screen.findByRole('link', { name: '이 공고로 자소서 쓰기' });
    await settle();
    expect(judge).not.toHaveBeenCalled();
  });

  it('스스로 맞춰 보기가 실패하면 버튼이 남고 다시 시작하지 않는다', async () => {
    const detail = unjudged();
    const judge = vi.spyOn(matchingApi, 'judge').mockRejectedValue(new ApiError('AI_UNAVAILABLE', 'unavailable', 503));
    const client = mountDetail(detail, confirmedCards());
    expect(await screen.findByText('맞춰 보지 못했어요. 잠시 뒤 다시 눌러 주세요.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '이 공고와 맞춰 보기' })).toBeEnabled();
    // 공고를 다시 읽어 와도 되풀이하지 않는다
    act(() => { client.setQueryData(matchKeys.detail(detail.id), { ...detail, role: `${detail.role} (수정)` }); });
    await settle();
    expect(judge).toHaveBeenCalledTimes(1);
  });

  it('진행하기에서 돌아온 공고는 스스로 맞춰 보기와 겹치지 않고 한 번만 다시 맞춰 본다', async () => {
    const detail = unjudged();
    const cards = confirmedCards();
    rememberProceeded(detail.id, judged().conditions.filter((c) => c.status === 'NOT_MET').map((c) => c.id));
    vi.spyOn(matchingApi, 'detail').mockResolvedValue(detail);
    vi.spyOn(endpoints, 'cards').mockResolvedValue(cards);
    const judge = vi.spyOn(matchingApi, 'judge').mockResolvedValue(judged());
    // 공고와 카드 목록이 첫 렌더부터 있으면 돌아왔다는 상태(returned)는 아직 비어 있어서, 저장된 표시를 바로 읽어야 겹치지 않는다
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    client.setQueryData(matchKeys.detail(detail.id), detail);
    client.setQueryData(keys.cards, cards);
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[`/match/${detail.id}`]}>
          <Routes><Route path="/match/:matchId" element={<MatchDetailPage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await screen.findByText(/모자랐던 조건 \d+개 중 \d+개를 채웠어요/);
    await settle();
    expect(judge).toHaveBeenCalledTimes(1);
  });
});

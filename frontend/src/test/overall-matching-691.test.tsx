import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { matchingApi, OverallRecommendation } from '@/api/matching';
import { OverallMatch } from '@/features/match/OverallMatch';

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const company = (i: number, over: Record<string, unknown> = {}) => ({
  companyId: `c${i}`, companyName: `회사${i}`, logoUrl: null,
  posting: { id: `p${i}`, title: `백엔드 개발자 ${i}`, deadline: null, closed: false },
  requiredMet: 2, requiredTotal: 3, preferredMet: 1, preferredTotal: 2, ...over,
});
const state = (over: Record<string, unknown> = {}) =>
  OverallRecommendation.parse({ status: 'NONE', computedAt: null, stale: false, progress: null, companies: [], ...over });
const ready = (over: Record<string, unknown> = {}) => state({ status: 'READY', computedAt: '2026-11-02T08:27:00Z', companies: [company(1), company(2)], ...over });

// 지난 결과를 읽는 동안에는 버튼이 잠겨 있다 — 풀린 뒤에 누른다
async function startButton() {
  const button = await screen.findByRole('button', { name: '내 조건으로 매칭하기' });
  await waitFor(() => expect(button).toBeEnabled());
  return button;
}

function mount() {
  const router = createMemoryRouter([
    { path: '/match', element: <OverallMatch /> },
    { path: '/match/:id', element: <p>공고 상세</p> },
  ], { initialEntries: ['/match'] });
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}><RouterProvider router={router} /></QueryClientProvider>);
  return router;
}

describe('#691 나와 맞는 기업(전체 매칭)', () => {
  it('한 번도 매칭하지 않았으면 안내와 내 조건으로 매칭하기만 있다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(state());
    mount();
    expect(await startButton()).toBeEnabled();
    expect(screen.getByText(/확정한 경험 카드와 인적사항으로 맞는 기업을 골라 드려요/)).toBeInTheDocument();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
  });

  it('누르면 계산을 시작하고, 계산 중에는 확인한 공고 수를 보이며 버튼을 막는다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(state());
    const start = vi.spyOn(matchingApi, 'startRecommendations').mockResolvedValue(state({ status: 'COMPUTING', progress: { done: 2, total: 5 } }));
    mount();
    fireEvent.click(await startButton());

    expect(await screen.findByRole('status')).toHaveTextContent('공고 5곳 중 2곳을 확인했어요');
    expect(start).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('progressbar', { name: '전체 매칭 진행률' })).toHaveAttribute('aria-valuenow', '40');
    expect(screen.getByRole('button', { name: '매칭하는 중' })).toBeDisabled();
  });

  it('계산 중에는 결과를 다시 읽어, 끝나면 기업을 보여 준다', async () => {
    const read = vi.spyOn(matchingApi, 'recommendations')
      .mockResolvedValueOnce(state({ status: 'COMPUTING', progress: { done: 1, total: 5 } }))
      .mockResolvedValue(ready());
    mount();
    expect(await screen.findByRole('status')).toHaveTextContent('공고 5곳 중 1곳을 확인했어요');

    expect(await screen.findByRole('link', { name: /회사1/ }, { timeout: 4000 })).toBeInTheDocument();
    expect(read.mock.calls.length).toBeGreaterThanOrEqual(2);
    expect(screen.getByRole('button', { name: '다시 매칭하기' })).toBeEnabled();
  });

  it('끝나면 기업마다 대표 공고와 맞은 필수 조건 수를 보이고, 누르면 그 공고 상세로 간다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready());
    const router = mount();
    const link = await screen.findByRole('link', { name: /회사1/ });
    expect(link).toHaveTextContent('백엔드 개발자 1');
    expect(link).toHaveTextContent('필수 조건 3개 중 2개가 맞아요, 우대 2개 중 1개');
    expect(screen.getByText('마지막 매칭 11월 2일 17:27')).toBeInTheDocument();
    fireEvent.click(link);

    expect(await screen.findByText('공고 상세')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/match/p1');
  });

  it('기술·경험 필수 조건이 없는 공고는 0개 중 0개라고 쓰지 않는다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready({ companies: [company(1, { requiredMet: 0, requiredTotal: 0, preferredMet: 0, preferredTotal: 0 })] }));
    mount();
    const link = await screen.findByRole('link', { name: /회사1/ });
    expect(link).toHaveTextContent('기술·경험 필수 조건이 없어요');
    expect(link).not.toHaveTextContent('0개 중');
  });

  it('카드나 인적사항이 바뀌었으면 다시 매칭하라고 알린다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready({ stale: true }));
    mount();
    expect(await screen.findByText('카드나 인적사항이 바뀌었어요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 매칭하기' })).toBeEnabled();
  });

  it('계산에 실패하면 알리고 지난 결과는 그대로 둔다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready({ status: 'FAILED' }));
    mount();
    expect(await screen.findByText('매칭을 끝내지 못했어요')).toBeInTheDocument();
    expect(screen.getByText(/아래는 지난번 결과예요/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /회사1/ })).toBeInTheDocument();
  });

  it('맞는 기업이 없으면 찾지 못했다고 알린다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready({ companies: [] }));
    mount();
    expect(await screen.findByText(/맞는 기업을 찾지 못했어요/)).toBeInTheDocument();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
  });

  it('지난 결과를 못 읽으면 경고 대신 한 줄로 알리고 다시 불러오게 한다 — 아래 공고 목록이 본문이다', async () => {
    const read = vi.spyOn(matchingApi, 'recommendations').mockRejectedValueOnce(new ApiError('INTERNAL_ERROR', 'x', 503)).mockResolvedValue(ready());
    mount();
    expect(await screen.findByText(/지난 매칭 결과를 불러오지 못했어요/)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '매칭 결과 다시 불러오기' }));

    expect(await screen.findByRole('link', { name: /회사1/ })).toBeInTheDocument();
    expect(read).toHaveBeenCalledTimes(2);
  });

  it('시작이 거절되면(호출 한도) 이유를 보이고 다시 누를 수 있다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(state());
    vi.spyOn(matchingApi, 'startRecommendations').mockRejectedValue(new ApiError('RATE_LIMITED', '잠시 뒤에 다시 시도해 주세요.', 429));
    mount();
    fireEvent.click(await startButton());

    expect(await screen.findByText('매칭을 시작하지 못했어요')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: '내 조건으로 매칭하기' })).toBeEnabled());
  });
});

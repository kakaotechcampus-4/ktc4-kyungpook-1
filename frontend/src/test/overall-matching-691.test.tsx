import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { keys } from '@/api/keys';
import { matchingApi, MatchTarget, OverallRecommendation } from '@/api/matching';
import { Me } from '@/api/schemas';
import { MatchListPage } from '@/features/match/MatchListPage';
import { fitLine } from '@/features/match/model';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';
import sampleList from '../../docs/examples/matching/list.json';

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

// 목록 공고 p1~p6. 맞춰 본 기업은 p1(일부)·p2(모두)·p3(필수 없음)·p4(하나도 못 채움)·p9(목록에 없는 공고)
const base = MatchTarget.parse(sampleList.data[0]);
const CATEGORY = ['BACKEND', 'BACKEND', 'DEVOPS', 'MOBILE', 'BACKEND', 'DATA'] as const;
const postings = CATEGORY.map((category, i) =>
  MatchTarget.parse({ ...base, id: `p${i + 1}`, company: `회사${i + 1}`, companyId: `c${i + 1}`, role: `공고${i + 1}`, category, fit: null }));
const company = (i: number, requiredMet: number, requiredTotal: number, preferredMet = 1, preferredTotal = 2) => ({
  companyId: `c${i}`, companyName: `회사${i}`, logoUrl: null,
  posting: { id: `p${i}`, title: `공고${i}`, deadline: null, closed: false },
  requiredMet, requiredTotal, preferredMet, preferredTotal,
});
const state = (over: Record<string, unknown> = {}) =>
  OverallRecommendation.parse({ status: 'NONE', computedAt: null, stale: false, progress: null, companies: [], ...over });
const ready = (over: Record<string, unknown> = {}) => state({
  status: 'READY', computedAt: '2026-11-02T08:27:00Z',
  companies: [company(1, 2, 4), company(2, 3, 3), company(3, 0, 0, 0, 0), company(4, 0, 2), company(9, 1, 1)],
  ...over,
});
const meWith = (confirmedCards: number) => {
  const me = handle('GET', '/me', new URLSearchParams(), {}, true).data as Record<string, unknown> & { stats: object };
  return Me.parse({ ...me, stats: { ...me.stats, confirmedCards } });
};

function mount(path = '/match') {
  vi.spyOn(matchingApi, 'list').mockResolvedValue(postings);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  client.setQueryData(keys.me, meWith(3));
  const router = createMemoryRouter([
    { path: '/match', element: <MatchListPage /> },
    { path: '/match/:id', element: <p>공고 상세</p> },
  ], { initialEntries: [path] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  return router;
}
const group = (name: string) => screen.getByRole('region', { name });
const titles = (root: HTMLElement) => [...root.querySelectorAll('.posting-title')].map((el) => el.textContent);
// 지난 결과를 읽는 동안에는 버튼이 잠겨 있다 — 풀린 뒤에 누른다
async function startButton() {
  const button = await screen.findByRole('button', { name: '내 조건으로 맞춰 보기' });
  await waitFor(() => expect(button).toBeEnabled());
  return button;
}

describe('공고 타일 안의 맞는 정도 한 줄(fitLine)', () => {
  it('기술·경험 필수 조건이 없으면 0개 중 0개라고 쓰지 않는다', () => {
    expect(fitLine({ requiredMet: 0, requiredTotal: 0, preferredMet: 0, preferredTotal: 0 })).toBe('기술과 경험 필수 조건이 없어요');
    expect(fitLine({ requiredMet: 0, requiredTotal: 0, preferredMet: 1, preferredTotal: 2 })).toBe('기술과 경험 필수 조건이 없어요, 우대 2개 중 1개가 맞아요');
  });

  it('필수 조건을 모두 채웠으면 그렇다고 쓰고 우대 개수를 붙인다', () => {
    expect(fitLine({ requiredMet: 3, requiredTotal: 3, preferredMet: 1, preferredTotal: 2 })).toBe('필수 조건 3개를 모두 채웠어요, 우대 2개 중 1개가 맞아요');
  });

  it('일부만 채웠으면 필수 개수만 쓴다', () => {
    expect(fitLine({ requiredMet: 2, requiredTotal: 4, preferredMet: 1, preferredTotal: 2 })).toBe('필수 조건 4개 중 2개를 채웠어요');
  });
});

describe('#755 공고 매칭 한 목록', () => {
  it('맞춰 본 적이 없으면 검은 버튼과 안내 한 줄만 있고, 묶음 없이 공고 N건 목록이다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(state());
    mount();
    const button = await startButton();
    expect(button).toHaveClass('btn--primary');
    expect(screen.getByText('확정한 경험 카드와 인적사항으로 맞는 공고를 골라 드려요')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { level: 2, name: '공고 6건' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: '나와 맞는 공고' })).not.toBeInTheDocument();
    expect(screen.queryByText(/필수 조건/)).not.toBeInTheDocument();
  });

  it('누르면 계산을 시작하고, 계산 중에는 확인한 공고 수를 보이며 버튼을 막는다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(state());
    const start = vi.spyOn(matchingApi, 'startRecommendations').mockResolvedValue(state({ status: 'COMPUTING', progress: { done: 2, total: 5 } }));
    mount();
    fireEvent.click(await startButton());

    expect(await screen.findByText('공고 5곳 중 2곳을 확인했어요')).toBeInTheDocument();
    expect(start).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('progressbar', { name: '맞춰 보기 진행률' })).toHaveAttribute('aria-valuenow', '40');
    expect(screen.getByRole('button', { name: '맞춰 보는 중' })).toBeDisabled();
  });

  it('계산 중에는 결과를 다시 읽어, 끝나면 맞춰 본 날짜와 확정 카드 수를 쓰고 테두리 버튼으로 바꾼다', async () => {
    const read = vi.spyOn(matchingApi, 'recommendations')
      .mockResolvedValueOnce(state({ status: 'COMPUTING', progress: { done: 1, total: 5 } }))
      .mockResolvedValue(ready());
    mount();
    expect(await screen.findByText('공고 5곳 중 1곳을 확인했어요')).toBeInTheDocument();

    expect(await screen.findByText('11월 2일에 확정 카드 3장과 인적사항으로 맞춰 봤어요', undefined, { timeout: 4000 })).toBeInTheDocument();
    expect(read.mock.calls.length).toBeGreaterThanOrEqual(2);
    const again = screen.getByRole('button', { name: '다시 맞춰 보기' });
    expect(again).toBeEnabled();
    expect(again).toHaveClass('btn--outline');
  });

  it('맞춰 본 공고는 순위대로 나와 맞는 공고에 맞는 정도 한 줄과 함께 오르고, 목록에 없는 공고는 빠진다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready());
    mount();
    const fit = await screen.findByRole('region', { name: '나와 맞는 공고' });
    expect(titles(fit)).toEqual(['공고1', '공고2', '공고3']);
    expect(within(fit).getByText('3개')).toBeInTheDocument();
    expect([...fit.querySelectorAll('.posting-fit')].map((el) => el.textContent)).toEqual([
      '필수 조건 4개 중 2개를 채웠어요',
      '필수 조건 3개를 모두 채웠어요, 우대 2개 중 1개가 맞아요',
      '기술과 경험 필수 조건이 없어요',
    ]);
    expect(screen.queryByText('공고9')).not.toBeInTheDocument();
    expect(screen.queryByText('나와 맞는 기업')).not.toBeInTheDocument();
  });

  it('필수 조건을 하나도 못 채운 공고는 아직 부족한 공고로 접어 두고, 누르면 펼친다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready());
    mount();
    const gap = await screen.findByRole('region', { name: '아직 부족한 공고' });
    expect(within(gap).getByText('필수 조건이 거의 안 맞아요. 카드를 더 확정하면 다시 맞춰 볼 수 있어요.')).toBeInTheDocument();
    expect(titles(gap)).toEqual([]);
    const more = within(gap).getByRole('button', { name: '부족한 공고 1개 보기' });
    expect(more).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(more);
    expect(titles(gap)).toEqual(['공고4']);
    expect(gap.querySelector('.posting-fit')).toHaveTextContent('필수 조건 2개 중 0개를 채웠어요');
    expect(within(gap).getByRole('button', { name: '부족한 공고 접기' })).toHaveAttribute('aria-expanded', 'true');
  });

  it('그 밖의 공고는 위 두 묶음에 나온 공고를 빼고 보여 준다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready());
    mount();
    const rest = await screen.findByRole('region', { name: '그 밖의 공고' });
    expect(titles(rest)).toEqual(['공고5', '공고6']);
    expect(rest.querySelector('.posting-fit')).toBeNull();
  });

  it('직무 칩을 고르면 세 묶음 모두 그 직무 공고만 남고, 주소에 직무를 남긴다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready());
    const router = mount();
    await screen.findByRole('region', { name: '나와 맞는 공고' });
    const chip = screen.getByRole('button', { name: '백엔드' });
    fireEvent.click(chip);

    expect(chip).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: '전체' })).toHaveAttribute('aria-pressed', 'false');
    expect(titles(group('나와 맞는 공고'))).toEqual(['공고1', '공고2']);
    expect(screen.queryByRole('region', { name: '아직 부족한 공고' })).not.toBeInTheDocument();
    expect(titles(group('그 밖의 공고'))).toEqual(['공고5']);
    expect(router.state.location.search).toBe('?category=BACKEND');
  });

  it('카드나 인적사항이 바뀌었으면 다시 맞춰 보라고 한 줄로 알린다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready({ stale: true }));
    mount();
    expect(await screen.findByText('카드나 인적사항이 바뀌었어요.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 맞춰 보기' })).toBeEnabled();
  });

  it('계산에 실패하면 알리고 지난 결과는 그대로 둔다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready({ status: 'FAILED' }));
    mount();
    expect(await screen.findByText('맞춰 보지 못했어요.')).toBeInTheDocument();
    expect(screen.getByText(/아래는 지난번 결과예요/)).toBeInTheDocument();
    expect(await screen.findByRole('region', { name: '나와 맞는 공고' })).toBeInTheDocument();
  });

  it('맞는 공고가 없으면 찾지 못했다고 알리고 묶음 없이 공고 N건 목록이다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready({ companies: [] }));
    mount();
    expect(await screen.findByText(/맞는 공고를 찾지 못했어요/)).toBeInTheDocument();
    expect(await screen.findByRole('heading', { level: 2, name: '공고 6건' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: '나와 맞는 공고' })).not.toBeInTheDocument();
  });

  it('지난 결과를 못 읽으면 경고 대신 한 줄로 알리고 다시 불러오게 한다 — 공고 목록이 본문이다', async () => {
    const read = vi.spyOn(matchingApi, 'recommendations').mockRejectedValueOnce(new ApiError('INTERNAL_ERROR', 'x', 503)).mockResolvedValue(ready());
    mount();
    expect(await screen.findByText(/지난 결과를 불러오지 못했어요/)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '결과 다시 불러오기' }));

    expect(await screen.findByRole('region', { name: '나와 맞는 공고' })).toBeInTheDocument();
    expect(read).toHaveBeenCalledTimes(2);
  });

  it('시작이 거절되면(호출 한도) 이유를 보이고 다시 누를 수 있다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(state());
    vi.spyOn(matchingApi, 'startRecommendations').mockRejectedValue(new ApiError('RATE_LIMITED', '잠시 뒤에 다시 시도해 주세요.', 429));
    mount();
    fireEvent.click(await startButton());

    expect(await screen.findByRole('alert')).toHaveTextContent('맞춰 보기를 시작하지 못했어요.');
    await waitFor(() => expect(screen.getByRole('button', { name: '내 조건으로 맞춰 보기' })).toBeEnabled());
  });
});

import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { keys } from '@/api/keys';
import { matchingApi, MatchTarget, OverallRecommendation } from '@/api/matching';
import { Me } from '@/api/schemas';
import { MatchListPage } from '@/features/match/MatchListPage';
import { fitNames, fitSummary } from '@/features/match/model';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';
import sampleList from '../../docs/examples/matching/list.json';

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

// 목록 공고 p1~p6. 맞춰 본 기업은 p1(일부)·p2(모두)·p3(필수 없음)·p4(하나도 못 채움, 조건 이름 없는 옛 응답)·p9(목록에 없는 공고)
const base = MatchTarget.parse(sampleList.data[0]);
const CATEGORY = ['BACKEND', 'BACKEND', 'DEVOPS', 'MOBILE', 'BACKEND', 'DATA'] as const;
const postings = CATEGORY.map((category, i) =>
  MatchTarget.parse({ ...base, id: `p${i + 1}`, company: `회사${i + 1}`, companyId: `c${i + 1}`, role: `공고${i + 1}`, category, fit: null }));
const cond = (text: string, techs: string[] = []) => ({ text, techs });
const company = (i: number, requiredMet: number, requiredTotal: number, preferredMet = 1, preferredTotal = 2, names: Record<string, unknown> = {}) => ({
  companyId: `c${i}`, companyName: `회사${i}`, logoUrl: null,
  posting: { id: `p${i}`, title: `공고${i}`, deadline: null, closed: false },
  requiredMet, requiredTotal, preferredMet, preferredTotal, ...names,
});
const state = (over: Record<string, unknown> = {}) =>
  OverallRecommendation.parse({ status: 'NONE', computedAt: null, stale: false, progress: null, companies: [], ...over });
const ready = (over: Record<string, unknown> = {}) => state({
  status: 'READY', computedAt: '2026-11-02T08:27:00Z', checked: 15,
  companies: [
    company(1, 2, 4, 0, 0, { met: [cond('Java 서버 개발 경험', ['JAVA'])], missing: [cond('Kafka 운영 경험', ['KAFKA']), cond('메시지 큐 운영 경험')] }),
    company(2, 3, 3, 1, 2, { met: [cond('서버 개발 경험', ['JAVA', 'KOTLIN']), cond('SQL 작성 경험', ['SQL']), cond('Docker 사용 경험', ['DOCKER'])] }),
    company(3, 0, 0, 0, 0),
    company(4, 0, 2),
    company(9, 1, 1),
  ],
  ...over,
});
const meWith = (confirmedCards: number) => {
  const me = handle('GET', '/me', new URLSearchParams(), {}, true).data as Record<string, unknown> & { stats: object };
  return Me.parse({ ...me, stats: { ...me.stats, confirmedCards } });
};

function mount(path = '/match', cards = 3) {
  vi.spyOn(matchingApi, 'list').mockResolvedValue(postings);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  client.setQueryData(keys.me, meWith(cards));
  const router = createMemoryRouter([
    { path: '/match', element: <MatchListPage /> },
    { path: '/match/:id', element: <p>공고 상세</p> },
  ], { initialEntries: [path] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  return router;
}
const BOX = '내 카드로 맞춰 본 공고';
const box = () => screen.getByRole('region', { name: BOX });
const allPostings = () => screen.getByRole('region', { name: '모든 공고' });
const tiles = () => [...box().querySelectorAll<HTMLElement>('.result-tile')];
const titles = (root: HTMLElement) => [...root.querySelectorAll('.posting-title')].map((el) => el.textContent);
// 지난 결과를 읽는 동안에는 버튼이 잠겨 있다 — 풀린 뒤에 누른다
async function startButton() {
  const button = await screen.findByRole('button', { name: '내 조건으로 맞춰 보기' });
  await waitFor(() => expect(button).toBeEnabled());
  return button;
}
const chip = (name: string) => screen.getByRole('button', { name });

describe('결과 타일의 개수 줄(fitSummary)', () => {
  it('기술·경험 필수 조건이 없으면 0개 중 0개라고 쓰지 않는다', () => {
    expect(fitSummary({ requiredMet: 0, requiredTotal: 0, preferredMet: 0, preferredTotal: 0 })).toEqual({ done: false, text: '기술과 경험 필수 조건이 없어요', extra: '' });
    expect(fitSummary({ requiredMet: 0, requiredTotal: 0, preferredMet: 1, preferredTotal: 2 }).text).toBe('기술과 경험 필수 조건이 없어요, 우대 2개 중 1개가 맞아요');
  });

  it('필수를 모두 채웠으면 그렇다고 쓰고, 맞은 우대가 있을 때만 그 개수를 붙인다', () => {
    expect(fitSummary({ requiredMet: 3, requiredTotal: 3, preferredMet: 2, preferredTotal: 5 })).toEqual({ done: true, text: '필수 3개 모두 맞아요', extra: '우대 2개' });
    expect(fitSummary({ requiredMet: 3, requiredTotal: 3, preferredMet: 0, preferredTotal: 5 }).extra).toBe('');
  });

  it('일부만 채웠으면 필수 개수만 쓴다', () => {
    expect(fitSummary({ requiredMet: 2, requiredTotal: 4, preferredMet: 1, preferredTotal: 2 })).toEqual({ done: false, text: '필수 4개 중 2개 맞아요', extra: '' });
  });
});

describe('결과 타일의 이름 줄(fitNames)', () => {
  it('기술 이름이 있으면 기술 이름으로, 없으면 조건 글로 쓰고 글 끝의 경험은 뗀다', () => {
    const line = fitNames({ requiredMet: 0, requiredTotal: 2, met: [], missing: [cond('Kafka 운영 경험', ['KAFKA', 'RABBITMQ']), cond('메시지 큐 운영 경험')] });
    expect(line).toEqual({ names: 'Kafka·RabbitMQ, 메시지 큐 운영', tail: ' 경험이 아직 없어요', lacking: true });
  });

  it('필수를 다 채웠으면 맞은 조건을, 모자라면 모자란 조건을 쓴다', () => {
    const both = { met: [cond('Java 서버 개발 경험', ['JAVA'])], missing: [cond('Docker 운영 경험', ['DOCKER'])] };
    expect(fitNames({ requiredMet: 1, requiredTotal: 1, ...both })).toEqual({ names: 'Java', tail: ' 경험이 맞아요', lacking: false });
    expect(fitNames({ requiredMet: 0, requiredTotal: 1, ...both })).toEqual({ names: 'Docker', tail: ' 경험이 아직 없어요', lacking: true });
  });

  it('2개까지 쓰고 남으면 서버가 준 이름 개수가 아니라 필수 개수에서 센 등 N개로 줄인다', () => {
    const three = [cond('가 경험', ['JAVA']), cond('나 경험', ['SQL']), cond('다 경험', ['DOCKER'])];
    expect(fitNames({ requiredMet: 9, requiredTotal: 13, met: [], missing: three })).toEqual({ names: 'Java, SQL', tail: ' 등 4개가 아직 없어요', lacking: true });
    expect(fitNames({ requiredMet: 3, requiredTotal: 3, met: three, missing: [] })?.tail).toBe(' 등 3개가 맞아요');
    expect(fitNames({ requiredMet: 2, requiredTotal: 2, met: three.slice(0, 2), missing: [] })?.tail).toBe(' 경험이 맞아요');
  });

  it('이름을 못 받은 옛 응답과 필수 조건이 없는 공고는 줄이 없다', () => {
    expect(fitNames({ requiredMet: 1, requiredTotal: 3, met: [], missing: [] })).toBeNull();
    expect(fitNames({ requiredMet: 0, requiredTotal: 0, met: [cond('가')], missing: [cond('나')] })).toBeNull();
  });
});

describe('공고 매칭 결과 상자', () => {
  it('맞춰 본 적이 없으면 상자에 안내와 검은 버튼만 있고, 모든 공고는 전체 목록이다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(state());
    mount();
    const button = await startButton();
    expect(button).toHaveClass('btn--primary');
    expect(within(box()).getByRole('heading', { level: 2, name: '내 카드로 공고 맞춰 보기' })).toBeInTheDocument();
    expect(within(box()).getByText('확정 카드 3장과 인적사항으로, 기술이 겹치는 공고의 필수·우대 조건을 확인해요. 1분쯤 걸려요.')).toBeInTheDocument();
    expect(tiles()).toHaveLength(0);
    expect(await within(allPostings()).findByText('6건')).toBeInTheDocument();
    expect(titles(allPostings())).toHaveLength(6);
  });

  it('누르면 계산을 시작하고, 계산 중에는 확인한 공고 수를 보이며 버튼을 막는다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(state());
    const start = vi.spyOn(matchingApi, 'startRecommendations').mockResolvedValue(state({ status: 'COMPUTING', progress: { done: 2, total: 5 } }));
    mount();
    fireEvent.click(await startButton());

    expect(await screen.findByText('공고 5곳 중 2곳을 확인했어요')).toBeInTheDocument();
    expect(start).toHaveBeenCalledTimes(1);
    expect(within(box()).getByRole('heading', { level: 2, name: '맞춰 보는 중' })).toBeInTheDocument();
    expect(screen.getByRole('progressbar', { name: '맞춰 보기 진행률' })).toHaveAttribute('aria-valuenow', '40');
    expect(screen.getByRole('button', { name: '맞춰 보는 중' })).toBeDisabled();
  });

  it('계산 중에도 지난 결과 타일은 그대로 보인다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready({ status: 'COMPUTING', progress: { done: 1, total: 5 } }));
    mount();
    expect(await screen.findByText('공고 5곳 중 1곳을 확인했어요')).toBeInTheDocument();
    await waitFor(() => expect(tiles()).toHaveLength(4));
  });

  it('계산 중에는 결과를 다시 읽어, 끝나면 맞춰 본 날짜·확정 카드 수·확인한 공고 수를 쓰고 테두리 버튼으로 바꾼다', async () => {
    const read = vi.spyOn(matchingApi, 'recommendations')
      .mockResolvedValueOnce(state({ status: 'COMPUTING', progress: { done: 1, total: 5 } }))
      .mockResolvedValue(ready());
    mount();
    expect(await screen.findByText('공고 5곳 중 1곳을 확인했어요')).toBeInTheDocument();

    await waitFor(() => expect(box()).toHaveTextContent('11월 2일 · 확정 카드 3장과 인적사항으로 기술이 겹치는 15곳을 확인했어요'), { timeout: 4000 });
    expect(read.mock.calls.length).toBeGreaterThanOrEqual(2);
    expect(within(box()).getByRole('heading', { level: 2, name: `${BOX} 4곳` })).toBeInTheDocument();
    const again = screen.getByRole('button', { name: '다시 맞춰 보기' });
    expect(again).toBeEnabled();
    expect(again).toHaveClass('btn--outline');
  });

  it('맞춰 본 공고는 순위 번호를 붙여 순위대로 상자에 오르고, 목록에 없는 공고는 빠진다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready());
    mount();
    await waitFor(() => expect(tiles()).toHaveLength(4));
    expect(titles(box())).toEqual(['공고1', '공고2', '공고3', '공고4']);
    expect(tiles().map((tile) => tile.querySelector('.star-key')?.textContent)).toEqual(['1', '2', '3', '4']);
    expect(within(tiles()[0]).getByRole('link', { name: '공고1' })).toHaveAttribute('href', '/match/p1');
    expect(within(tiles()[0]).getByRole('link', { name: '회사1' })).toHaveAttribute('href', '/match/company/c1');
    expect(screen.queryByText('공고9')).not.toBeInTheDocument();
    expect(tiles()[0].querySelector('.posting-techs')).toBeNull();
  });

  it('타일에는 필수 개수 줄과 맞은·모자란 조건 이름 줄이 있고, 이름이 없는 옛 응답은 이름 줄만 뺀다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready());
    mount();
    await waitFor(() => expect(tiles()).toHaveLength(4));
    const lines = (tile: HTMLElement) => [tile.querySelector('.result-tile__count')?.textContent, tile.querySelector('.result-tile__names')?.textContent];
    expect(lines(tiles()[0])).toEqual(['필수 4개 중 2개 맞아요', 'Kafka, 메시지 큐 운영 경험이 아직 없어요']);
    expect(lines(tiles()[1])).toEqual(['필수 3개 모두 맞아요 · 우대 1개', 'Java·Kotlin, SQL 등 3개가 맞아요']);
    expect(lines(tiles()[2])).toEqual(['기술과 경험 필수 조건이 없어요', undefined]);
    expect(lines(tiles()[3])).toEqual(['필수 2개 중 0개 맞아요', undefined]);
    expect(tiles()[0].querySelector('.result-tile__names b')).toHaveClass('is-lacking');
    expect(tiles()[1].querySelector('.result-tile__names b')).not.toHaveClass('is-lacking');
  });

  it('모든 공고는 상자에 오른 공고까지 포함한 전체 목록이다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready());
    mount();
    await waitFor(() => expect(tiles()).toHaveLength(4));
    expect(titles(allPostings())).toEqual(['공고1', '공고2', '공고3', '공고4', '공고5', '공고6']);
    expect(within(allPostings()).getByText('6건')).toBeInTheDocument();
    expect(allPostings().querySelector('.result-tile')).toBeNull();
  });

  it('직무 칩을 고르면 상자는 한 줄 띠로 접히고 모든 공고만 거르며, 펼치면 타일은 거르지 않은 그대로다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready());
    const router = mount();
    await waitFor(() => expect(tiles()).toHaveLength(4));
    fireEvent.click(chip('백엔드'));

    expect(chip('백엔드')).toHaveAttribute('aria-pressed', 'true');
    expect(router.state.location.search).toBe('?category=BACKEND');
    expect(titles(allPostings())).toEqual(['공고1', '공고2', '공고5']);
    expect(within(box()).getByText(`${BOX} 4곳`)).toBeInTheDocument();
    expect(within(box()).getByText('11월 2일')).toBeInTheDocument();
    expect(tiles()).toHaveLength(0);
    const open = within(box()).getByRole('button', { name: '펼치기' });
    expect(open).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(open);
    expect(titles(box())).toEqual(['공고1', '공고2', '공고3', '공고4']);
    expect(within(box()).getByRole('button', { name: '접기' })).toHaveAttribute('aria-expanded', 'true');
    fireEvent.click(within(box()).getByRole('button', { name: '접기' }));
    expect(tiles()).toHaveLength(0);
  });

  it('검색어를 지우면 접힌 상자가 자동으로 펼쳐지고, 직접 펼쳐 둔 상태는 초기화돼 다음 검색에서 다시 접힌다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready());
    mount();
    await waitFor(() => expect(tiles()).toHaveLength(4));
    const searchBox = screen.getByPlaceholderText('기업이나 직무 검색');
    fireEvent.change(searchBox, { target: { value: '회사5' } });
    expect(tiles()).toHaveLength(0);
    fireEvent.click(within(box()).getByRole('button', { name: '펼치기' }));
    expect(tiles()).toHaveLength(4);

    fireEvent.change(searchBox, { target: { value: '' } });
    expect(tiles()).toHaveLength(4);
    expect(within(box()).queryByRole('button', { name: '접기' })).not.toBeInTheDocument();
    fireEvent.change(searchBox, { target: { value: '회사5' } });
    expect(tiles()).toHaveLength(0);
    expect(within(box()).getByRole('button', { name: '펼치기' })).toBeInTheDocument();
  });

  it('맞춰 본 결과가 없거나 계산 중이면 칩이 걸려도 접지 않는다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(state());
    mount('/match?category=BACKEND');
    expect(await startButton()).toBeInTheDocument();
    expect(within(box()).queryByRole('button', { name: '펼치기' })).not.toBeInTheDocument();
    cleanup();

    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready({ status: 'COMPUTING', progress: { done: 1, total: 5 } }));
    mount('/match?category=BACKEND');
    expect(await screen.findByText('공고 5곳 중 1곳을 확인했어요')).toBeInTheDocument();
    await waitFor(() => expect(tiles()).toHaveLength(4));
    expect(within(box()).queryByRole('button', { name: '펼치기' })).not.toBeInTheDocument();
  });

  it('확정 카드가 0장이면 상자가 카드 확정을 안내하고 맞춰 보기 버튼은 없다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(state());
    mount('/match', 0);
    expect(await screen.findByText('확정한 카드가 없어요. 카드가 확정되면 공고마다 내 경험과 조건을 맞춰 볼 수 있어요.')).toBeInTheDocument();
    expect(within(box()).getByRole('link', { name: '카드 확정하러 가기' })).toHaveAttribute('href', '/cards');
    expect(screen.queryByRole('button', { name: '내 조건으로 맞춰 보기' })).not.toBeInTheDocument();
  });

  it('이름과 확인한 공고 수가 없는 옛 서버 응답도 깨지지 않고 해당 부분만 빠진다', async () => {
    const old = {
      status: 'READY', computedAt: '2026-11-02T08:27:00Z', stale: false, progress: null,
      companies: [{ companyId: 'c1', companyName: '회사1', logoUrl: null, posting: { id: 'p1', title: '공고1', deadline: null, closed: false }, requiredMet: 2, requiredTotal: 4, preferredMet: 0, preferredTotal: 0 }],
    };
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(OverallRecommendation.parse(old));
    mount();
    await waitFor(() => expect(tiles()).toHaveLength(1));
    expect(box()).toHaveTextContent('11월 2일 · 확정 카드 3장과 인적사항으로 맞춰 봤어요');
    expect(tiles()[0].querySelector('.result-tile__count')).toHaveTextContent('필수 4개 중 2개 맞아요');
    expect(tiles()[0].querySelector('.result-tile__names')).toBeNull();
  });

  it('카드나 인적사항이 바뀌었으면 상자 안에서 다시 맞춰 보라고 한 줄로 알린다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready({ stale: true }));
    mount();
    expect(await within(box()).findByText('카드나 인적사항이 바뀌었어요.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 맞춰 보기' })).toBeEnabled();
  });

  it('계산에 실패하면 상자 안에서 알리고 지난 결과 타일은 그대로 둔다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready({ status: 'FAILED' }));
    mount();
    expect(await within(box()).findByText('맞춰 보지 못했어요.')).toBeInTheDocument();
    expect(within(box()).getByText(/아래는 지난번 결과예요/)).toBeInTheDocument();
    await waitFor(() => expect(tiles()).toHaveLength(4));
  });

  it('맞는 공고가 없으면 상자 안에서 찾지 못했다고 알리고 모든 공고는 그대로다', async () => {
    vi.spyOn(matchingApi, 'recommendations').mockResolvedValue(ready({ companies: [] }));
    mount();
    expect(await within(box()).findByText(/맞는 공고를 찾지 못했어요/)).toBeInTheDocument();
    expect(tiles()).toHaveLength(0);
    expect(await within(allPostings()).findByText('6건')).toBeInTheDocument();
  });

  it('지난 결과를 못 읽으면 경고 대신 한 줄로 알리고 다시 불러오게 한다 — 공고 목록이 본문이다', async () => {
    const read = vi.spyOn(matchingApi, 'recommendations').mockRejectedValueOnce(new ApiError('INTERNAL_ERROR', 'x', 503)).mockResolvedValue(ready());
    mount();
    expect(await screen.findByText(/지난 결과를 불러오지 못했어요/)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '결과 다시 불러오기' }));

    await waitFor(() => expect(tiles()).toHaveLength(4));
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

import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { keys } from '@/api/keys';
import { CardSummary, type Card, type CardStatus, type StarStates } from '@/api/schemas';
import { CardStatusMark, cardStarStates } from '@/components/cards/CardStatusMark';
import { CardPage } from '@/features/cards/CardPage';
import { CardsListPage } from '@/features/cards/CardsListPage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
beforeEach(() => { resetDb(); localStorage.clear(); });
afterEach(cleanup);

const FILLED: StarStates = { S: 'FILLED', T: 'FILLED', A: 'FILLED', R: 'FILLED' };
const mark = (status: CardStatus, star: StarStates, reviewWithStatus?: boolean) => render(<CardStatusMark status={status} star={star} reviewWithStatus={reviewWithStatus} />);
const reviewBadge = () => screen.queryByText('확인 필요');

describe('CardStatusMark — STAR 네 칸이 다 찬 카드', () => {
  it('확정 카드는 글자 배지 대신 체크 아이콘만 두고, 읽어 주는 이름은 "확정됨"이다', () => {
    const { container } = mark('CONFIRMED', FILLED);
    const check = screen.getByRole('img', { name: '확정됨' });
    expect(check).toHaveAttribute('title', '확정됨');
    expect(check.querySelector('svg')).toHaveAttribute('aria-hidden', 'true'); // 아이콘 자체는 장식 — 이름은 바깥 요소가 준다
    expect(screen.queryByText('확정됨')).not.toBeInTheDocument();
    expect(screen.queryByText('작성 중')).not.toBeInTheDocument();
    expect(container.querySelector('.badge')).toBeNull();
    expect(reviewBadge()).not.toBeInTheDocument();
  });

  it('작성 중 카드는 아무것도 그리지 않는다(확정 버튼은 카드 화면이 그대로 둔다)', () => {
    const { container } = mark('DRAFT', FILLED);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('CardStatusMark — 확인 필요 칸이 있는 카드', () => {
  const review: StarStates = { ...FILLED, R: 'NEEDS_REVIEW' };

  it('작성 중 카드는 "확인 필요" 배지만 보이고 "작성 중"은 없다', () => {
    mark('DRAFT', review);
    expect(reviewBadge()).toBeInTheDocument();
    expect(screen.queryByText('작성 중')).not.toBeInTheDocument();
    expect(screen.queryByText('확정됨')).not.toBeInTheDocument();
  });

  it('확정 카드는 목록의 "보완 필요" 탭과 같은 기준으로 "확인 필요"를 띄우지 않고, 확정 표시는 아이콘으로 남는다', () => {
    mark('CONFIRMED', review);
    expect(reviewBadge()).not.toBeInTheDocument();
    expect(screen.getByRole('img', { name: '확정됨' })).toBeInTheDocument();
    expect(screen.queryByText('확정됨')).not.toBeInTheDocument();
  });
});

describe('CardStatusMark — 빈 칸이 있는 카드는 그대로', () => {
  it('작성 중 카드는 "작성 중"', () => {
    mark('DRAFT', { ...FILLED, T: 'EMPTY' });
    expect(screen.getByText('작성 중')).toBeInTheDocument();
    expect(reviewBadge()).not.toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('작성 중이면서 확인할 칸도 있으면 둘 다 보인다', () => {
    mark('DRAFT', { ...FILLED, T: 'EMPTY', R: 'NEEDS_REVIEW' });
    expect(screen.getByText('작성 중')).toBeInTheDocument();
    expect(reviewBadge()).toBeInTheDocument();
  });

  it('머리글처럼 reviewWithStatus 를 끄면 "작성 중"만 둔다(예전 머리글과 같다)', () => {
    mark('DRAFT', { ...FILLED, T: 'EMPTY', R: 'NEEDS_REVIEW' }, false);
    expect(screen.getByText('작성 중')).toBeInTheDocument();
    expect(reviewBadge()).not.toBeInTheDocument();
  });

  it('비운 채 확정한 카드는 아이콘이 아니라 "확정됨" 글자를 유지한다', () => {
    mark('CONFIRMED', { ...FILLED, A: 'EMPTY', R: 'EMPTY' });
    expect(screen.getByText('확정됨')).toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });
});

describe('cardStarStates — 카드 상세에서 요약과 같은 기준으로 칸 상태를 만든다', () => {
  const detail = (version: Partial<Card['version']>, low: Card['lowConfidenceFields'] = []) => ({
    version: { situation: 's', task: 't', action: 'a', result: 'r', ...version } as Card['version'], lowConfidenceFields: low,
  });

  it('글이 있으면 FILLED, 비었거나 공백뿐이면 EMPTY', () => {
    expect(cardStarStates(detail({}))).toEqual(FILLED);
    expect(cardStarStates(detail({ task: null, result: '   ' }))).toEqual({ S: 'FILLED', T: 'EMPTY', A: 'FILLED', R: 'EMPTY' });
  });

  it('확인 필요 표시가 붙은 칸은 NEEDS_REVIEW, 빈 칸이면 EMPTY 가 우선한다', () => {
    const low = [{ field: 'S' as const, why: 'x' }, { field: 'T' as const, why: 'y' }];
    expect(cardStarStates(detail({ task: null }, low))).toEqual({ S: 'NEEDS_REVIEW', T: 'EMPTY', A: 'FILLED', R: 'FILLED' });
  });
});

/** 목록·상세 화면에서 같은 규칙이 지켜지는지 — 서버 요약(star)과 상세 글을 그대로 쓴다 */
const summary = (id: string, title: string, status: CardStatus, star: StarStates, updatedAt: string) =>
  CardSummary.parse({ id, kind: 'TECH', status, title, versionNo: 1, star, updatedAt, evidenceCount: 2, userStatedCount: 0 });
const MIX = [
  summary('done', '확정 완료 카드', 'CONFIRMED', FILLED, '2026-10-05T00:00:00Z'),
  summary('ready', '확정 대기 카드', 'DRAFT', FILLED, '2026-10-04T00:00:00Z'),
  summary('review', '확인 필요 카드', 'DRAFT', { ...FILLED, R: 'NEEDS_REVIEW' }, '2026-10-03T00:00:00Z'),
  summary('empty', '빈 칸 카드', 'DRAFT', { ...FILLED, T: 'EMPTY', R: 'EMPTY' }, '2026-10-02T00:00:00Z'),
  summary('emptyReview', '빈 칸과 확인 필요 카드', 'DRAFT', { ...FILLED, T: 'EMPTY', A: 'NEEDS_REVIEW' }, '2026-10-01T00:00:00Z'),
];

function mountList() {
  const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity, retry: false } } });
  client.setQueryData(keys.cards, MIX);
  localStorage.setItem('gitory.cards.view', JSON.stringify('recent'));
  render(<QueryClientProvider client={client}><MemoryRouter><CardsListPage /></MemoryRouter></QueryClientProvider>);
}
const tile = (title: string) => screen.getByRole('heading', { name: title }).closest('.experience-item') as HTMLElement;

describe('경험 카드 목록', () => {
  it('칸이 다 찬 카드의 글자 배지는 걷히고 "확인 필요"만 남는다', () => {
    mountList();
    const done = within(tile('확정 완료 카드'));
    expect(done.getByRole('img', { name: '확정됨' })).toBeInTheDocument();
    expect(done.queryByText(/확정됨|작성 중|확인 필요/)).not.toBeInTheDocument();
    const ready = within(tile('확정 대기 카드'));
    expect(ready.queryByText(/확정됨|작성 중|확인 필요/)).not.toBeInTheDocument();
    expect(ready.queryByRole('img', { name: '확정됨' })).not.toBeInTheDocument();
    const review = within(tile('확인 필요 카드'));
    expect(review.getByText('확인 필요')).toBeInTheDocument();
    expect(review.queryByText(/확정됨|작성 중/)).not.toBeInTheDocument();
  });

  it('빈 칸이 있는 카드는 예전 그대로 "작성 중"과 빈 칸 수를 보인다', () => {
    mountList();
    const empty = within(tile('빈 칸 카드'));
    expect(empty.getByText('작성 중')).toBeInTheDocument();
    expect(empty.getByText('빈 칸 2개')).toBeInTheDocument();
    const both = within(tile('빈 칸과 확인 필요 카드'));
    expect(both.getByText('작성 중')).toBeInTheDocument();
    expect(both.getByText('확인 필요')).toBeInTheDocument();
    expect(both.getByText('빈 칸 1개')).toBeInTheDocument();
  });

  it('필터 탭의 이름과 개수, 걸러 내는 기준은 그대로다', () => {
    mountList();
    const tabs = within(screen.getByLabelText('카드 작성 상태 필터'));
    expect(tabs.getAllByRole('button').map((b) => b.textContent?.replace(/\s+/g, ' ').trim())).toEqual(['전체 5', '작성 중 4', '확정됨 1', '보완 필요 3']);
    const shown = () => screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent);
    fireEvent.click(tabs.getByRole('button', { name: /^작성 중/ }));
    expect(shown()).toEqual(['확정 대기 카드', '확인 필요 카드', '빈 칸 카드', '빈 칸과 확인 필요 카드']);
    fireEvent.click(tabs.getByRole('button', { name: /^확정됨/ }));
    expect(shown()).toEqual(['확정 완료 카드']);
    fireEvent.click(tabs.getByRole('button', { name: /^보완 필요/ }));
    expect(shown()).toEqual(['확인 필요 카드', '빈 칸 카드', '빈 칸과 확인 필요 카드']);
  });
});

describe('카드 상세 머리글', () => {
  const load = (id: string) => handle('GET', `/cards/${id}`, new URLSearchParams(), {}, true).data as Card;
  function mountDetail(card: Card) {
    const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity, retry: false } } });
    client.setQueryData(keys.card(card.id), card);
    const router = createMemoryRouter([{ path: '/cards/:cardId', element: <CardPage /> }], { initialEntries: [`/cards/${card.id}`] });
    render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
    return document.querySelector('.card-page-header') as HTMLElement;
  }

  it('칸이 다 찬 확정 카드는 체크 아이콘 + 확정일, 글자 배지는 없다', () => {
    const header = within(mountDetail(load('card_04')));
    expect(header.getByRole('img', { name: '확정됨' })).toBeInTheDocument();
    expect(header.queryByText('확정됨')).not.toBeInTheDocument();
    expect(header.getByText(/확정$/)).toBeInTheDocument(); // "2026-10-.. 확정"
  });

  it('칸이 다 찬 작성 중 카드는 상태 배지 없이도 확정 버튼이 그대로 보인다', () => {
    const base = load('card_01');
    const header = within(mountDetail({ ...base, droppedFields: [], lowConfidenceFields: [], version: { ...base.version, task: '팀 일정에 맞춰 세션 유지 방식을 정해야 했다', result: '재로그인 요청이 줄었다' } }));
    expect(header.queryByText(/작성 중|확정됨|확인 필요/)).not.toBeInTheDocument();
    expect(header.queryByRole('img', { name: '확정됨' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '확정' })).toBeEnabled();
  });

  it('확인 필요 칸만 있는 작성 중 카드는 "확인 필요" 배지만 보인다', () => {
    const base = load('card_01');
    const header = within(mountDetail({ ...base, droppedFields: [], version: { ...base.version, task: '팀 일정에 맞춰 세션 유지 방식을 정해야 했다' } })); // R 만 확인 필요
    expect(header.getByText('확인 필요')).toBeInTheDocument();
    expect(header.queryByText(/작성 중|확정됨/)).not.toBeInTheDocument();
  });

  it('빈 칸이 있는 작성 중 카드는 예전처럼 "작성 중"', () => {
    const header = within(mountDetail(load('card_01'))); // T 가 비어 있고 R 은 확인 필요
    expect(header.getByText('작성 중')).toBeInTheDocument();
    expect(header.queryByText('확인 필요')).not.toBeInTheDocument();
  });
});

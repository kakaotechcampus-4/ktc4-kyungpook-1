import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { CardSummary } from '@/api/schemas';
import { CardsListPage } from '@/features/cards/CardsListPage';
import { summaryDetail } from '@/lib/cardSummaryView';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

beforeEach(() => { resetDb(); localStorage.clear(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

function mount(cards?: unknown) {
  const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity, retry: false } } });
  if (cards !== undefined) client.setQueryData(keys.cards, cards);
  render(<QueryClientProvider client={client}><MemoryRouter><CardsListPage /></MemoryRouter></QueryClientProvider>);
}
const seeded = () => handle('GET', '/cards', new URLSearchParams(), {}, true).data;
const primaries = () => document.querySelectorAll('.btn--primary');

it('카드가 하나도 없으면 주 버튼은 하나이고, 의미 없는 탭은 숨기고, 직접 작성 길을 한 줄로 알려 준다', () => {
  mount([]);
  expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('경험 카드 0');
  expect(screen.getByText('아직 카드가 없어요')).toBeInTheDocument();
  expect(primaries()).toHaveLength(1);
  expect(screen.getByRole('button', { name: '레포에서 카드 만들기' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /^레포에서 만들기/ })).not.toBeInTheDocument();
  // 전체 0 / 작성 중 0 / 확정됨 0 / 보완 필요 0 탭과 묶어 보기 선택은 카드가 생긴 뒤에 의미가 있다
  expect(screen.queryByRole('button', { name: /^(전체|작성 중|확정됨|보완 필요)/ })).not.toBeInTheDocument();
  expect(screen.queryByRole('radiogroup', { name: '카드 묶어 보기' })).not.toBeInTheDocument();
  // 직접 작성은 기존 라우트 하나로, 화면에 한 번만 나온다
  const manual = screen.getAllByRole('link', { name: /직접 작성/ });
  expect(manual).toHaveLength(1);
  expect(manual[0]).toHaveAttribute('href', '/cards/new');
});

it('건수를 아직 모르는 동안(조회 중)에는 빈 상태로 보지 않고 탭을 그대로 둔다', () => {
  vi.spyOn(endpoints, 'cards').mockReturnValue(new Promise(() => {}));
  mount();
  expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('경험 카드 —');
  expect(screen.getByRole('button', { name: '전체 —' })).toBeInTheDocument();
  expect(screen.queryByText('아직 카드가 없어요')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '레포에서 만들기' })).toBeInTheDocument();
});

it('카드가 있으면 상단 도구와 탭이 그대로 보이고, 묶음 요약 단위가 모두 붙는다', () => {
  mount(seeded());
  expect(primaries()).toHaveLength(1);
  expect(screen.getByRole('button', { name: '레포에서 만들기' })).toBeInTheDocument();
  for (const tab of ['전체', '작성 중', '확정됨', '보완 필요']) expect(screen.getByRole('button', { name: new RegExp(`^${tab}`) })).toBeInTheDocument();
  const summaries = [...document.querySelectorAll('.card-group__counts')].map((node) => node.textContent);
  expect(summaries.length).toBeGreaterThan(0);
  for (const text of summaries) expect(text).toMatch(/^카드 \d+장, 확정 \d+장, 작성 중 \d+장$/);
});

it('검색 결과가 없을 때는 필터 초기화가 다음 행동이고, 같은 이름의 주 버튼을 또 만들지 않는다', () => {
  mount(seeded());
  fireEvent.change(screen.getByRole('textbox', { name: '카드 이름으로 검색' }), { target: { value: 'no-such-card-title' } });
  expect(screen.getByText('조건에 맞는 카드가 없어요')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '레포에서 카드 만들기' })).not.toBeInTheDocument();
  expect(primaries()).toHaveLength(1);
  // 카드가 있으니 탭은 그대로 둔다
  expect(screen.getByRole('button', { name: /^전체/ })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '필터 초기화' }));
  expect(screen.queryByText('조건에 맞는 카드가 없어요')).not.toBeInTheDocument();
  expect(within(document.querySelector('.card-groups')!).getAllByRole('link').length).toBeGreaterThan(0);
});

const base = { id: 'c1', kind: 'TECH', status: 'CONFIRMED', title: '카드', versionNo: 1,
  star: { S: 'FILLED', T: 'FILLED', A: 'FILLED', R: 'FILLED' }, updatedAt: '2026-10-03T00:00:00Z' };

it('카드 하단 단위는 카드 상세와 같다: 근거 커밋 N개, 내 답변 N개', () => {
  expect(summaryDetail(CardSummary.parse({ ...base, evidenceCount: 3, userStatedCount: 0 }))).toBe('근거 커밋 3개');
  expect(summaryDetail(CardSummary.parse({ ...base, evidenceCount: 0, userStatedCount: 4 }))).toBe('내 답변 4개');
  expect(summaryDetail(CardSummary.parse({ ...base, userStatedCount: 4 }))).toBe('내 답변 4개');
  // 모르는 값은 만들지 않고, 서버가 준 0 은 0 으로 둔다
  expect(summaryDetail(CardSummary.parse(base))).toBeUndefined();
  expect(summaryDetail(CardSummary.parse({ ...base, evidenceCount: 0, userStatedCount: 0 }))).toBe('근거 커밋 0개');
  // 빈 칸이 있으면 개수보다 빈 칸을 먼저 알려 준다
  expect(summaryDetail(CardSummary.parse({ ...base, evidenceCount: 3, star: { ...base.star, T: 'EMPTY' } }))).toBe('빈 칸 1개');
});

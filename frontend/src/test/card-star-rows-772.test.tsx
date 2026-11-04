import { cleanup, render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { Card } from '@/api/schemas';
import { CardPage } from '@/features/cards/CardPage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

/** #772 확정 카드 읽기 — 상황·과제·행동·결과가 왼쪽 라벨 열 + 오른쪽 본문의 행으로 나뉜다 */
vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/jobWatcher', () => ({ releaseJobToast: vi.fn(), suppressJobToast: vi.fn() }));
beforeEach(resetDb);
afterEach(() => { cleanup(); vi.clearAllMocks(); vi.restoreAllMocks(); });

const load = (id: string) => Card.parse(handle('GET', `/cards/${id}`, new URLSearchParams(), {}, true).data);
function mount(card: Card) {
  vi.spyOn(endpoints, 'card').mockResolvedValue(card);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  client.setQueryData(keys.card(card.id), card);
  const router = createMemoryRouter([{ path: '/cards/:cardId', element: <CardPage /> }], { initialEntries: [`/cards/${card.id}`] });
  return render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
}

describe('#772 확정 카드 읽기의 행', () => {
  it('네 칸이 라벨 열 + 본문 행 컨테이너 안에 있고 결과 행에만 결과 표시가 붙는다', () => {
    const { container } = mount(load('card_03'));
    const rows = Array.from(container.querySelectorAll('.star-read--rows > .star-read__row'));
    expect(rows.map((row) => row.querySelector('.star__name')?.textContent)).toEqual(['상황', '과제', '행동', '결과']);
    expect(rows.map((row) => row.classList.contains('star-read__row--result'))).toEqual([false, false, false, true]);
  });

  it('근거 버튼은 각 칸의 문장 바로 아래에 있다', () => {
    const { container } = mount(load('card_03'));
    const bodies = Array.from(container.querySelectorAll('.star-read--rows .star-row__body'));
    expect(bodies).toHaveLength(4);
    for (const body of bodies) expect(body.querySelector('.star__text')?.nextElementSibling).toBe(body.querySelector('.evidence-open'));
  });

  it('초안 화면의 근거 버튼은 읽기 화면 전용 범위 밖이라 상자 버튼 모양 그대로다', () => {
    const { container } = mount(load('card_01'));
    const buttons = Array.from(container.querySelectorAll('.evidence-open'));
    expect(buttons.length).toBeGreaterThan(0);
    for (const button of buttons) expect(button.closest('.star-read--rows')).toBeNull();
  });

  it('읽기 화면에는 커밋 레일(점과 선)이 없다', () => {
    const { container } = mount(load('card_03'));
    expect(container.querySelector('.evidence-rail, [data-rail], [data-commits]')).toBeNull();
  });
});

import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { Card } from '@/api/schemas';
import { CardPage } from '@/features/cards/CardPage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/toast', () => ({ toast: vi.fn() }));

const load = (id: string) => Card.parse(handle('GET', `/cards/${id}`, new URLSearchParams(), {}, true).data);

function mount(card: Card) {
  vi.spyOn(endpoints, 'card').mockResolvedValue(card);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(keys.card(card.id), card);
  const router = createMemoryRouter([{ path: '/cards/:cardId', element: <CardPage /> }], { initialEntries: [`/cards/${card.id}`] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  return router;
}

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe('확정 카드의 연필 — 위 수정 버튼 없이 고칠 곳 옆에서 고친다', () => {
  it('제목 옆 연필이 있고 위에는 복사 버튼 하나뿐이다', () => {
    mount(load('card_03'));
    expect(screen.getByRole('button', { name: '기본 정보 수정' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '수정' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '복사' })).toBeInTheDocument();
  });

  it('제목 연필은 카드를 다시 열지 않고 제목·기간 창을 열어 저장한다', async () => {
    const card = load('card_03');
    const reopen = vi.spyOn(endpoints, 'reopen');
    const save = vi.spyOn(endpoints, 'saveMetadata').mockResolvedValue({ cardId: card.id, title: '새 제목', period: card.period, themes: [], updatedAt: new Date().toISOString() } as never);
    mount(card);
    fireEvent.click(screen.getByRole('button', { name: '기본 정보 수정' }));
    const dialog = screen.getByRole('dialog', { name: '기본 정보 수정' });
    fireEvent.change(within(dialog).getByRole('textbox', { name: '카드 제목' }), { target: { value: '새 제목' } });
    fireEvent.click(within(dialog).getByRole('button', { name: '저장' }));
    await waitFor(() => expect(save).toHaveBeenCalledTimes(1));
    expect(save.mock.calls[0][1]).toEqual({ title: '새 제목' });
    expect(reopen).not.toHaveBeenCalled();
  });

  it('비운 채 확정한 칸에도 연필(채우기)이 있고, 누르면 카드를 다시 열어 그 칸으로 간다', async () => {
    const base = load('card_03');
    const card = { ...base, version: { ...base.version, result: null } };
    vi.spyOn(endpoints, 'reopen').mockResolvedValue({ ...card, status: 'DRAFT', confirmedAt: null });
    const router = mount(card);
    fireEvent.click(screen.getByRole('button', { name: '결과 채우기' }));
    await waitFor(() => expect(router.state.location.search).toBe('?mode=edit&field=R'));
  });
});

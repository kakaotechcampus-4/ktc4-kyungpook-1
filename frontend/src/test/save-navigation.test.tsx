import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import type { Card } from '@/api/schemas';
import { NewCardPage } from '@/features/cards/NewCardPage';
import { EditMode } from '@/features/cards/CardModes';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
let card: Card;
beforeEach(() => {
  resetDb();
  card = handle('GET', '/cards/card_01', new URLSearchParams(), {}, true).data as Card;
  vi.spyOn(endpoints, 'repos').mockResolvedValue([]);
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
function mount(element = <NewCardPage />) {
  const router = createMemoryRouter([
    { path: '/new', element },
    { path: '/', element: <p>Home destination</p> },
    { path: '/cards/:id', element: <p>Card destination</p> },
  ], { initialEntries: ['/new'] });
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}><RouterProvider router={router} /></QueryClientProvider>);
  return router;
}
describe('writing route save protection', () => {
  it('uses returned ID immediately and keeps failed first save on the page for retry', async () => {
    const create = vi.spyOn(endpoints, 'createManualDraft').mockResolvedValue({ ...card, id: 'returned-card' });
    const save = vi.spyOn(endpoints, 'saveDraft').mockRejectedValueOnce(new Error('offline')).mockResolvedValue({ ...card.version, cardId: card.id, savedAt: card.version.createdAt });
    const router = mount();
    fireEvent.change(screen.getByLabelText('카드 제목'), { target: { value: '  Original title  ' } });
    fireEvent.change(screen.getByLabelText('상황 (Situation)'), { target: { value: '  original\n' } });
    fireEvent.click(screen.getByRole('button', { name: '나중에 이어서' }));
    await screen.findByRole('button', { name: '다시 저장' });
    expect(router.state.location.pathname).toBe('/new');
    expect(save).toHaveBeenCalledWith('returned-card', expect.objectContaining({ situation: '  original\n' }));
    expect(screen.getByLabelText('카드 제목')).toBeDisabled();
    expect(screen.getByLabelText('기간')).toBeDisabled();
    expect(screen.getByLabelText('상황 (Situation)')).toHaveValue('  original\n');
    fireEvent.click(screen.getByRole('button', { name: '나중에 이어서' }));
    await screen.findByText('Card destination');
    expect(create).toHaveBeenCalledTimes(1);
    expect(create.mock.calls[0][0]).toEqual({ title: '  Original title  ', period: '', repoId: null });
  });

  it('guards reload and in-app navigation, stays after failure, and discards only explicitly', async () => {
    vi.spyOn(endpoints, 'createManualDraft').mockRejectedValue(new Error('offline'));
    const router = mount();
    fireEvent.change(screen.getByLabelText('카드 제목'), { target: { value: 'unsaved' } });
    const unload = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(unload);
    expect(unload.defaultPrevented).toBe(true);
    fireEvent.click(screen.getByRole('link', { name: '나가기' }));
    await screen.findByRole('dialog');
    fireEvent.click(screen.getByRole('button', { name: '계속 작성' }));
    expect(router.state.location.pathname).toBe('/new');
    fireEvent.click(screen.getByRole('link', { name: '나가기' }));
    fireEvent.click(screen.getByRole('button', { name: '저장하고 이동' }));
    await screen.findByRole('alert');
    expect(router.state.location.pathname).toBe('/new');
    fireEvent.click(screen.getByRole('button', { name: '저장하지 않고 나가기' }));
    await screen.findByText('Home destination');
    const cleanUnload = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(cleanUnload);
    expect(cleanUnload.defaultPrevented).toBe(false);
  });

  it('does not close edit mode or lose input when its save fails', async () => {
    const done = vi.fn();
    vi.spyOn(endpoints, 'saveDraft').mockRejectedValueOnce(new Error('offline')).mockResolvedValue({ ...card.version, cardId: card.id, savedAt: card.version.createdAt });
    mount(<EditMode card={card} onDone={done} />);
    fireEvent.change(screen.getByLabelText('상황 (Situation)'), { target: { value: 'Changed situation' } });
    fireEvent.click(screen.getByRole('button', { name: '저장하고 닫기' }));
    await screen.findByRole('button', { name: '다시 저장하고 닫기' });
    expect(done).not.toHaveBeenCalled();
    expect(screen.getByLabelText('상황 (Situation)')).toHaveValue('Changed situation');
    fireEvent.click(screen.getByRole('button', { name: '다시 저장하고 닫기' }));
    await waitFor(() => expect(done).toHaveBeenCalledTimes(1));
  });
});

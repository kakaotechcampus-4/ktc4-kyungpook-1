import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import type { Card } from '@/api/schemas';
import { NewCardPage } from '@/features/cards/NewCardPage';
import { EditMode } from '@/features/cards/CardModes';
import { CardPage } from '@/features/cards/CardPage';
import { keys } from '@/api/keys';
import { CONFIG } from '@/lib/config';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
let card: Card;
beforeEach(() => {
  resetDb();
  card = handle('GET', '/cards/card_01', new URLSearchParams(), {}, true).data as Card;
  vi.spyOn(endpoints, 'repos').mockResolvedValue([]);
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers(); });
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
  it('opens a newly saved manual card and re-edits acknowledged fields when its detail GET fails', async () => {
    const blank = { ...card, id: 'manual-cache', version: { ...card.version, situation: null, task: null, action: null, result: null } };
    vi.spyOn(endpoints, 'createManualDraft').mockResolvedValue(blank);
    vi.spyOn(endpoints, 'saveDraft').mockImplementation(async (id, fields) => ({ ...fields, cardId: id, savedAt: card.version.createdAt, versionNo: 2 }));
    vi.spyOn(endpoints, 'card').mockRejectedValue(new Error('offline detail'));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const router = createMemoryRouter([{ path: '/new', element: <NewCardPage /> }, { path: '/cards/:cardId', element: <CardPage /> }], { initialEntries: ['/new'] });
    render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
    fireEvent.change(screen.getByLabelText('카드 제목'), { target: { value: 'Manual cache' } });
    fireEvent.change(screen.getByLabelText('상황 (Situation)'), { target: { value: 'Saved manual situation' } });
    fireEvent.click(screen.getByRole('button', { name: '저장 후 종료' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/cards/manual-cache'));
    await waitFor(() => expect(client.getQueryState(keys.card('manual-cache'))?.status).toBe('error'));
    expect(screen.getByText('Saved manual situation')).toBeInTheDocument();
    await act(async () => { await router.navigate('/cards/manual-cache?mode=edit'); });
    expect(screen.getByLabelText('상황 (Situation)')).toHaveValue('Saved manual situation');
  });
  it.each(['guard', 'autosave'] as const)('keeps saved text across %s then Back and re-edit when detail refresh is unavailable', async (mode) => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
    client.setQueryData(keys.card(card.id), card);
    vi.spyOn(endpoints, 'card').mockRejectedValue(new Error('offline refresh'));
    vi.spyOn(endpoints, 'saveDraft').mockImplementation(async (id, fields) => ({ ...fields, cardId: id, savedAt: card.version.createdAt, versionNo: 2 }));
    const router = createMemoryRouter([{ path: '/cards/:cardId', element: <CardPage /> }], { initialEntries: [`/cards/${card.id}`, `/cards/${card.id}?mode=edit`], initialIndex: 1 });
    render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
    vi.useFakeTimers();
    fireEvent.change(screen.getByLabelText('상황 (Situation)'), { target: { value: 'Latest acknowledged situation' } });
    if (mode === 'autosave') await act(async () => { await vi.advanceTimersByTimeAsync(CONFIG.DRAFT_AUTOSAVE_MS + 1); });
    await act(async () => { await router.navigate(-1); });
    if (mode === 'guard') {
      fireEvent.click(screen.getByRole('button', { name: '저장하고 이동' }));
      await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    }
    vi.useRealTimers();
    await waitFor(() => expect(router.state.location.search).toBe(''));
    expect(screen.getByText('Latest acknowledged situation')).toBeInTheDocument();
    await act(async () => { await client.invalidateQueries({ queryKey: keys.card(card.id), exact: true }); });
    await act(async () => { await router.navigate(`/cards/${card.id}?mode=edit`); });
    expect(screen.getByLabelText('상황 (Situation)')).toHaveValue('Latest acknowledged situation');
    expect(client.getQueryData<Card>(keys.card(card.id))?.version.versionNo).toBe(2);
  });
  it('uses returned ID immediately and keeps failed first save on the page for retry', async () => {
    const create = vi.spyOn(endpoints, 'createManualDraft').mockResolvedValue({ ...card, id: 'returned-card' });
    const save = vi.spyOn(endpoints, 'saveDraft').mockRejectedValueOnce(new Error('offline')).mockResolvedValue({ ...card.version, cardId: card.id, savedAt: card.version.createdAt });
    const router = mount();
    fireEvent.change(screen.getByLabelText('카드 제목'), { target: { value: '  Original title  ' } });
    fireEvent.change(screen.getByLabelText('상황 (Situation)'), { target: { value: '  original\n' } });
    fireEvent.click(screen.getByRole('button', { name: '저장 후 종료' }));
    await screen.findByRole('button', { name: '다시 저장' });
    expect(router.state.location.pathname).toBe('/new');
    expect(save).toHaveBeenCalledWith('returned-card', expect.objectContaining({ situation: '  original\n' }));
    expect(screen.getByLabelText('카드 제목')).toBeDisabled();
    expect(screen.getByLabelText('기간')).toBeDisabled();
    expect(screen.getByLabelText('상황 (Situation)')).toHaveValue('  original\n');
    fireEvent.click(screen.getByRole('button', { name: '저장 후 종료' }));
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

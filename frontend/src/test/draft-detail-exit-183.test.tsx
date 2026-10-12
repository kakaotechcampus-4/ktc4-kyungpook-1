import { StrictMode } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import type { Card } from '@/api/schemas';
import { CardPage } from '@/features/cards/CardPage';
import { CONFIG } from '@/lib/config';
import { isDraftMetadataPending } from '@/lib/cacheSavedDraft';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
let card: Card;
beforeEach(() => {
  resetDb();
  const original = handle('GET', '/cards/card_01', new URLSearchParams(), {}, true).data as Card;
  card = { ...original, generation: null,
    version: { ...original.version, source: 'AI_DRAFT', situation: 'Original situation', task: 'Original task', action: 'Original action', result: null },
    evidence: [{ field: 'S', type: 'COMMIT', authoredBy: 'AI', sha: '1234567', url: null, snippet: 'Original evidence', turnNo: null }],
    lowConfidenceFields: [{ field: 'S', why: 'Original warning' }],
    droppedFields: [{ field: 'R', reason: 'NO_EVIDENCE' }],
  };
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers(); });

function mount() {
  let server = card;
  const save = vi.spyOn(endpoints, 'saveDraft').mockImplementation(async (id, fields) => {
    const restored = fields.situation === card.version.situation;
    server = { ...card, version: { ...card.version, ...fields, source: 'USER_EDIT', versionNo: 2 },
      evidence: [{ ...card.evidence[0], snippet: restored ? 'Restored evidence' : 'Saved evidence' }],
      lowConfidenceFields: [{ field: 'S', why: restored ? 'Restored warning' : 'Saved warning' }],
    };
    return { ...fields, cardId: id, versionNo: 2, savedAt: server.version.createdAt };
  });
  const detail = vi.spyOn(endpoints, 'card').mockImplementation(async () => server);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  client.setQueryData(keys.card(card.id), card);
  const router = createMemoryRouter([{ path: '/cards/:cardId', element: <CardPage /> }], {
    initialEntries: [`/cards/${card.id}`, `/cards/${card.id}?mode=edit`], initialIndex: 1,
  });
  render(<StrictMode><QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider></StrictMode>);
  return { client, router, save, detail, server: () => server };
}

async function typeAndAutosave(text: string) {
  vi.useFakeTimers();
  fireEvent.change(screen.getByLabelText('상황 (Situation)'), { target: { value: text } });
  await act(async () => { await vi.advanceTimersByTimeAsync(CONFIG.DRAFT_AUTOSAVE_MS + 1); });
  vi.useRealTimers();
  await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('저장됨'));
}

describe('saved draft metadata when returning to card detail (#183)', () => {
  it.each(['cancel', 'back', 'guard'] as const)('reconciles metadata once after %s without fetching during active editing', async (exit) => {
    const { client, router, save, detail, server } = mount();
    await typeAndAutosave('Acknowledged situation');
    expect(detail).not.toHaveBeenCalled();
    expect(isDraftMetadataPending(client.getQueryData<Card>(keys.card(card.id)))).toBe(true);
    expect(screen.getByLabelText('상황 (Situation)')).toHaveValue('Acknowledged situation');
    if (exit === 'cancel') fireEvent.click(screen.getByRole('button', { name: '편집 취소' }));
    else {
      if (exit === 'guard') fireEvent.change(screen.getByLabelText('상황 (Situation)'), { target: { value: 'Guard acknowledged situation' } });
      await act(async () => { await router.navigate(-1); });
      if (exit === 'guard') fireEvent.click(screen.getByRole('button', { name: '저장하고 이동' }));
    }
    await waitFor(() => expect(router.state.location.search).toBe(''));
    await waitFor(() => expect(detail).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(client.getQueryData<Card>(keys.card(card.id))).toEqual(server()));
    expect(screen.getByText('직접 작성한 내용')).toBeInTheDocument();
    expect(screen.getByText(exit === 'cancel' ? /Restored evidence/ : /Saved evidence/)).toBeInTheDocument();
    expect(screen.getByText(exit === 'cancel' ? 'Restored warning' : 'Saved warning')).toBeInTheDocument();
    expect(save).toHaveBeenCalledTimes(exit === 'back' ? 1 : 2);
    await act(async () => { await router.navigate(`/cards/${card.id}?versions=0`); });
    expect(detail).toHaveBeenCalledTimes(1);
  });

  it.each(['cancel', 'back', 'guard'] as const)('keeps ACK text and exits after %s when the nonblocking detail GET fails', async (exit) => {
    const { client, router, save, detail } = mount();
    let reject!: (error: Error) => void;
    detail.mockImplementation(() => new Promise<Card>((_resolve, fail) => { reject = fail; }));
    await typeAndAutosave('Acknowledged situation');
    if (exit === 'cancel') fireEvent.click(screen.getByRole('button', { name: '편집 취소' }));
    else {
      if (exit === 'guard') fireEvent.change(screen.getByLabelText('상황 (Situation)'), { target: { value: 'Guard acknowledged situation' } });
      await act(async () => { await router.navigate(-1); });
      if (exit === 'guard') fireEvent.click(screen.getByRole('button', { name: '저장하고 이동' }));
    }
    await waitFor(() => expect(router.state.location.search).toBe(''));
    const expected = exit === 'cancel' ? 'Original situation' : exit === 'guard' ? 'Guard acknowledged situation' : 'Acknowledged situation';
    expect(screen.getByText(expected)).toBeInTheDocument();
    await waitFor(() => expect(detail).toHaveBeenCalledTimes(1));
    await act(async () => { reject(new Error('offline detail')); });
    await waitFor(() => expect(client.getQueryState(keys.card(card.id))?.status).toBe('error'));
    expect(screen.getByText(expected)).toBeInTheDocument();
    expect(screen.getByText('본문 저장됨')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '다시 저장' })).not.toBeInTheDocument();
    await act(async () => { await router.navigate(`/cards/${card.id}?versions=0`); });
    expect(detail).toHaveBeenCalledTimes(1);
    await act(async () => { await router.navigate(`/cards/${card.id}?mode=edit`); });
    expect(screen.getByLabelText('상황 (Situation)')).toHaveValue(expected);
    expect(save).toHaveBeenCalledTimes(exit === 'back' ? 1 : 2);
  });

  it('does not read or write when canceling an untouched edit session', async () => {
    const { router, save, detail } = mount();
    fireEvent.click(screen.getByRole('button', { name: '편집 취소' }));
    await waitFor(() => expect(router.state.location.search).toBe(''));
    expect(save).not.toHaveBeenCalled();
    expect(detail).not.toHaveBeenCalled();
  });

  it('joins the existing save-and-close detail GET without restarting it', async () => {
    const { router, detail, server } = mount();
    let resolve!: (value: Card) => void;
    detail.mockImplementation(() => new Promise<Card>((accept) => { resolve = accept; }));
    fireEvent.change(screen.getByLabelText('상황 (Situation)'), { target: { value: 'Close acknowledged situation' } });
    fireEvent.click(screen.getByRole('button', { name: '저장하고 닫기' }));
    await waitFor(() => expect(router.state.location.search).toBe(''));
    expect(screen.getByText('Close acknowledged situation')).toBeInTheDocument();
    expect(detail).toHaveBeenCalledTimes(1);
    await act(async () => { resolve(server()); });
    await screen.findByText(/Saved evidence/);
    expect(detail).toHaveBeenCalledTimes(1);
  });
});

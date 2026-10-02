import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { Card, ConfirmResult } from '@/api/schemas';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { ConfirmDialog } from '@/features/cards/CardDialogs';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

let card: Card;
let result: ConfirmResult;
beforeEach(() => {
  resetDb();
  card = Card.parse(handle('GET', '/cards/card_01', new URLSearchParams(), {}, true).data);
  result = ConfirmResult.parse(handle('POST', '/cards/card_01/confirm', new URLSearchParams(), { edited: false, maskedFields: [] }, true).data);
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
function mount(value = card) {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  const confirmed = vi.fn();
  render(<QueryClientProvider client={client}><ConfirmDialog card={value} onClose={vi.fn()} onConfirmed={confirmed} /></QueryClientProvider>);
  return confirmed;
}
it('confirms directly without a self-attestation checkbox and keeps the API payload', async () => {
  const send = vi.spyOn(endpoints, 'confirm').mockResolvedValue(result);
  const confirmed = mount();
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '확정하기' })).toBeEnabled();
  fireEvent.click(screen.getByRole('button', { name: '확정하기' }));
  await waitFor(() => expect(confirmed).toHaveBeenCalledWith(result));
  expect(send).toHaveBeenCalledWith(card.id, { edited: false, maskedFields: [] });
});
it('keeps required STAR fields enforced even when the modal is opened directly', () => {
  const send = vi.spyOn(endpoints, 'confirm');
  mount({ ...card, version: { ...card.version, situation: null } });
  expect(screen.getByRole('button', { name: '확정하기' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: '확정하기' }));
  expect(send).not.toHaveBeenCalled();
});
it('finishes the confirmation only after the card refetch, so the page never shows the stale DRAFT', async () => {
  vi.spyOn(endpoints, 'confirm').mockResolvedValue(result);
  const confirmedCard: Card = { ...card, status: 'CONFIRMED' };
  vi.spyOn(endpoints, 'card').mockImplementation(() => new Promise((resolve) => setTimeout(() => resolve(confirmedCard), 60)));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  client.setQueryData(keys.card(card.id), card);
  let statusWhenConfirmed: string | undefined;
  const confirmed = vi.fn(() => { statusWhenConfirmed = client.getQueryData<Card>(keys.card(card.id))?.status; });
  function Observer() { useQuery({ queryKey: keys.card(card.id), queryFn: () => endpoints.card(card.id), staleTime: Infinity }); return null; }
  render(<QueryClientProvider client={client}><Observer /><ConfirmDialog card={card} onClose={vi.fn()} onConfirmed={confirmed} /></QueryClientProvider>);
  fireEvent.click(screen.getByRole('button', { name: '확정하기' }));
  await waitFor(() => expect(confirmed).toHaveBeenCalledTimes(1));
  expect(statusWhenConfirmed).toBe('CONFIRMED');
});
it('reports fields that were never written as empty even when the server dropped none', () => {
  mount({ ...card, droppedFields: [], version: { ...card.version, task: null, result: null } });
  expect(screen.getByText('2칸 — 빈 채로 확정됩니다')).toBeInTheDocument();
});
it('keeps the confirmation open on a failed request', async () => {
  vi.spyOn(endpoints, 'confirm').mockRejectedValue(new Error('일시적인 오류'));
  const confirmed = mount();
  fireEvent.click(screen.getByRole('button', { name: '확정하기' }));
  await screen.findByText('확정하지 못했습니다');
  expect(screen.getByRole('dialog')).toBeInTheDocument();
  expect(confirmed).not.toHaveBeenCalled();
});

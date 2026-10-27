import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import type { Card } from '@/api/schemas';
import { CardPage } from '@/features/cards/CardPage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
let card: Card;
beforeEach(() => {
  resetDb();
  const original = handle('GET', '/cards/card_01', new URLSearchParams(), {}, true).data as Card;
  card = { ...original, status: 'DRAFT', generation: null, draftFailure: 'AI_UNAVAILABLE',
    version: { ...original.version, situation: null, task: null, action: null, result: null } };
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

function mount(current: Card) {
  vi.spyOn(endpoints, 'card').mockResolvedValue(current);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  client.setQueryData(keys.card(current.id), current);
  const router = createMemoryRouter([{ path: '/cards/:cardId', element: <CardPage /> }], { initialEntries: [`/cards/${current.id}`] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
}

describe('#419 a card whose AI draft failed can be drafted again', () => {
  it('explains a temporary AI failure and retries the draft for this card', async () => {
    const retry = vi.spyOn(endpoints, 'retryDraft').mockResolvedValue({ jobId: 'job_2' });
    mount(card);

    expect(screen.getByText('AI 초안을 만들지 못했어요')).toBeInTheDocument();
    expect(screen.getByText(/AI 가 잠시 응답하지 않았어요/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '초안 다시 만들기' }));

    await waitFor(() => expect(retry).toHaveBeenCalledWith(card.id));
  });

  it('suggests writing the fields by hand for other failures', () => {
    mount({ ...card, draftFailure: 'INTERNAL_ERROR' });

    expect(screen.getByText(/다시 시도하거나 칸을 직접 채워 주세요/)).toBeInTheDocument();
  });

  it('shows why the retry was refused', async () => {
    vi.spyOn(endpoints, 'retryDraft').mockRejectedValue(new ApiError('RATE_LIMITED', 'hidden', 429, { retryAfterSec: 3600 }));
    mount(card);

    fireEvent.click(screen.getByRole('button', { name: '초안 다시 만들기' }));

    expect(await screen.findByText('다시 시도하지 못했어요')).toBeInTheDocument();
    expect(screen.getByText(/오늘 사용 한도를 다 썼어요|내일 다시 시도해 주세요/)).toBeInTheDocument();
  });

  it('shows nothing when the draft did not fail or the card is confirmed', () => {
    mount({ ...card, draftFailure: null });
    expect(screen.queryByText('AI 초안을 만들지 못했어요')).not.toBeInTheDocument();
    cleanup();
    mount({ ...card, status: 'CONFIRMED' });
    expect(screen.queryByText('AI 초안을 만들지 못했어요')).not.toBeInTheDocument();
  });
});

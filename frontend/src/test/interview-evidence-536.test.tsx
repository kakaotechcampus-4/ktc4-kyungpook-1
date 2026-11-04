import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import type { Card, InterviewTurn } from '@/api/schemas';
import { InterviewPage } from '@/features/cards/InterviewPage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
let card: Card;
let template: InterviewTurn;
beforeEach(() => {
  resetDb();
  card = handle('GET', '/cards/card_01', new URLSearchParams(), {}, true).data as Card;
  template = handle('POST', '/cards/card_01/interview', new URLSearchParams(), { field: 'T' }, true).data as InterviewTurn;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

async function mount(found: string[], missing: string[]) {
  const turns = [{ ...template, answer: null, found, missing }];
  vi.spyOn(endpoints, 'interviewTurns').mockResolvedValue(turns);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  client.setQueryData(keys.card(card.id), card);
  client.setQueryData(keys.interview(card.id), turns);
  const router = createMemoryRouter([{ path: '/cards/:cardId/interview', element: <InterviewPage /> }], { initialEntries: [`/cards/${card.id}/interview?field=T`] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  await screen.findByRole('textbox', { name: '답변' });
}

describe('#536 되묻기 질문 근거', () => {
  it('찾은 것·없는 것이 둘 다 비면 질문 근거 보기를 그리지 않는다', async () => {
    await mount([], []);
    expect(screen.queryByText('질문 근거 보기')).not.toBeInTheDocument();
    expect(screen.queryByText('코드에서 찾은 것')).not.toBeInTheDocument();
  });

  it('하나라도 있으면 질문 근거 보기를 그린다', async () => {
    await mount([], ['그때 왜 되돌렸는지']);
    expect(screen.getByText('질문 근거 보기')).toBeInTheDocument();
    expect(screen.getByText('• 그때 왜 되돌렸는지')).toBeInTheDocument();
  });
});

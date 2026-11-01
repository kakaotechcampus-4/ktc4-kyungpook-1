import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import type { Card, InterviewTurn, StarField } from '@/api/schemas';
import { InterviewPage } from '@/features/cards/InterviewPage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
let card: Card;
let template: InterviewTurn;
beforeEach(() => {
  resetDb();
  card = handle('GET', '/cards/card_01', new URLSearchParams(), {}, true).data as Card;
  card = { ...card, version: { ...card.version, task: null, result: null } };
  template = handle('POST', '/cards/card_01/interview', new URLSearchParams(), { field: 'T' }, true).data as InterviewTurn;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
function mount(turns: InterviewTurn[], suffix = '') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  client.setQueryData(keys.card(card.id), card);
  client.setQueryData(keys.interview(card.id), turns);
  const router = createMemoryRouter([{ path: '/cards/:cardId/interview', element: <InterviewPage /> }], { initialEntries: [`/cards/${card.id}/interview${suffix}`] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
}
describe('interview retry and pending answer', () => {
  it.each(['?field=T', ''])('retries the failed R question with initial URL %s', async (suffix) => {
    let turns: InterviewTurn[] = [];
    let failedR = false;
    vi.spyOn(endpoints, 'interviewTurns').mockImplementation(async () => turns);
    const ask = vi.spyOn(endpoints, 'askInterview').mockImplementation(async (_id, field: StarField) => {
      if (field === 'R' && !failedR) { failedR = true; throw new Error('offline'); }
      const turn = { ...template, field, turnNo: turns.length + 1, question: `${field} question`, answer: null };
      turns = [...turns, turn];
      return turn;
    });
    vi.spyOn(endpoints, 'answerInterview').mockImplementation(async (_id, no, answer) => {
      turns = turns.map((turn) => turn.turnNo === no ? { ...turn, answer } : turn);
      return card;
    });
    mount(turns, suffix);
    if (suffix) {
      await screen.findByRole('textbox', { name: '답변' });
      fireEvent.change(screen.getByRole('textbox', { name: '답변' }), { target: { value: 'T answer' } });
      fireEvent.click(screen.getByRole('button', { name: '답변 저장' }));
      await screen.findByText('이어서 물을까요?');
    }
    fireEvent.click(screen.getByRole('button', { name: /결과 \(비어 있음\)/ }));
    const retry = await screen.findByRole('button', { name: /다시/ });
    fireEvent.click(retry);
    await waitFor(() => expect(ask.mock.calls.map((call) => call[1])).toEqual(suffix ? ['T', 'R', 'R'] : ['R', 'R']));
    await screen.findByRole('textbox', { name: '답변' });
    expect(screen.getByRole('heading', { name: /R question/ })).toBeInTheDocument();
  });

  it('says the card is confirmed and stops offering slots when the answer completes the card', async () => {
    let turns: InterviewTurn[] = [{ ...template, field: 'R', turnNo: 1, question: 'R question', answer: null }];
    vi.spyOn(endpoints, 'interviewTurns').mockImplementation(async () => turns);
    vi.spyOn(endpoints, 'answerInterview').mockImplementation(async (_id, no, answer) => {
      turns = turns.map((turn) => turn.turnNo === no ? { ...turn, answer } : turn);
      return { ...card, status: 'CONFIRMED', confirmedAt: '2026-11-01T00:00:00Z' };
    });
    mount(turns);
    fireEvent.change(await screen.findByRole('textbox', { name: '답변' }), { target: { value: 'R answer' } });
    fireEvent.click(screen.getByRole('button', { name: '답변 저장' }));
    expect(await screen.findByText('네 칸이 모두 차서 카드를 확정했어요')).toBeInTheDocument();
    expect(screen.queryByText('이어서 물을까요?')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '카드로 돌아가기' })).toBeInTheDocument();
  });

  it('locks option chips while an answer is being saved', async () => {
    let resolve!: (value: Card) => void;
    vi.spyOn(endpoints, 'answerInterview').mockImplementation(() => new Promise<Card>((r) => { resolve = r; }));
    vi.spyOn(endpoints, 'interviewTurns').mockResolvedValue([]);
    mount([{ ...template, options: ['First option', 'Second option'], answer: null }]);
    fireEvent.click(screen.getByRole('button', { name: 'First option' }));
    fireEvent.click(screen.getByRole('button', { name: '답변 저장' }));
    await waitFor(() => expect(screen.getByRole('textbox', { name: '답변' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Second option' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Second option' }));
    expect(screen.getByRole('textbox', { name: '답변' })).toHaveValue('First option');
    await act(async () => { resolve(card); });
  });

  it('shows why an answer was rejected instead of blaming the connection', async () => {
    vi.spyOn(endpoints, 'answerInterview').mockRejectedValue(new ApiError('INVALID_REQUEST', 'hidden', 400));
    vi.spyOn(endpoints, 'interviewTurns').mockResolvedValue([]);
    mount([{ ...template, answer: null }]);
    fireEvent.change(screen.getByRole('textbox', { name: '답변' }), { target: { value: 'T answer' } });
    fireEvent.click(screen.getByRole('button', { name: '답변 저장' }));
    expect(await screen.findByText('입력 내용과 현재 상태를 확인해 주세요.')).toBeInTheDocument();
    expect(screen.queryByText(/연결을 확인/)).not.toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: '답변' })).toHaveValue('T answer');
  });

  it('still asks to check the connection when the server cannot be reached', async () => {
    vi.spyOn(endpoints, 'answerInterview').mockRejectedValue(new ApiError('NETWORK', 'hidden', 0));
    vi.spyOn(endpoints, 'interviewTurns').mockResolvedValue([]);
    mount([{ ...template, answer: null }]);
    fireEvent.change(screen.getByRole('textbox', { name: '답변' }), { target: { value: 'T answer' } });
    fireEvent.click(screen.getByRole('button', { name: '답변 저장' }));
    expect(await screen.findByText(/연결을 확인한 뒤 다시 시도해 주세요/)).toBeInTheDocument();
  });
});

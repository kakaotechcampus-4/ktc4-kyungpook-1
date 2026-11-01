import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { z } from 'zod';
import { envelope, InterviewTurn as InterviewTurnSchema, isOpenTurn, type Card, type InterviewTurn, type StarField } from '@/api/schemas';
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
    const ask = vi.spyOn(endpoints, 'askInterview').mockImplementation(async (_id, field?: StarField | null) => {
      if (!field) return null;
      if (field === 'R' && !failedR) { failedR = true; throw new Error('offline'); }
      const turn = { ...template, field, turnNo: turns.length + 1, question: `${field} question`, answer: null };
      turns = [...turns, turn];
      return turn;
    });
    vi.spyOn(endpoints, 'answerInterview').mockImplementation(async (_id, no, body) => {
      if (!('text' in body)) throw new Error('이 시험은 글 답만 보낸다');
      turns = turns.map((turn) => turn.turnNo === no ? { ...turn, answer: body } : turn);
      return card;
    });
    mount(turns, suffix);
    if (suffix) {
      await screen.findByRole('textbox', { name: '답변' });
      fireEvent.change(screen.getByRole('textbox', { name: '답변' }), { target: { value: 'T answer' } });
      fireEvent.click(screen.getByRole('button', { name: '답변 저장' }));
    }
    fireEvent.click(await screen.findByRole('button', { name: /결과 \(비어 있음\)/ }));
    const retry = await screen.findByRole('button', { name: /다시/ });
    fireEvent.click(retry);
    await waitFor(() => expect(ask.mock.calls.map((call) => call[1])).toEqual(suffix ? ['T', undefined, 'R', 'R'] : [undefined, 'R', 'R']));
    await screen.findByRole('textbox', { name: '답변' });
    expect(screen.getByRole('heading', { name: /R question/ })).toBeInTheDocument();
  });

  it('says the card is confirmed and stops offering slots when the answer completes the card', async () => {
    let turns: InterviewTurn[] = [{ ...template, field: 'R', turnNo: 1, question: 'R question', answer: null }];
    vi.spyOn(endpoints, 'interviewTurns').mockImplementation(async () => turns);
    vi.spyOn(endpoints, 'answerInterview').mockImplementation(async (_id, no, answer) => {
      if (!('text' in answer)) throw new Error('이 시험은 글 답만 보낸다');
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

describe('#417 skip saves SKIP instead of leaving the question open', () => {
  it('saves SKIP, shows the question as skipped and asks which field to continue with', async () => {
    let turns: InterviewTurn[] = [{ ...template, field: 'T', answer: null, outcome: null }];
    vi.spyOn(endpoints, 'interviewTurns').mockImplementation(async () => turns);
    const save = vi.spyOn(endpoints, 'answerInterview').mockImplementation(async (_id, no, body) => {
      turns = turns.map((turn) => turn.turnNo === no ? { ...turn, outcome: 'SKIPPED' } : turn);
      expect(body).toEqual({ kind: 'SKIP' });
      return card;
    });
    mount(turns);

    fireEvent.click(await screen.findByRole('button', { name: '건너뛰기' }));

    await screen.findByText('이어서 물을까요?');
    expect(save).toHaveBeenCalledWith(card.id, template.turnNo, { kind: 'SKIP' });
    expect(screen.getByText('넘긴 질문')).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: '답변' })).not.toBeInTheDocument();
  });

  it('does not offer a skipped field again because the server will not ask it twice', async () => {
    const skipped = { ...template, field: 'T' as StarField, answer: null, outcome: 'SKIPPED' as const };
    vi.spyOn(endpoints, 'interviewTurns').mockResolvedValue([skipped]);
    mount([skipped]);

    await screen.findByText('이어서 물을까요?');
    expect(screen.queryByRole('button', { name: /^과제/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /결과 \(비어 있음\)/ })).toBeInTheDocument();
  });

  it('keeps the question open and explains why when skipping fails', async () => {
    vi.spyOn(endpoints, 'interviewTurns').mockResolvedValue([{ ...template, answer: null, outcome: null }]);
    vi.spyOn(endpoints, 'answerInterview').mockRejectedValue(new ApiError('INVALID_STATE', 'hidden', 409));
    mount([{ ...template, answer: null, outcome: null }]);

    fireEvent.click(await screen.findByRole('button', { name: '건너뛰기' }));

    expect(await screen.findByText('질문을 넘기지 못했어요')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: '답변' })).toBeInTheDocument();
  });

  it('treats a turn without outcome (demo data) as open and LATER as still waiting', () => {
    expect(InterviewTurnSchema.parse({ ...template, outcome: undefined }).outcome).toBeNull();
    expect(isOpenTurn({ ...template, answer: null, outcome: 'LATER' })).toBe(true);
    expect(isOpenTurn({ ...template, answer: null, outcome: 'SKIPPED' })).toBe(false);
  });
});

describe('#418 AI picks the next field and ends the interview by itself', () => {
  // 실서버 상한(3)으로 맞춘다. 목 서버 상한은 2다
  const open = (field: StarField, turnNo = 1) => ({ ...template, field, turnNo, answer: null, outcome: null, maxTurns: 3 }) as InterviewTurn;

  it('asks for the next question without a field after an answer and shows it', async () => {
    let turns: InterviewTurn[] = [open('T')];
    vi.spyOn(endpoints, 'interviewTurns').mockImplementation(async () => turns);
    vi.spyOn(endpoints, 'answerInterview').mockImplementation(async (_id, no) => {
      turns = turns.map((turn) => turn.turnNo === no ? { ...turn, answer: { text: 'T answer', source: 'USER_STATED' }, outcome: 'ANSWERED' } : turn);
      return card;
    });
    const ask = vi.spyOn(endpoints, 'askInterview').mockImplementation(async () => {
      const next = { ...open('R', 2), question: 'AI 가 고른 R 질문' };
      turns = [...turns, next];
      return next;
    });
    mount(turns);

    fireEvent.change(await screen.findByRole('textbox', { name: '답변' }), { target: { value: 'T answer' } });
    fireEvent.click(screen.getByRole('button', { name: '답변 저장' }));

    expect(await screen.findByRole('heading', { name: /AI 가 고른 R 질문/ })).toBeInTheDocument();
    expect(ask).toHaveBeenCalledWith(card.id, undefined);
  });

  it('shows the finished state when the AI has nothing more to ask', async () => {
    let turns: InterviewTurn[] = [open('T')];
    vi.spyOn(endpoints, 'interviewTurns').mockImplementation(async () => turns);
    vi.spyOn(endpoints, 'answerInterview').mockImplementation(async (_id, no) => {
      turns = turns.map((turn) => turn.turnNo === no ? { ...turn, answer: { text: 'T answer', source: 'USER_STATED' }, outcome: 'ANSWERED' } : turn);
      return card;
    });
    vi.spyOn(endpoints, 'askInterview').mockResolvedValue(null);
    mount(turns);

    fireEvent.change(await screen.findByRole('textbox', { name: '답변' }), { target: { value: 'T answer' } });
    fireEvent.click(screen.getByRole('button', { name: '답변 저장' }));

    expect(await screen.findByText('AI 가 더 물을 것이 없다고 판단했어요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '카드 확인하고 확정하기' })).toBeInTheDocument();
  });

  it('continues with the next question after skipping', async () => {
    let turns: InterviewTurn[] = [open('T')];
    vi.spyOn(endpoints, 'interviewTurns').mockImplementation(async () => turns);
    vi.spyOn(endpoints, 'answerInterview').mockImplementation(async (_id, no) => {
      turns = turns.map((turn) => turn.turnNo === no ? { ...turn, outcome: 'SKIPPED' } : turn);
      return card;
    });
    const ask = vi.spyOn(endpoints, 'askInterview').mockResolvedValue(null);
    mount(turns);

    fireEvent.click(await screen.findByRole('button', { name: '건너뛰기' }));

    await screen.findByText('AI 가 더 물을 것이 없다고 판단했어요');
    expect(ask).toHaveBeenCalledWith(card.id, undefined);
  });

  it('does not ask again once the question limit is used up', async () => {
    let turns: InterviewTurn[] = [
      { ...open('S', 1), answer: { text: 's', source: 'USER_STATED' }, outcome: 'ANSWERED' },
      { ...open('A', 2), outcome: 'SKIPPED' },
      open('T', 3),
    ];
    vi.spyOn(endpoints, 'interviewTurns').mockImplementation(async () => turns);
    vi.spyOn(endpoints, 'answerInterview').mockImplementation(async (_id, no) => {
      turns = turns.map((turn) => turn.turnNo === no ? { ...turn, answer: { text: 't', source: 'USER_STATED' }, outcome: 'ANSWERED' } : turn);
      return card;
    });
    const ask = vi.spyOn(endpoints, 'askInterview').mockResolvedValue(null);
    mount(turns);

    fireEvent.change(await screen.findByRole('textbox', { name: '답변' }), { target: { value: 't' } });
    fireEvent.click(screen.getByRole('button', { name: '답변 저장' }));

    expect(await screen.findByText(/질문 3개를 다 썼어요/)).toBeInTheDocument();
    expect(ask).not.toHaveBeenCalled();
  });

  it('rejects an empty success envelope unless the endpoint allows a null result', () => {
    const body = { data: null, error: null };
    expect(envelope(z.object({ a: z.string() })).safeParse(body).success).toBe(false);
    expect(envelope(z.object({ a: z.string() }).nullable(), { allowNullData: true }).safeParse(body).success).toBe(true);
  });
});

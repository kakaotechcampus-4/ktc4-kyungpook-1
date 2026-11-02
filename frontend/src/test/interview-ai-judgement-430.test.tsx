import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { Card as CardSchema, InterviewTurn as InterviewTurnSchema, type Card, type InterviewTurn } from '@/api/schemas';
import { InterviewPage } from '@/features/cards/InterviewPage';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
let card: Card;
let template: InterviewTurn;
beforeEach(() => {
  resetDb();
  card = handle('GET', '/cards/card_01', new URLSearchParams(), {}, true).data as Card;
  card = { ...card, version: { ...card.version, task: null }, interviewEndReason: null };
  template = handle('POST', '/cards/card_01/interview', new URLSearchParams(), { field: 'T' }, true).data as InterviewTurn;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
function mount(turns: InterviewTurn[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  client.setQueryData(keys.card(card.id), card);
  client.setQueryData(keys.interview(card.id), turns);
  const router = createMemoryRouter([{ path: '/cards/:cardId/interview', element: <InterviewPage /> }], { initialEntries: [`/cards/${card.id}/interview`] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
}
const openTurn = (reason: string | null): InterviewTurn => ({ ...template, field: 'T', turnNo: 1, askedBy: 'FOLLOW_UP', question: 'T question', answer: null, outcome: null, reason });
async function answerOpenTurn() {
  fireEvent.change(await screen.findByRole('textbox', { name: '답변' }), { target: { value: 'T answer' } });
  fireEvent.click(screen.getByRole('button', { name: '답변 저장' }));
}

describe('#430 되묻기에서 AI 판단을 보여 주기', () => {
  it('AI 가 고른 질문 위에 AI 판단 이유를 보여 주고, 사용자가 칸을 골라 받은 질문에는 보이지 않는다', async () => {
    vi.spyOn(endpoints, 'interviewTurns').mockResolvedValue([openTurn('앞 답에 결과 수치가 없어 R 을 다시 물어요')]);
    mount([openTurn('앞 답에 결과 수치가 없어 R 을 다시 물어요')]);
    expect(await screen.findByText('앞 답에 결과 수치가 없어 R 을 다시 물어요')).toBeInTheDocument();
    expect(screen.getByText('AI 판단')).toBeInTheDocument();
    cleanup();

    vi.spyOn(endpoints, 'interviewTurns').mockResolvedValue([openTurn(null)]);
    mount([openTurn(null)]);
    await screen.findByRole('heading', { name: /T question/ });
    expect(screen.queryByText('AI 판단')).not.toBeInTheDocument();
  });

  it('AI 가 되묻기를 끝내면 카드를 다시 읽어 끝낸 이유를 보여 준다', async () => {
    let turns = [openTurn(null)];
    vi.spyOn(endpoints, 'interviewTurns').mockImplementation(async () => turns);
    vi.spyOn(endpoints, 'answerInterview').mockImplementation(async (_id, no, body) => {
      turns = turns.map((turn) => turn.turnNo === no ? { ...turn, answer: body as InterviewTurn['answer'] } : turn);
      return card;
    });
    const ask = vi.spyOn(endpoints, 'askInterview').mockResolvedValue(null);
    vi.spyOn(endpoints, 'card').mockResolvedValue({ ...card, interviewEndReason: '네 칸 모두 근거가 있어 질문을 마칠게요' });
    mount(turns);
    await answerOpenTurn();

    expect(await screen.findByText('네 칸 모두 근거가 있어 질문을 마칠게요')).toBeInTheDocument();
    expect(screen.getByText('AI 판단')).toBeInTheDocument();
    expect(screen.getByText('AI 가 더 물을 것이 없다고 판단했어요')).toBeInTheDocument();
    expect(ask).toHaveBeenCalledWith(card.id, undefined);
  });

  it('답으로 카드가 확정되면 다음 질문을 요청하지 않고 확정 안내와 카드 보기를 보인다', async () => {
    let turns = [openTurn(null)];
    vi.spyOn(endpoints, 'interviewTurns').mockImplementation(async () => turns);
    vi.spyOn(endpoints, 'answerInterview').mockImplementation(async (_id, no, body) => {
      turns = turns.map((turn) => turn.turnNo === no ? { ...turn, answer: body as InterviewTurn['answer'] } : turn);
      return { ...card, status: 'CONFIRMED', confirmedAt: '2026-11-01T00:00:00Z' };
    });
    const ask = vi.spyOn(endpoints, 'askInterview');
    mount(turns);
    await answerOpenTurn();

    expect(await screen.findByText('네 칸이 모두 차서 카드를 확정했어요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '카드 보기' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '카드 확인하고 확정하기' })).not.toBeInTheDocument();
    expect(ask).not.toHaveBeenCalled();
  });

  it('목 서버도 AI 가 고른 질문에 이유를 싣고, 끝낼 때 이유를 남기며 네 칸이 다 찼으면 확정한다', () => {
    const stored = db.cards.find((c) => c.id === 'card_01')!;
    Object.assign(stored.versions[stored.versions.length - 1], { situation: '상황', task: '과제', action: '행동', result: null });
    stored.lowConfidenceFields = [];
    expect(InterviewTurnSchema.parse(template).reason).toBeNull();

    const asked = InterviewTurnSchema.parse(handle('POST', '/cards/card_01/interview', new URLSearchParams(), {}, true).data);
    expect(asked.field).toBe('R');
    expect(asked.reason).toBe('결과 칸이 비어 있어 먼저 물어볼게요');
    // 답으로 네 칸이 차면 답에서 바로 확정되므로, AI 가 끝내는 경우는 질문 없이 칸이 채워진 초안으로 본다
    stored.versions[stored.versions.length - 1].result = '응답 시간을 절반으로 줄였다';

    expect(handle('POST', '/cards/card_01/interview', new URLSearchParams(), {}, true).data).toBeNull();
    const ended = CardSchema.parse(handle('GET', '/cards/card_01', new URLSearchParams(), {}, true).data);
    expect(ended.interviewEndReason).toBe('더 물을 빈 칸이 없어 질문을 마칠게요');
    expect(ended.status).toBe('CONFIRMED');
  });
});

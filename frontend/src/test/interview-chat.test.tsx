import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import type { Card, InterviewTurn } from '@/api/schemas';
import { InterviewPage } from '@/features/cards/InterviewPage';
import { answeredBy, appliedReply } from './replyResult';
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

const turn = (over: Partial<InterviewTurn>): InterviewTurn => ({ ...template, maxTurns: 8, answer: null, outcome: null, options: [], ...over });
const thread = () => document.querySelector('.iv__thread') as HTMLElement;

function mount(turns: InterviewTurn[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  client.setQueryData(keys.card(card.id), card);
  client.setQueryData(keys.interview(card.id), turns);
  const router = createMemoryRouter([{ path: '/cards/:cardId/interview', element: <InterviewPage /> }], { initialEntries: [`/cards/${card.id}/interview`] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
}

/** 저장하면 그 질문이 답한 질문이 되고, 다음 질문은 없다고(null) 답하는 서버. save 로 저장이 끝나는 때를 늦출 수 있다 */
function serve(turns: InterviewTurn[], save: () => Promise<Card> = async () => card) {
  let list = turns;
  vi.spyOn(endpoints, 'interviewTurns').mockImplementation(async () => list);
  vi.spyOn(endpoints, 'askInterview').mockResolvedValue(null);
  return vi.spyOn(endpoints, 'replyInterview').mockImplementation(async (_id, no, body) => {
    list = list.map((t) => (t.turnNo === no ? answeredBy(t, body) : t));
    return appliedReply(await save());
  });
}

describe('되묻기 대화 모양', () => {
  it('지난 질문과 답은 말풍선으로 차례대로 보이고, 넘긴 질문은 한 줄로 남는다', () => {
    mount([
      turn({ turnNo: 1, field: 'S', question: 'S 질문', answer: { text: 'S 답', source: 'USER_STATED' }, outcome: 'ANSWERED' }),
      turn({ turnNo: 2, field: 'A', question: 'A 질문', outcome: 'SKIPPED' }),
      turn({ turnNo: 3, field: 'T', question: 'T 질문' }),
    ]);

    const lines = Array.from(thread().querySelectorAll('.chat-bub, .chat-line, .iv__saved'));
    expect(lines.map((line) => line.textContent)).toEqual(['상황 칸S 질문', 'S 답', '내 답변, 저장됨', '행동 칸A 질문', '넘긴 질문', '과제 칸Q3. T 질문']);
    expect(Array.from(thread().querySelectorAll('.chat-bub')).map((bubble) => bubble.classList.contains('chat-bub--me'))).toEqual([false, true, false, false]);
    expect(thread().querySelectorAll('.chat-from')).toHaveLength(3);
  });

  it('보낸 답은 저장을 기다리는 동안 바로 내 말풍선으로 보이고 입력 칸이 잠긴다', async () => {
    let finish!: () => void;
    const turns = [turn({ turnNo: 1, field: 'T', question: 'T 질문' })];
    serve(turns, () => new Promise<Card>((resolve) => { finish = () => resolve(card); }));
    mount(turns);

    fireEvent.change(screen.getByRole('textbox', { name: '답변' }), { target: { value: 'T 답' } });
    fireEvent.click(screen.getByRole('button', { name: '답 보내기' }));

    await waitFor(() => expect(thread().querySelector('.chat-bub--me')).toHaveTextContent('T 답'));
    expect(screen.getByRole('textbox', { name: '답변' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '답 보내기' })).toBeDisabled();
    expect(screen.getByText('답을 읽고 카드 문장으로 쓰는 중이에요')).toBeInTheDocument();
    await act(async () => { finish(); });
    await screen.findByText('추가 질문을 마쳤어요');
  });

  it('Enter 로 답을 저장하고, 보기를 누르면 보내지 않고 입력 칸에 채운다', async () => {
    const turns = [turn({ turnNo: 1, field: 'T', question: 'T 질문', options: ['첫째 보기', '둘째 보기'] })];
    const save = serve(turns);
    mount(turns);

    fireEvent.click(screen.getByRole('button', { name: '첫째 보기' }));
    expect(save).not.toHaveBeenCalled();
    expect(screen.getByRole('textbox', { name: '답변' })).toHaveValue('첫째 보기');

    fireEvent.keyDown(screen.getByRole('textbox', { name: '답변' }), { key: 'Enter' });
    await waitFor(() => expect(save).toHaveBeenCalledWith(card.id, 1, { text: '첫째 보기', source: 'USER_SELECTED' }));
    await screen.findByText('추가 질문을 마쳤어요');
  });
});

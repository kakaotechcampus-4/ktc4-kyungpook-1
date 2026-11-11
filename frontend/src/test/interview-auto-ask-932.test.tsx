import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { Card, type InterviewTurn } from '@/api/schemas';
import { CardPage } from '@/features/cards/CardPage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';
import { connectMockServer } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

beforeEach(() => { resetDb(); connectMockServer(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const load = (id: string) => Card.parse(handle('GET', `/cards/${id}`, new URLSearchParams(), {}, true).data);
const template = () => handle('POST', '/cards/card_01/interview', new URLSearchParams(), { field: 'R' }, true).data as InterviewTurn;

function mount(card: Card, turns: InterviewTurn[]) {
  vi.spyOn(endpoints, 'interviewTurns').mockResolvedValue(turns);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(keys.card(card.id), card);
  const router = createMemoryRouter([
    { path: '/cards/:cardId', element: <CardPage /> },
    { path: '/cards/:cardId/interview', element: <p>되묻기 화면</p> },
  ], { initialEntries: [`/cards/${card.id}`] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  return router;
}

describe('열린 질문이 있으면 카드 화면이 질문을 보여 주고 답하기로 이어진다', () => {
  it('F1 확정 카드의 알림 줄에 질문 글이 보이고 답하기가 그 칸의 되묻기로 간다', async () => {
    const original = load('card_03');
    const card = { ...original, status: 'CONFIRMED' as const, confirmedAt: '2026-11-01T00:00:00Z', version: { ...original.version, task: null } };
    const open = { ...template(), field: 'T' as const, turnNo: 1, question: '이 일을 맡게 된 계기가 무엇이었나요?', answer: null, outcome: null };
    const router = mount(card, [open]);

    expect(await screen.findByText(/과제 칸이 비어 있어요\. 이 일을 맡게 된 계기가 무엇이었나요\?/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '질문 받기' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '답하기' }));

    expect(await screen.findByText('되묻기 화면')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/cards/card_03/interview');
    expect(router.state.location.search).toBe('?field=T');
  });

  it('F1 초안 카드의 아래 줄에도 질문 글과 답하기가 보인다', async () => {
    const card = load('card_01');
    const open = { ...template(), field: 'R' as const, turnNo: 1, question: '결과를 숫자로 말해 주실 수 있나요?', answer: null, outcome: null };
    const router = mount(card, [open]);

    expect(await screen.findByText('결과를 숫자로 말해 주실 수 있나요?')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '답변하기' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '답하기' }));

    expect(await screen.findByText('되묻기 화면')).toBeInTheDocument();
    expect(router.state.location.search).toBe('?field=R');
  });

  it('F2 열린 질문이 없으면 확정 카드는 질문 받기, 초안 카드는 답변하기 그대로다', async () => {
    const original = load('card_03');
    const answered = { ...template(), field: 'T' as const, turnNo: 1, answer: { text: '답', source: 'USER_STATED' as const }, outcome: 'ANSWERED' as const };
    mount({ ...original, status: 'CONFIRMED', confirmedAt: '2026-11-01T00:00:00Z', version: { ...original.version, task: null } }, [answered]);
    expect(await screen.findByRole('button', { name: '질문 받기' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '답하기' })).not.toBeInTheDocument();
    cleanup();

    const draft = load('card_01');
    mount({ ...draft, version: { ...draft.version, result: null } }, []);
    expect(await screen.findByRole('button', { name: '답변하기' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '답하기' })).not.toBeInTheDocument();
  });
});

import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { keys } from '@/api/keys';
import type { Card, InterviewTurn } from '@/api/schemas';
import { InterviewPage } from '@/features/cards/InterviewPage';
import { starFieldSpoken } from '@/lib/labels';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

/** #983: 열린 질문에 예시 답이 있으면 입력칸 안내가 그 예시로 바뀌고, 없으면 칸 공통 예시를 보인다 */
vi.mock('@/lib/track', () => ({ track: vi.fn() }));
let card: Card;
let template: InterviewTurn;
beforeEach(() => {
  resetDb();
  card = handle('GET', '/cards/card_01', new URLSearchParams(), {}, true).data as Card;
  template = handle('POST', '/cards/card_01/interview', new URLSearchParams(), { field: 'T' }, true).data as InterviewTurn;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

function mount(turn: InterviewTurn) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  client.setQueryData(keys.card(card.id), card);
  client.setQueryData(keys.interview(card.id), [turn]);
  const router = createMemoryRouter([{ path: '/cards/:cardId/interview', element: <InterviewPage /> }], { initialEntries: [`/cards/${card.id}/interview`] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
}
const composer = () => screen.getByRole('textbox', { name: '답변' });

describe('질문별 예시 답', () => {
  it('열린 질문에 예시 답이 있으면 입력칸 안내에 그 예시를 쓴다', () => {
    mount({ ...template, answer: null, outcome: null, exampleAnswer: '예: 배포 시간을 줄여야 했어요' });

    expect(composer()).toHaveAttribute('placeholder', '편하게 말하듯 적어 주세요. 예: 배포 시간을 줄여야 했어요');
  });

  it('예시 답이 없으면 칸 공통 예시로 돌아간다', () => {
    mount({ ...template, answer: null, outcome: null, exampleAnswer: null });

    expect(composer()).toHaveAttribute('placeholder', starFieldSpoken[template.field]);
  });
});

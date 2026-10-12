import { cleanup, render, screen, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { Card } from '@/api/schemas';
import { endpoints } from '@/api/endpoints';
import { EditMode, MaskMode } from '@/features/cards/CardModes';
import { CardPage } from '@/features/cards/CardPage';
import { keys } from '@/api/keys';
import { seedCards } from '@/mock/fixtures';

const raw = seedCards[0];
const card = { ...raw, version: raw.versions[0] } as Card;
vi.mock('@/lib/track', () => ({ track: vi.fn() }));
beforeEach(() => { vi.stubEnv('VITE_API_MOCK', 'false'); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllEnvs(); });
function mount(element: React.ReactNode, client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } })) {
  const router = createMemoryRouter([{ path: '/cards/:cardId', element }], { initialEntries: [`/cards/${card.id}`] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
}
it('every edit STAR slot accepts the shared 1000 character limit', () => {
  mount(<EditMode card={card} onDone={vi.fn()} />);
  for (const field of ['상황 (Situation)', '과제 (Task)', '행동 (Action)', '결과 (Result)']) expect(screen.getByLabelText(field)).toHaveAttribute('maxlength', '1000');
});
it('mask rule creation stops at 20 and both texts have 100 character limits', () => {
  mount(<MaskMode card={{ ...card, maskRules: Array.from({ length: 20 }, (_, index) => ({ from: `원문${index}`, to: '치환' })) }} onDone={vi.fn()} />);
  for (const input of screen.getAllByRole('textbox')) expect(input).toHaveAttribute('maxlength', '100');
  for (const label of ['+ 규칙 추가', '+ 팀원 익명화', '+ 회사명 가리기']) {
    const button = screen.getByRole('button', { name: label }); expect(button).toBeDisabled(); fireEvent.click(button);
  }
  expect(screen.getAllByLabelText('원문')).toHaveLength(20);
});
it('real API mode never renders either timeout regeneration action', () => {
  const partial = { ...card, generation: { jobId: 'partial-job', partial: true }, droppedFields: [{ field: 'R', reason: 'TIMEOUT' }], version: { ...card.version, result: null } } as Card;
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } }); client.setQueryData(keys.card(card.id), partial);
  vi.spyOn(endpoints, 'card').mockResolvedValue(partial);
  mount(<CardPage />, client);
  expect(screen.queryAllByRole('button', { name: /다시 생성/ })).toHaveLength(0);
  expect(screen.getByRole('button', { name: '결과 답변하기' })).toBeInTheDocument();
});

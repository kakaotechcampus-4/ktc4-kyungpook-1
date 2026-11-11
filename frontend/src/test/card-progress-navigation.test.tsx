import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it } from 'vitest';
import { CardSummary } from '@/api/schemas';
import { CardNavigation } from '@/features/cards/CardNavigation';
import { keys } from '@/api/keys';
afterEach(cleanup);
const summary = (id: string, updatedAt: string) =>
  CardSummary.parse({
    id,
    title: id,
    kind: 'TECH',
    status: 'DRAFT',
    versionNo: 1,
    star: { S: 'FILLED', T: 'EMPTY', A: 'FILLED', R: 'EMPTY' },
    updatedAt,
  });
function mount(id: string, order?: string[]) {
  const client = new QueryClient({
    defaultOptions: { queries: { staleTime: Infinity, retry: false } },
  });
  client.setQueryData(keys.cards, [
    summary('old', '2026-10-18'),
    summary('new', '2026-10-20'),
    summary('middle', '2026-10-19'),
  ]);
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter
        initialEntries={[
          {
            pathname: `/cards/${id}`,
            state: order ? { cardOrder: order } : null,
          },
        ]}
      >
        <CardNavigation cardId={id} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
it('direct card entry uses the same newest-first order as the card list', () => {
  mount('middle');
  expect(screen.getByRole('link', { name: '이전 카드' })).toHaveAttribute(
    'href',
    '/cards/new',
  );
  expect(screen.getByRole('link', { name: '다음 카드' })).toHaveAttribute(
    'href',
    '/cards/old',
  );
});
it('preserves the originating list order, excluding removed entries', () => {
  mount('middle', ['old', 'deleted', 'middle', 'new']);
  expect(screen.getByRole('link', { name: '이전 카드' })).toHaveAttribute(
    'href',
    '/cards/old',
  );
  expect(screen.getByRole('link', { name: '다음 카드' })).toHaveAttribute(
    'href',
    '/cards/new',
  );
});
it('first/last boundaries disable the unavailable action', () => {
  mount('new');
  expect(screen.getByRole('button', { name: '이전 카드' })).toBeDisabled();
  cleanup();
  mount('old');
  expect(screen.getByRole('button', { name: '다음 카드' })).toBeDisabled();
});
it('a missing current card never sends the user to an arbitrary neighbor', () => {
  mount('absent');
  expect(screen.getByRole('button', { name: '이전 카드' })).toBeDisabled();
  expect(screen.getByRole('button', { name: '다음 카드' })).toBeDisabled();
});

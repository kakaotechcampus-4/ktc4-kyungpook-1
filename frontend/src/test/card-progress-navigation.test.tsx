import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it } from 'vitest';
import { Job, CardSummary } from '@/api/schemas';
import { DraftProgress } from '@/features/cards/DraftProgress';
import { CardNavigation } from '@/features/cards/CardNavigation';
import { keys } from '@/api/keys';
afterEach(cleanup);
const job = (total: number | null) =>
  Job.parse({
    jobId: 'j',
    type: 'DRAFT',
    state: 'RUNNING',
    partial: false,
    steps: [
      { key: 'COMMITS', state: 'RUNNING', done: 20, total: 40 },
      { key: 'PR_REVIEW', state: 'SKIPPED', done: 0, total: 0 },
      { key: 'COMPRESS', state: 'RUNNING', done: 10, total: 40 },
      { key: 'REASON', state: 'RUNNING', done: 10, total },
    ],
    errorCode: null,
    retryable: null,
    retryAfterSec: null,
    startedAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    finishedAt: null,
    result: null,
    pollAfterMs: 2000,
  });
it('draft progress counts finished cards using REASON rather than analysis stages', () => {
  render(<DraftProgress job={job(40)} checkedAt={Date.now()} />);
  expect(screen.getByText('카드 초안 10 / 40장')).toBeInTheDocument();
  expect(screen.getByRole('progressbar')).toHaveAttribute(
    'aria-valuenow',
    '25',
  );
  expect(screen.queryByText('커밋 읽기')).not.toBeInTheDocument();
  expect(screen.queryByText(/남았어요/)).not.toBeInTheDocument();
});
it('unknown total does not fabricate a percentage or duration', () => {
  render(<DraftProgress job={job(null)} checkedAt={Date.now()} />);
  expect(
    screen.getByText('카드 초안 10장 완료 · 전체 확인 중'),
  ).toBeInTheDocument();
  expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
});
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

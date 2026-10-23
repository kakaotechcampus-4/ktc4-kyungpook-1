import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { CardGridItem } from '@/features/home/HomePage';
import { CardSummary } from '@/api/schemas';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';
import { endpoints } from '@/api/endpoints';
import { useDeleteCard } from '@/api/queries';
import { ApiError } from '@/api/client';
import { keys } from '@/api/keys';
import { matchKeys } from '@/api/matching';

const request = (method: string, path: string) => handle(method, path, new URLSearchParams(), {}, true);
beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

it('deleting a candidate card releases its candidate and removes its interview history', () => {
  const card = db.cards.find(card => card.candidate)!;
  const candidate = db.candidates.find(candidate => candidate.id === card.candidate.id)!;
  candidate.status = 'USED';
  const response = request('DELETE', `/cards/${card.id}`);
  expect(response).toMatchObject({ status: 200, data: { ok: true } });
  expect(candidate.status).toBe('NEW');
  expect(request('GET', `/cards/${card.id}`).status).toBe(404);
  expect(db.interview[card.id]).toBeUndefined();
});

for (const missing of [false, true]) it(`deletion reconciles card and related caches (already missing: ${missing})`, async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  client.setQueryData(keys.cards, [{ id: 'remove' }, { id: 'keep' }]);
  client.setQueryData(keys.card('remove'), { id: 'remove' });
  client.setQueryData(keys.versions('remove'), []);
  client.setQueryData(keys.repo('repo'), { id: 'repo' });
  client.setQueryData(keys.candidates('repo'), {});
  client.setQueryData(matchKeys.all, { old: 'match' });
  const send = vi.spyOn(endpoints, 'deleteCard');
  if (missing) send.mockRejectedValue(new ApiError('NOT_FOUND', '', 404));
  else send.mockResolvedValue({ ok: true });
  function Delete() {
    const mutation = useDeleteCard('remove');
    return <button onClick={() => mutation.mutate()}>delete</button>;
  }
  render(<QueryClientProvider client={client}><Delete /></QueryClientProvider>);
  fireEvent.click(screen.getByText('delete'));
  await waitFor(() => expect(client.getQueryData(keys.cards)).toEqual([{ id: 'keep' }]));
  expect(client.getQueryData(keys.card('remove'))).toBeUndefined();
  expect(client.getQueryData(keys.versions('remove'))).toBeUndefined();
  expect(client.getQueryState(keys.repo('repo'))?.isInvalidated).toBe(true);
  expect(client.getQueryState(keys.candidates('repo'))?.isInvalidated).toBe(true);
  expect(client.getQueryData(matchKeys.all)).toBeUndefined();
  expect(send).toHaveBeenCalledTimes(1);
});

it('a late repository read cannot clear invalidation with pre-delete data', async () => {
  const client = new QueryClient();
  client.setQueryData(keys.cards, [{ id: 'remove' }]);
  client.setQueryData(keys.repos, { count: 0 });
  let resolve!: (value: { count: number }) => void;
  const reading = client.fetchQuery({ queryKey: keys.repos, staleTime: 0,
    queryFn: () => new Promise<{ count: number }>(done => { resolve = done; }) }).catch(() => undefined);
  vi.spyOn(endpoints, 'deleteCard').mockResolvedValue({ ok: true });
  function Delete() {
    const mutation = useDeleteCard('remove');
    return <button onClick={() => mutation.mutate()}>delete</button>;
  }
  render(<QueryClientProvider client={client}><Delete /></QueryClientProvider>);
  fireEvent.click(screen.getByText('delete'));
  await waitFor(() => expect(client.getQueryData(keys.cards)).toEqual([]));
  resolve({ count: 1 });
  await reading;
  expect(client.getQueryData(keys.repos)).toEqual({ count: 0 });
  expect(client.getQueryState(keys.repos)?.isInvalidated).toBe(true);
});

it('an active draft job prevents deletion without removing the card', () => {
  const card = db.cards[0];
  card.generation = { jobId: 'active-delete-test', partial: false };
  db.jobs.set('active-delete-test', { state: 'RUNNING', type: 'DRAFT' });
  expect(request('DELETE', `/cards/${card.id}`)).toMatchObject({ status: 409, error: { code: 'INVALID_STATE' } });
  expect(db.cards).toContain(card);
});

it('a card delete action opens confirmation without following the card link and cancel keeps it', () => {
  const summary = CardSummary.parse((request('GET', '/cards').data as unknown[])[0]);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const router = createMemoryRouter([{ path: '/', element: <CardGridItem c={summary} /> }]);
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  fireEvent.click(screen.getByRole('button', { name: `${summary.title} 삭제` }));
  expect(screen.getByRole('dialog', { name: '카드를 삭제할까요?' })).toBeInTheDocument();
  expect(router.state.location.pathname).toBe('/');
  fireEvent.click(screen.getByRole('button', { name: '취소' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(db.cards.some(card => card.id === summary.id)).toBe(true);
});

it('pending deletion cannot be submitted twice or dismissed with Escape', async () => {
  const summary = CardSummary.parse((request('GET', '/cards').data as unknown[])[0]);
  let finish!: (value: { ok: true }) => void;
  const send = vi.spyOn(endpoints, 'deleteCard').mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  const client = new QueryClient();
  const router = createMemoryRouter([{ path: '/', element: <CardGridItem c={summary} /> }]);
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  fireEvent.click(screen.getByRole('button', { name: `${summary.title} 삭제` }));
  const remove = screen.getByRole('button', { name: '삭제' });
  fireEvent.click(remove);
  fireEvent.click(remove);
  await waitFor(() => expect(send).toHaveBeenCalledTimes(1));
  fireEvent.keyDown(document, { key: 'Escape' });
  expect(screen.getByRole('dialog')).toBeInTheDocument();
  finish({ ok: true });
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
});

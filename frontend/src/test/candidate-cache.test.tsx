import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, expect, it, vi } from 'vitest';
import { useCandidates, usePatchCandidate } from '@/api/queries';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { CandidateBoard, Candidate } from '@/api/schemas';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it('publishes the patch response immediately without locking the board until a slow GET completes', async () => {
  resetDb();
  const board = CandidateBoard.parse(handle('GET', '/repos/r_auth/candidates', new URLSearchParams(), {}, true).data);
  const target = board.candidates.find((item) => item.status === 'NEW')!;
  const updated = Candidate.parse({ ...target, status: 'EXCLUDED' });
  vi.spyOn(endpoints, 'patchCandidate').mockResolvedValue(updated);
  const read = vi.spyOn(endpoints, 'candidates').mockImplementation(() => new Promise(() => {}));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(keys.candidates('r_auth'), board);
  const { result } = renderHook(() => ({ read: useCandidates('r_auth'), patch: usePatchCandidate('r_auth') }), {
    wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
  });
  act(() => result.current.patch.mutate({ id: target.id, status: 'EXCLUDED' }));
  await waitFor(() => expect(read).toHaveBeenCalled());
  await waitFor(() => expect(result.current.patch.isPending).toBe(false), { timeout: 1200 });
  expect(result.current.read.data?.candidates.find((item) => item.id === target.id)?.status).toBe('EXCLUDED');
  client.clear();
});

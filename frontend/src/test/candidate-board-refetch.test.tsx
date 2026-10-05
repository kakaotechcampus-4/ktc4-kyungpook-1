import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { CandidateBoard, RepoDetail } from '@/api/schemas';
import { CandidateBoardPage } from '@/features/candidates/CandidateBoardPage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it('keeps showing the board when only a background refetch fails', async () => {
  resetDb();
  const board = CandidateBoard.parse(handle('GET', '/repos/r_auth/candidates', new URLSearchParams(), {}, true).data);
  const repo = RepoDetail.parse(handle('GET', '/repos/r_auth', new URLSearchParams(), {}, true).data);
  const target = board.candidates.find((item) => item.status === 'NEW')!;
  const read = vi.spyOn(endpoints, 'candidates').mockRejectedValue(new Error('offline'));
  vi.spyOn(endpoints, 'repo').mockResolvedValue(repo);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(keys.candidates('r_auth'), board);
  client.setQueryData(keys.repo('r_auth'), repo);
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/repos/r_auth/candidates']}>
    <Routes><Route path="/repos/:repoId/candidates" element={<CandidateBoardPage />} /></Routes>
  </MemoryRouter></QueryClientProvider>);
  await waitFor(() => expect(read).toHaveBeenCalled());
  await waitFor(() => expect(client.getQueryState(keys.candidates('r_auth'))?.status).toBe('error'));
  expect(client.getQueryData(keys.candidates('r_auth'))).toBeDefined();
  expect(screen.getByText(target.title)).toBeInTheDocument();
  client.clear();
});

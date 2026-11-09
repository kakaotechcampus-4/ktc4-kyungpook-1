// 11/3 배포 사이트 점검(Codex)에서 재현된 것: 확정 카드 인터뷰 진입만으로 질문 POST, 저장소 목록이 GitHub 동기화를 기다려 늦게 뜸
import { cleanup, render, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { useRepos } from '@/api/queries';
import type { Card, RepoSummary } from '@/api/schemas';
import { InterviewPage } from '@/features/cards/InterviewPage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
beforeEach(() => resetDb());
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
const client = () => new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });

describe('interview auto question', () => {
  function mount(card: Card) {
    const qc = client();
    qc.setQueryData(keys.card(card.id), card);
    qc.setQueryData(keys.interview(card.id), []);
    const router = createMemoryRouter([{ path: '/cards/:cardId/interview', element: <InterviewPage /> }], { initialEntries: [`/cards/${card.id}/interview`] });
    render(<QueryClientProvider client={qc}><RouterProvider router={router} /></QueryClientProvider>);
  }
  const base = () => handle('GET', '/cards/card_01', new URLSearchParams(), {}, true).data as Card;

  it('asks the first question for a confirmed card too', async () => {
    vi.spyOn(endpoints, 'interviewTurns').mockResolvedValue([]);
    const ask = vi.spyOn(endpoints, 'askInterview').mockResolvedValue(null);
    mount({ ...base(), status: 'CONFIRMED' });
    await waitFor(() => expect(ask).toHaveBeenCalledTimes(1));
  });

  it('still asks the first question for a draft card', async () => {
    vi.spyOn(endpoints, 'interviewTurns').mockResolvedValue([]);
    const ask = vi.spyOn(endpoints, 'askInterview').mockResolvedValue(null);
    mount({ ...base(), status: 'DRAFT' });
    await waitFor(() => expect(ask).toHaveBeenCalledTimes(1));
  });
});

describe('repository list', () => {
  const wrapper = (qc: QueryClient) => ({ children }: { children: ReactNode }) => <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  const repos = () => handle('GET', '/repos', new URLSearchParams(), {}, true).data as RepoSummary[];

  it('shows the saved list while the GitHub-synced list is still loading, then swaps in the fresh one', async () => {
    let finish!: (v: RepoSummary[]) => void;
    vi.spyOn(endpoints, 'repos').mockImplementation(() => new Promise((r) => { finish = r; }));
    vi.spyOn(endpoints, 'savedRepos').mockResolvedValue(repos());
    const { result } = renderHook(() => useRepos(), { wrapper: wrapper(client()) });
    await waitFor(() => expect(result.current.data).toHaveLength(repos().length));
    expect(result.current.isPlaceholderData).toBe(true);
    finish(repos().slice(0, 1));
    await waitFor(() => expect(result.current.isPlaceholderData).toBe(false));
    expect(result.current.data).toHaveLength(1);
  });

  it('waits for the fresh list as before when nothing was saved yet or the saved list fails', async () => {
    vi.spyOn(endpoints, 'repos').mockImplementation(() => new Promise(() => {}));
    vi.spyOn(endpoints, 'savedRepos').mockRejectedValue(new Error('offline'));
    const { result } = renderHook(() => useRepos(), { wrapper: wrapper(client()) });
    await new Promise((r) => setTimeout(r, 30));
    expect(result.current.isPending).toBe(true);
    expect(result.current.data).toBeUndefined();
  });
});

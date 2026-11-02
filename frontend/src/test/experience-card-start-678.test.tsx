import { StrictMode } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { RepoSummary, type Card } from '@/api/schemas';
import { endpoints } from '@/api/endpoints';
import { NewCardPage } from '@/features/cards/NewCardPage';
import { seedRepos } from '@/mock/fixtures';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
const repo = seedRepos[0];
const card = (overrides: Partial<Card> = {}): Card => ({
  id: '10000000-0000-4000-8000-000000000678', kind: 'QUALITATIVE', status: 'DRAFT', title: '동아리 축제 예약 웹', period: '2024.05',
  repo: { id: repo.id, owner: repo.owner, name: repo.name }, candidate: null,
  version: { versionNo: 1, source: 'USER_EDIT', createdAt: '2026-11-02T00:00:00Z', situation: null, task: null, action: null, result: null },
  evidence: [], lowConfidenceFields: [], droppedFields: [], maskRules: [], generation: null, interviewTurns: 0, confirmedAt: null, createdAt: '2026-11-02T00:00:00Z', ...overrides,
});
beforeEach(() => { vi.stubEnv('VITE_API_MOCK', 'false'); vi.spyOn(endpoints, 'repos').mockResolvedValue(seedRepos.map(r => RepoSummary.parse(r))); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllEnvs(); });

function mount(entry = '/new') {
  const router = createMemoryRouter([
    { path: '/', element: <p>앞 화면</p> },
    { path: '/new', element: <NewCardPage /> },
    { path: '/cards/:id', element: <p>카드 화면</p> },
    { path: '/cards/:id/interview', element: <p>되묻기 화면</p> },
  ], { initialEntries: ['/', entry], initialIndex: 1 });
  render(<StrictMode><QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}><RouterProvider router={router} /></QueryClientProvider></StrictMode>);
  return router;
}
const change = (name: string, value: string) => fireEvent.change(screen.getByLabelText(name), { target: { value } });

describe('#678 경험 카드 만들기 첫 화면', () => {
  it('제목·기간·레포·활동만 받고, 제목을 적어야 질문 받으며 쓰기를 누를 수 있다', () => {
    mount();
    expect(screen.getByRole('heading', { level: 1, name: '경험 카드 만들기' })).toBeInTheDocument();
    expect(screen.queryByLabelText('상황 (Situation)')).not.toBeInTheDocument();
    const start = screen.getByRole('button', { name: '질문 받으며 쓰기' });
    expect(start).toBeDisabled();
    change('카드 제목', '동아리 축제 예약 웹');
    expect(start).toBeEnabled();
  });

  it('질문 받으며 쓰기는 고른 레포로 카드를 만들고 되묻기로 넘어가며, 뒤로 가면 이 화면이 아니라 앞 화면이다', async () => {
    const create = vi.spyOn(endpoints, 'createManualDraft').mockResolvedValue(card());
    const router = mount(`/new?repo=${encodeURIComponent(repo.id)}`);
    change('카드 제목', '동아리 축제 예약 웹'); change('기간', '2024.05');
    fireEvent.click(screen.getByRole('button', { name: '질문 받으며 쓰기' }));

    await screen.findByText('되묻기 화면');
    expect(create).toHaveBeenCalledTimes(1);
    expect(create.mock.calls[0][0]).toEqual({ title: '동아리 축제 예약 웹', period: '2024.05', repoId: repo.id });
    expect(router.state.location.pathname).toBe(`/cards/${card().id}/interview`);
    await act(async () => { await router.navigate(-1); });
    expect(router.state.location.pathname).toBe('/');
  });

  it('네 칸 직접 쓰기는 적은 제목을 그대로 두고 네 칸 양식으로 바꾸며, 아직 카드를 만들지 않는다', () => {
    const create = vi.spyOn(endpoints, 'createManualDraft');
    mount();
    change('카드 제목', '동아리 축제 예약 웹');
    fireEvent.click(screen.getByRole('button', { name: '네 칸 직접 쓰기' }));

    expect(screen.getByRole('heading', { level: 1, name: '직접 작성' })).toBeInTheDocument();
    expect(screen.getByLabelText('카드 제목')).toHaveValue('동아리 축제 예약 웹');
    expect(screen.getByLabelText('상황 (Situation)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '임시 저장 시작' })).toBeEnabled();
    expect(create).not.toHaveBeenCalled();
  });

  it('카드를 만들지 못하면 첫 화면에 남고 적은 제목도 그대로다', async () => {
    const create = vi.spyOn(endpoints, 'createManualDraft').mockRejectedValue(new Error('network'));
    const router = mount();
    change('카드 제목', '동아리 축제 예약 웹');
    fireEvent.click(screen.getByRole('button', { name: '질문 받으며 쓰기' }));

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getByRole('button', { name: '질문 받으며 쓰기' })).toBeEnabled());
    expect(router.state.location.pathname).toBe('/new');
    expect(screen.getByLabelText('카드 제목')).toHaveValue('동아리 축제 예약 웹');
  });

  it('같은 요청을 다시 보내 이미 확정된 카드가 돌아오면 되묻기가 아니라 카드 화면으로 간다', async () => {
    vi.spyOn(endpoints, 'createManualDraft').mockResolvedValue(card({ status: 'CONFIRMED' }));
    mount();
    change('카드 제목', '동아리 축제 예약 웹');
    fireEvent.click(screen.getByRole('button', { name: '질문 받으며 쓰기' }));

    expect(await screen.findByText('카드 화면')).toBeInTheDocument();
  });
});

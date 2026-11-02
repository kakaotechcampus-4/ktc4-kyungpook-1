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
const card = (overrides: Partial<Card> = {}): Card => ({
  id: '10000000-0000-4000-8000-000000000001', kind: 'QUALITATIVE', status: 'DRAFT', title: '서버 제목', period: '2024.04', repo: null, candidate: null,
  version: { versionNo: 1, source: 'USER_EDIT', createdAt: '2026-10-12T00:00:00Z', situation: null, task: null, action: null, result: null },
  evidence: [], lowConfidenceFields: [], droppedFields: [], maskRules: [], generation: null, interviewTurns: 0, confirmedAt: null, createdAt: '2026-10-12T00:00:00Z', ...overrides,
});
beforeEach(() => { vi.stubEnv('VITE_API_MOCK', 'false'); vi.spyOn(endpoints, 'repos').mockResolvedValue(seedRepos.map(repo => RepoSummary.parse(repo))); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllEnvs(); });
function mount() {
  const router = createMemoryRouter([{ path: '/new', element: <NewCardPage /> }, { path: '/cards/:id', element: <p>기존 카드 화면</p> }, { path: '/', element: <p>홈</p> }], { initialEntries: ['/new'] });
  render(<StrictMode><QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}><RouterProvider router={router} /></QueryClientProvider></StrictMode>);
  return router;
}
const change = (name: string, value: string) => fireEvent.change(screen.getByLabelText(name), { target: { value } });

describe('manual startup contract #183', () => {
  it('drains text typed during creation and the first PATCH without restoring older input', async () => {
    let resolveCreate!: (value: Card) => void;
    let resolvePatch!: (value: Awaited<ReturnType<typeof endpoints.saveDraft>>) => void;
    const returned = card({ version: { ...card().version, task: '서버 과제' } });
    vi.spyOn(endpoints, 'createManualDraft').mockImplementation(() => new Promise(resolve => { resolveCreate = resolve; }));
    const save = vi.spyOn(endpoints, 'saveDraft').mockImplementationOnce(() => new Promise(resolve => { resolvePatch = resolve; }))
      .mockImplementation(async (id, fields) => ({ cardId: id, versionNo: 1, ...fields, savedAt: '2026-10-12T00:00:02Z' }));
    mount(); change('카드 제목', '요청 제목'); change('상황', '첫 입력');
    fireEvent.click(screen.getByRole('button', { name: '작성 시작' }));
    await waitFor(() => expect(resolveCreate).toBeDefined());
    change('상황', '생성 중 입력');
    await act(async () => { resolveCreate(returned); });
    await waitFor(() => expect(save).toHaveBeenCalledTimes(1));
    expect(save.mock.calls[0][1]).toMatchObject({ situation: '생성 중 입력', task: '서버 과제' });
    change('상황', '최신 입력');
    await act(async () => { resolvePatch({ cardId: returned.id, versionNo: 1, ...save.mock.calls[0][1], savedAt: '2026-10-12T00:00:01Z' }); });
    await waitFor(() => expect(save).toHaveBeenCalledTimes(2));
    expect(save.mock.calls[1][1]).toMatchObject({ situation: '최신 입력', task: '서버 과제' });
    expect(screen.getByLabelText('상황')).toHaveValue('최신 입력');
    expect(screen.getByLabelText('카드 제목')).toHaveValue('서버 제목');
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('저장됨'));
  });
  it('reuses one entry UUID after lost response and accepts server metadata without an unsupported PATCH', async () => {
    const repo = seedRepos[0];
    const returned = card({ repo: { id: repo.id, owner: repo.owner, name: repo.name } });
    const create = vi.spyOn(endpoints, 'createManualDraft').mockRejectedValueOnce(new Error('response lost')).mockResolvedValue(returned);
    const metadata = vi.spyOn(endpoints, 'saveMetadata');
    vi.spyOn(endpoints, 'saveDraft').mockImplementation(async (id, fields) => ({ cardId: id, versionNo: 1, ...fields, savedAt: '2026-10-12T00:00:01Z' }));
    mount(); change('카드 제목', '서버 제목'); change('기간', '2024.04'); change('상황', '  입력한 문장\n그대로  ');
    fireEvent.click(screen.getByRole('button', { name: '작성 시작' }));
    await screen.findByRole('button', { name: '다시 저장' });
    change('카드 제목', '재요청에서 바꾼 제목'); change('기간', '2025.02');
    fireEvent.click(screen.getByRole('button', { name: '다시 저장' }));
    await waitFor(() => expect(create).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getByLabelText('카드 제목')).toHaveValue('서버 제목'));
    expect(screen.getByLabelText('기간')).toHaveValue('2024.04');
    expect(screen.getByRole('combobox', { name: '관련 레포' })).toHaveTextContent(`${repo.owner}/${repo.name}`);
    expect(screen.getByLabelText('상황')).toHaveValue('  입력한 문장\n그대로  ');
    const firstKey = create.mock.calls[0][1], replayKey = create.mock.calls[1][1];
    expect(firstKey).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
    expect(replayKey).toBe(firstKey); expect(metadata).not.toHaveBeenCalled();
  });

  it('creates a fresh key for the next writing entry', async () => {
    const create = vi.spyOn(endpoints, 'createManualDraft').mockImplementation(async input => card({ title: input.title, period: input.period ?? null }));
    vi.spyOn(endpoints, 'saveDraft').mockImplementation(async (id, fields) => ({ cardId: id, versionNo: 1, ...fields, savedAt: '2026-10-12T00:00:01Z' }));
    const router = mount(); change('카드 제목', '첫 카드'); fireEvent.click(screen.getByRole('button', { name: '작성 시작' }));
    fireEvent.click(await screen.findByRole('button', { name: '저장 후 종료' }));
    await screen.findByText('기존 카드 화면', {}, { timeout: 5000 });
    await act(async () => { await router.navigate('/new'); });
    change('카드 제목', '다음 카드'); fireEvent.click(screen.getByRole('button', { name: '작성 시작' }));
    fireEvent.click(await screen.findByRole('button', { name: '저장 후 종료' }));
    await screen.findByText('기존 카드 화면', {}, { timeout: 5000 });
    expect(create).toHaveBeenCalledTimes(2); expect(create.mock.calls[0][1]).not.toBe(create.mock.calls[1][1]);
  }, 20000); // 만들기·저장·화면 이동을 두 번 거쳐, 전체 시험이 한꺼번에 돌 때는 화면 이동이 1초를 넘길 때가 있다

  it('keeps untouched server STAR slots when replay already contains writing', async () => {
    const returned = card({ title: '이미 만든 카드', version: { ...card().version, situation: '서버에서 이어 쓴 상황', task: '서버 과제', action: null, result: null } });
    vi.spyOn(endpoints, 'createManualDraft').mockResolvedValue(returned);
    const save = vi.spyOn(endpoints, 'saveDraft').mockImplementation(async (id, fields) => ({ cardId: id, versionNo: 1, ...fields, savedAt: '2026-10-12T00:00:01Z' }));
    mount(); change('카드 제목', '다른 요청 제목'); change('행동', '내가 입력한 행동');
    fireEvent.click(screen.getByRole('button', { name: '작성 시작' }));
    await waitFor(() => expect(screen.getByLabelText('상황')).toHaveValue('서버에서 이어 쓴 상황'));
    expect(screen.getByLabelText('행동')).toHaveValue('내가 입력한 행동');
    await waitFor(() => expect(save).toHaveBeenCalledWith(returned.id, expect.objectContaining({ situation: '서버에서 이어 쓴 상황', task: '서버 과제', action: '내가 입력한 행동', result: null })));
  });

  it('does not PATCH an already confirmed replay or silently discard local text', async () => {
    const returned = card({ status: 'CONFIRMED', confirmedAt: '2026-10-12T00:00:00Z' });
    vi.spyOn(endpoints, 'createManualDraft').mockResolvedValue(returned); const save = vi.spyOn(endpoints, 'saveDraft');
    const router = mount(); change('카드 제목', '작성 중 제목'); change('상황', '남겨 둘 입력');
    fireEvent.click(screen.getByRole('button', { name: '작성 시작' }));
    await screen.findByText(/이미 확정/);
    expect(save).not.toHaveBeenCalled(); expect(router.state.location.pathname).toBe('/new');
    expect(screen.getByLabelText('상황')).toHaveValue('남겨 둘 입력');
    expect(screen.getByRole('link', { name: '기존 카드 보기' })).toHaveAttribute('href', `/cards/${returned.id}`);
  });
});

import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { Candidate, CandidateBoard, CardSummary, Job, RepoDetail } from '@/api/schemas';
import { AnalyzePage } from '@/features/repos/AnalyzePage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/jobWatcher', () => ({ releaseJobToast: vi.fn(), suppressJobToast: vi.fn() }));
// 어디로 가는지만 본다 — 실제로 옮겨 가면 이 화면이 사라져 그 뒤를 볼 수 없다
const navigate = vi.hoisted(() => vi.fn());
vi.mock('react-router-dom', async (importOriginal) => ({ ...(await importOriginal<typeof import('react-router-dom')>()), useNavigate: () => navigate }));

const BOARD = '/repos/r_auth/candidates';
const LONG = { timeout: 3000 }; // 부하가 큰 기계에서도 넘어가기까지 기다릴 수 있게
const steps = (state: 'DONE' | 'RUNNING') => ['PR_REVIEW', 'COMMITS', 'COMPRESS', 'REASON'].map((key) => ({ key, state, done: 4, total: null }));
const job = (state: 'RUNNING' | 'SUCCEEDED', over: Record<string, unknown> = {}) => Job.parse({
  jobId: 'job', type: 'ANALYZE', state, partial: false, steps: steps(state === 'SUCCEEDED' ? 'DONE' : 'RUNNING'), errorCode: null, retryable: null, retryAfterSec: null,
  result: null, startedAt: '2026-11-02T00:00:00Z', updatedAt: '2026-11-02T00:00:10Z', finishedAt: state === 'SUCCEEDED' ? '2026-11-02T00:00:10Z' : null, pollAfterMs: 60_000, ...over,
});
const candidate = (id: string, over: Record<string, unknown> = {}) => Candidate.parse({
  id, repoId: 'r_auth', type: 'PR', status: 'NEW', ref: '#1', title: `후보 ${id}`, reason: '리뷰를 주고받으며 다듬은 일이에요',
  meta: { commits: 3, files: 4, reviewComments: 2, days: 3, codeRatio: 0.9 }, weak: false, score: 10, commits: null, usedByCardId: null, ...over,
});
const boardOf = (candidates: Candidate[]) => CandidateBoard.parse({ repoId: 'r_auth', verdict: 'OK', partial: false, retryAfterSeconds: null, readCoverage: null, emptyReasons: [], candidates });
const cardOf = (repoId: string | null) => CardSummary.parse({
  id: `card_of_${repoId}`, kind: 'TECH', status: 'DRAFT', title: '전에 만든 카드', versionNo: 1, star: { S: 'FILLED', T: 'EMPTY', A: 'FILLED', R: 'EMPTY' },
  updatedAt: '2026-10-30T00:00:00Z', repo: repoId ? { id: repoId, owner: 'hong-dev', name: repoId } : null,
});

// 점수 순으로 위 다섯은 s1~s5 — 점수가 가장 높은 약한 후보·이미 카드로 만든 후보·제외한 후보는 들어가면 안 된다
const MANY = [
  candidate('s3', { score: 50 }), candidate('s7', { score: 10 }), candidate('s1', { score: 70 }), candidate('s5', { score: 30 }),
  candidate('s2', { score: 60 }), candidate('s6', { score: 20 }), candidate('s4', { score: 40 }),
  candidate('weak', { weak: true, score: 99 }), candidate('used', { status: 'USED', usedByCardId: 'card_x', score: 98 }), candidate('out', { status: 'EXCLUDED', score: 97 }),
];
const NOTHING_TO_PICK = [candidate('weak', { weak: true, score: 90 }), candidate('used', { status: 'USED', usedByCardId: 'card_x', score: 80 }), candidate('out', { status: 'EXCLUDED', score: 70 })];

const settle = (ms: number) => act(async () => { await new Promise((done) => setTimeout(done, ms)); });

function mount(first: Job, { board = boardOf(MANY), cards = [] }: { board?: CandidateBoard; cards?: CardSummary[] } = {}) {
  let latest = first;
  vi.spyOn(endpoints, 'job').mockImplementation(async () => latest);
  const read = vi.spyOn(endpoints, 'candidates').mockResolvedValue(board);
  const list = vi.spyOn(endpoints, 'cards').mockResolvedValue(cards);
  const create = vi.spyOn(endpoints, 'createCardsFromCandidates').mockResolvedValue({ jobId: 'job_draft', cardIds: ['card_a', 'card_b'] });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  client.setQueryData(keys.repo('r_auth'), RepoDetail.parse(handle('GET', '/repos/r_auth', new URLSearchParams(), {}, true).data));
  client.setQueryData(keys.job('job'), first);
  // 정리 전에 본 보드와 카드 목록이 캐시에 남아 있는 상태
  client.setQueryData(keys.candidates('r_auth'), boardOf([candidate('old', { score: 100 })]));
  client.setQueryData(keys.cards, [cardOf('r_auth')]);
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/repos/r_auth/run?job=job']}><Routes>
    <Route path="/repos/:repoId/run" element={<AnalyzePage />} />
  </Routes></MemoryRouter></QueryClientProvider>);
  const finish = (next: Job) => { latest = next; act(() => { client.setQueryData(keys.job('job'), next); }); };
  return { read, list, create, finish };
}

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.clearAllMocks(); vi.restoreAllMocks(); });

describe('정리가 끝나면 카드 초안까지 이어서 쓴다', () => {
  it('정리가 끝나는 것을 이 화면에서 봤고 그 레포에 카드가 없으면 추천 후보로 카드 초안을 만들고 쓰는 화면으로 간다', async () => {
    const { create, read, finish } = mount(job('RUNNING'), { cards: [cardOf('r_web'), cardOf(null)] }); // 다른 레포나 레포 없는 카드는 막지 않는다
    let release!: () => void;
    const held = new Promise<void>((done) => { release = done; });
    read.mockImplementation(async () => { await held; return boardOf(MANY); });
    finish(job('SUCCEEDED'));
    await screen.findByText('추천 경험으로 카드까지 이어서 써요', {}, LONG); // 후보를 읽는 동안
    expect(screen.getByRole('link', { name: '후보 직접 고르기' })).toHaveAttribute('href', BOARD);
    expect(screen.queryByRole('link', { name: '후보 보드로 바로 가기' })).not.toBeInTheDocument();
    expect(create).not.toHaveBeenCalled();

    release();
    await screen.findByText('카드를 쓰기 시작했어요', {}, LONG);
    expect(screen.queryByRole('link', { name: '후보 직접 고르기' })).not.toBeInTheDocument();
    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/cards/card_a', { replace: true, state: { cardOrder: ['card_a', 'card_b'] } }), LONG);
    expect(create).toHaveBeenCalledTimes(1);
    expect(create).toHaveBeenCalledWith('r_auth', ['s1', 's2', 's3', 's4', 's5']); // 쓸 만한 후보가 일곱 개여도 다섯 개까지, 점수 순이다
    expect(navigate).toHaveBeenCalledTimes(1);
  });

  it('그 레포에 카드가 이미 있으면 만들지 않고 후보 보드로 간다', async () => {
    const { create, finish } = mount(job('RUNNING'), { cards: [cardOf('r_auth')] });
    finish(job('SUCCEEDED'));
    await waitFor(() => expect(navigate).toHaveBeenCalledWith(BOARD, { replace: true }), LONG);
    expect(create).not.toHaveBeenCalled();
  });

  it('추천할 후보가 없으면 만들지 않고 후보 보드로 간다', async () => {
    const { create, finish } = mount(job('RUNNING'), { board: boardOf(NOTHING_TO_PICK) });
    finish(job('SUCCEEDED'));
    await waitFor(() => expect(navigate).toHaveBeenCalledWith(BOARD, { replace: true }), LONG);
    expect(create).not.toHaveBeenCalled();
  });

  it('이미 끝난 정리를 다시 열면 만들지 않는다', async () => {
    const { create, read } = mount(job('SUCCEEDED'));
    expect(screen.getByRole('link', { name: '후보 보드로 바로 가기' })).toHaveAttribute('href', BOARD);
    await waitFor(() => expect(navigate).toHaveBeenCalledWith(BOARD, { replace: true }), LONG);
    expect(create).not.toHaveBeenCalled();
    expect(read).not.toHaveBeenCalled();
  });

  it('카드를 만들지 못하면 후보 보드로 가고 다시 시도하지 않는다', async () => {
    const { create, finish } = mount(job('RUNNING'));
    create.mockRejectedValue(new ApiError('INTERNAL_ERROR', '서버 오류', 500));
    finish(job('SUCCEEDED'));
    await waitFor(() => expect(navigate).toHaveBeenCalledWith(BOARD, { replace: true }), LONG);
    await settle(200);
    expect(create).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledTimes(1);
  });

  it('일부만 읽은 정리는 카드를 만들지 않고 지금처럼 멈춘다', async () => {
    const { create, read, list, finish } = mount(job('RUNNING'));
    finish(job('SUCCEEDED', { partial: true, errorCode: 'COLLECTION_CAPPED', retryable: false }));
    await screen.findByRole('heading', { level: 1, name: '읽은 데까지 정리했어요' }, LONG);
    await settle(900); // 다 읽은 정리가 넘어가기까지 머무는 시간보다 길게
    expect(screen.getByRole('button', { name: '읽은 범위의 후보 보기' })).toBeInTheDocument();
    expect(create).not.toHaveBeenCalled();
    expect(read).not.toHaveBeenCalled();
    expect(list).not.toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalled();
  });
});

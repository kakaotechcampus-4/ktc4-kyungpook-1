import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, MemoryRouter, Route, RouterProvider, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CoverLetter, coverLetterKeys } from '@/api/coverLetters';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { Card, type InterviewTurn } from '@/api/schemas';
import { CardPage } from '@/features/cards/CardPage';
import { InterviewPage } from '@/features/cards/InterviewPage';
import { CoverLetterNewPage } from '@/features/coverLetters/CoverLetterNewPage';
import { CoverLetterPage } from '@/features/coverLetters/CoverLetterPage';
import { proceedDestinations } from '@/features/match/gapFill';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';
import { answeredBy, appliedReply } from './replyResult';
import { connectMockServer } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

const POSTING = { id: '3f6c2a10-6b1d-4c2e-9a51-0d7e8b4f1a01', company: '가온소프트', title: '백엔드 엔지니어 (Java·Spring)' };
const TEXT = '결제 오류를 줄이려고 재시도 로직을 바꿨다.';
const letter = (posting: unknown) => CoverLetter.parse({
  id: 'cl_1', stage: 'DRAFTED', pollAfterMs: null, jobRole: '백엔드 개발', createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z', posting,
  steps: { assign: [], draft: [] },
  questions: [{
    id: 'q1', seq: 1, text: '지원 동기를 적어 주세요', charLimit: 700, theme: 'MOTIVATION',
    primaryCard: { id: 'card_03', title: '토큰 만료 자동 재발급', kind: 'TECH' }, primaryCardDeleted: false, supportingCard: null,
    assignedBy: 'SUB_AGENT', pickReason: null, considered: [], materials: [], asks: [], writing: null,
    draft: { text: TEXT, aiText: TEXT, edited: false, charCount: TEXT.length, overBy: 0, sentences: [{ seq: 1, text: TEXT }], marks: [], blanks: [], source: 'LLM' },
  }],
});
const newClient = () => new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe('N1 초안이 끝난 자소서의 다음 걸음', () => {
  const mountLetter = (posting: unknown) => {
    connectMockServer();
    const client = newClient();
    client.setQueryData(coverLetterKeys.one('cl_1'), letter(posting));
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/cover-letters/cl_1']}>
          <Routes><Route path="/cover-letters/:coverLetterId" element={<CoverLetterPage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
  };

  it('공고가 있으면 원문 링크만 보이고, 다음 공고 보기와 캘린더 담기는 없다', async () => {
    mountLetter(POSTING);
    const source = await screen.findByRole('link', { name: /공고 원문에서 지원하기/ });
    expect(source).toHaveAttribute('href', expect.stringMatching(/^https?:\/\//));
    expect(source).toHaveAttribute('target', '_blank');
    expect(screen.queryByRole('link', { name: '다음 공고 보기' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '캘린더에 담기' })).not.toBeInTheDocument();
    expect(source.closest('.cl-next')).toBeInTheDocument();
  });

  it('공고가 없는 자소서에는 둘 다 없다', async () => {
    mountLetter(null);
    await screen.findByRole("link", { name: "문항 고치기" });
    expect(screen.queryByRole('link', { name: /공고 원문에서 지원하기/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /캘린더/ })).not.toBeInTheDocument();
  });
});

describe('N2 채우고 돌아오면 공고로 간다', () => {
  const load = (id: string) => Card.parse(handle('GET', `/cards/${id}`, new URLSearchParams(), {}, true).data);

  it('카드 초안으로 보낼 때 이동 상태에 공고 주소가 실린다', () => {
    const result = { created: [{ repoId: 'r_auth', candidateIds: ['c_51'], jobId: 'job_9', cardIds: ['card_9'] }], continueCards: [], started: [], failed: 0 };
    expect(proceedDestinations(result, 'm 1')).toEqual([{ path: '/cards/card_9', state: { cardOrder: ['card_9'], returnTo: '/match/m%201' } }]);
    expect(proceedDestinations(result)).toEqual([{ path: '/cards/card_9', state: { cardOrder: ['card_9'] } }]);
  });

  const mountInterview = (state: unknown) => {
    const original = load('card_01');
    const card = { ...original, version: { ...original.version, task: null, result: null } };
    const template = handle('POST', '/cards/card_01/interview', new URLSearchParams(), { field: 'R' }, true).data as InterviewTurn;
    let turns: InterviewTurn[] = [{ ...template, field: 'R', turnNo: 1, question: 'R question', answer: null, outcome: null }];
    vi.spyOn(endpoints, 'interviewTurns').mockImplementation(async () => turns);
    vi.spyOn(endpoints, 'replyInterview').mockImplementation(async (_id, no, body) => {
      turns = turns.map((turn) => turn.turnNo === no ? answeredBy(turn, body) : turn);
      return appliedReply({ ...card, status: 'CONFIRMED', confirmedAt: '2026-11-01T00:00:00Z' });
    });
    const client = newClient();
    client.setQueryData(keys.card(card.id), card);
    client.setQueryData(keys.interview(card.id), turns);
    const router = createMemoryRouter([
      { path: '/cards/:cardId/interview', element: <InterviewPage /> },
      { path: '/cards/repo/:repoId', element: <p>레포 폴더 화면</p> },
      { path: '/match/:matchId', element: <p>공고 화면</p> },
    ], { initialEntries: [{ pathname: `/cards/${card.id}/interview`, state }] });
    render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
    return router;
  };
  const answer = async () => {
    fireEvent.change(await screen.findByRole('textbox', { name: '답변' }), { target: { value: 'R answer' } });
    fireEvent.click(screen.getByRole('button', { name: '답 보내기' }));
    await screen.findByText('카드를 확정했어요');
  };

  it('되묻기로 확정하면 돌아가기 버튼이 returnTo 로 가고, 없으면 카드 폴더로 간다', async () => {
    const router = mountInterview({ returnTo: '/match/m1' });
    await answer();
    fireEvent.click(screen.getByRole('button', { name: '공고로 돌아가기' }));
    await screen.findByText('공고 화면');
    expect(router.state.location.pathname).toBe('/match/m1');
    cleanup();

    const folder = mountInterview(undefined);
    await answer();
    fireEvent.click(screen.getByRole('button', { name: '폴더로 돌아가기' }));
    await screen.findByText('레포 폴더 화면');
    expect(folder.state.location.pathname).toBe('/cards/repo/r_auth');
  });


  it('수정을 저장하다 서버가 확정한 카드 화면의 돌아가기 링크도 returnTo 로 가고, 없으면 카드 폴더로 간다', async () => {
    const cases = [[{ returnTo: '/match/m1' }, '공고로 돌아가기', '/match/m1'], [undefined, '폴더로 돌아가기', '/cards/repo/r_auth']] as const;
    for (const [state, name, href] of cases) {
      resetDb();
      connectMockServer();
      const draft = load('card_01');
      const client = newClient();
      const router = createMemoryRouter([{ path: '/cards/:cardId', element: <CardPage /> }], { initialEntries: [{ pathname: '/cards/card_01', state }] });
      render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
      await screen.findByRole('button', { name: '확정' });
      Object.assign(db.cards.find((card) => card.id === draft.id)!, { status: 'CONFIRMED', confirmedAt: '2026-11-01T00:00:00Z' });
      act(() => { client.setQueryData(keys.card(draft.id), { ...draft, status: 'CONFIRMED', confirmedAt: '2026-11-01T00:00:00Z' }); });
      expect(await screen.findByRole('link', { name })).toHaveAttribute('href', href);
      cleanup();
    }
  });
});

describe('N3 새 자소서 안내 한 줄', () => {
  const mountNew = (path: string) => {
    connectMockServer();
    render(
      <QueryClientProvider client={newClient()}>
        <MemoryRouter initialEntries={[path]}>
          <Routes><Route path="/cover-letters/new" element={<CoverLetterNewPage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
  };

  it('공고 없이 열면 공고 목록으로 가는 안내가 보이고, 공고로 시작하면 보이지 않는다', async () => {
    mountNew('/cover-letters/new');
    expect(screen.getByRole('link', { name: '공고 목록' })).toHaveAttribute('href', '/match');
    cleanup();
    mountNew(`/cover-letters/new?postingId=${POSTING.id}`);
    await waitFor(() => expect(screen.getByPlaceholderText('예: 백엔드 개발')).toHaveValue(POSTING.title));
    expect(screen.queryByRole('link', { name: '공고 목록' })).not.toBeInTheDocument();
  });
});

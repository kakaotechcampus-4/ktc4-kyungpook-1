import { StrictMode } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CoverLetter, coverLetterKeys } from '@/api/coverLetters';
import { keys } from '@/api/keys';
import { CardSummary } from '@/api/schemas';
import { CoverLetterNewPage } from '@/features/coverLetters/CoverLetterNewPage';
import { CoverLetterPage } from '@/features/coverLetters/CoverLetterPage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

/** 사용자는 직무와 문항만 적는다 — 카드 확인·질문 화면에서 멈추지 않고, 자소서 화면이 넘어가는 요청을 스스로 보낸다 */
const question = (over: Record<string, unknown> = {}) => ({
  id: 'q1', seq: 1, text: '어려운 문제를 해결한 경험을 적어 주세요', charLimit: 700, theme: 'PROBLEM_SOLVING',
  primaryCard: { id: 'card_03', title: '토큰 만료 자동 재발급', kind: 'TECH' }, primaryCardDeleted: false,
  supportingCard: null, assignedBy: 'SUB_AGENT', pickReason: null,
  considered: [], materials: ['SITUATION', 'ACTION'], asks: [], draft: null, writing: null, ...over,
});
const letter = (over: Record<string, unknown> = {}) => CoverLetter.parse({
  id: 'cl_1', stage: 'ASSIGNED', failedStep: null, errorCode: null, retryable: null, pollAfterMs: null,
  jobRole: '백엔드 개발', memo: null, createdAt: '2026-11-05T00:00:00Z', updatedAt: '2026-11-05T00:00:00Z',
  questions: [question()], steps: { assign: [], draft: [] }, ...over,
});
const requiredAsk = { item: 'RESULT', question: '이 일로 무엇이 얼마나 나아졌나요?', templates: [], required: true, answer: null, skipped: false };

type Reply = { status: number; data?: unknown; error?: { code: string; message: string } };
const sent: { call: string; body: unknown }[] = [];
const calls = () => sent.map((s) => s.call);
const count = (call: string) => calls().filter((c) => c === call).length;
const ok = (data: unknown): Reply => ({ status: 200, data });
const serverError: Reply = { status: 500, error: { code: 'INTERNAL', message: '서버 오류' } };
const notFound: Reply = { status: 404, error: { code: 'NOT_FOUND', message: '없음' } };
const CREATE = 'POST /cover-letters';
const ASKS = 'POST /cover-letters/cl_1/asks';
const DRAFTS = 'POST /cover-letters/cl_1/drafts';
const LETTER = 'GET /cover-letters/cl_1';

/** fetch 를 가짜 서버로 보낸다 — 보낸 요청을 '메서드 경로' 로 남기고, 응답은 요청과 그 요청이 몇 번째인지로 정한다 */
function serve(reply: (call: string, nth: number) => Reply) {
  sent.length = 0;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (resource: RequestInfo | URL, init?: RequestInit) => {
    const raw = typeof resource === 'string' ? resource : resource instanceof URL ? resource.toString() : resource.url;
    const call = `${(init?.method ?? 'GET').toUpperCase()} ${new URL(raw, 'http://localhost').pathname.replace(/^\/api/, '')}`;
    const nth = count(call);
    sent.push({ call, body: typeof init?.body === 'string' ? JSON.parse(init.body) : null });
    const r = reply(call, nth);
    return new Response(JSON.stringify({ data: r.data ?? null, error: r.error ?? null }), { status: r.status, headers: { 'Content-Type': 'application/json' } });
  });
}

const newClient = () => new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });

function mountNew() {
  const client = newClient();
  const cards = handle('GET', '/cards', new URLSearchParams(), {}, true, {}).data as unknown[];
  client.setQueryData(keys.cards, cards.map((c) => CardSummary.parse(c)));
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/cover-letters/new']}>
        <Routes>
          <Route path="/cover-letters/new" element={<CoverLetterNewPage />} />
          <Route path="/cover-letters/:coverLetterId" element={<p>자소서 화면</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function mountLetter(seed: CoverLetter, strict = false) {
  const client = newClient();
  client.setQueryData(coverLetterKeys.one(seed.id), seed);
  const page = (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/cover-letters/${seed.id}`]}>
        <Routes><Route path="/cover-letters/:coverLetterId" element={<CoverLetterPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
  render(strict ? <StrictMode>{page}</StrictMode> : page);
  return client;
}

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe('새 자소서 입력', () => {
  it('새 자소서는 직무와 문항만 받고 지원 이유와 강점 없이 만든다', async () => {
    serve((call) => (call === CREATE ? ok(letter({ stage: 'ASSIGNING' })) : notFound));
    mountNew();
    expect(screen.queryByLabelText('지원 이유')).not.toBeInTheDocument();
    expect(screen.queryByText(/지원 이유|강점/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '꼼꼼함' })).not.toBeInTheDocument();
    fireEvent.change(screen.getByPlaceholderText('예: 백엔드 개발'), { target: { value: '백엔드 개발' } });
    fireEvent.change(screen.getByLabelText('1번 문항'), { target: { value: '지원 동기와 입사 후 포부를 적어 주세요' } });
    fireEvent.click(screen.getByRole('button', { name: '초안 쓰기' }));
    await screen.findByText('자소서 화면');
    expect(sent.find((s) => s.call === CREATE)?.body).toEqual({
      jobRole: '백엔드 개발', memo: null, strengths: [], questions: [{ text: '지원 동기와 입사 후 포부를 적어 주세요', charLimit: null }], postingId: null,
    });
  });
});

describe('자소서 화면이 스스로 넘어간다', () => {
  it('카드 고르기가 끝난 자소서를 열면 누르지 않아도 재료 확인을 시작한다', async () => {
    serve((call) => (call === ASKS ? ok(letter({ stage: 'ASKING' })) : notFound));
    mountLetter(letter({ stage: 'ASSIGNED' }));
    await waitFor(() => expect(count(ASKS)).toBe(1));
    expect(screen.getByRole('heading', { level: 1, name: '카드에 없는 재료를 확인하고 있어요' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '다음: 질문 받기' })).not.toBeInTheDocument();
    expect(calls()).toEqual([ASKS]);
  });

  it('재료 확인이 끝난 자소서를 열면 누르지 않아도 초안 쓰기를 시작한다', async () => {
    serve((call) => (call === DRAFTS ? ok(letter({ stage: 'DRAFTING' })) : notFound));
    mountLetter(letter({ stage: 'ASKED', questions: [question({ asks: [requiredAsk] })] }));
    await waitFor(() => expect(count(DRAFTS)).toBe(1));
    expect(screen.getByRole('heading', { level: 1, name: '초안을 쓰고 있어요' })).toBeInTheDocument();
    expect(screen.queryByText('조금만 더 알려 주세요')).not.toBeInTheDocument();
    expect(calls()).toEqual([DRAFTS]); // 답을 먼저 저장하는 요청 없이 곧바로 초안이다
  });

  it('넘어가는 요청이 실패하면 다시 시도 버튼만 남기고 스스로 되풀이하지 않는다', async () => {
    serve((call, nth) => (call === ASKS ? (nth === 0 ? serverError : ok(letter({ stage: 'ASKING' }))) : notFound));
    mountLetter(letter({ stage: 'ASSIGNED' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('서버에서 처리하지 못했어요');
    expect(screen.getByRole('region', { name: '자기소개서' })).toBeInTheDocument(); // 종이는 그대로 남는다
    expect(screen.getAllByRole('button', { name: '다시 시도' })).toHaveLength(1);
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(count(ASKS)).toBe(1);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(count(ASKS)).toBe(2));
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
  });

  it('같은 단계에서는 요청을 한 번만 보낸다', async () => {
    const reread = letter({ stage: 'ASSIGNED', updatedAt: '2026-11-05T01:00:00Z' });
    serve((call) => (call === ASKS ? serverError : call === LETTER ? ok(reread) : notFound));
    const client = mountLetter(letter({ stage: 'ASSIGNED' }), true); // 개발 모드의 효과 두 번 실행도 한 번으로 센다
    await screen.findByRole('alert');
    expect(count(ASKS)).toBe(1);
    await act(async () => { await client.refetchQueries({ queryKey: coverLetterKeys.one('cl_1') }); }); // 같은 단계로 다시 읽기
    expect(count(LETTER)).toBe(1);
    act(() => { client.setQueryData(coverLetterKeys.one('cl_1'), letter({ stage: 'ASKING' })); }); // 단계가 갔다가
    act(() => { client.setQueryData(coverLetterKeys.one('cl_1'), reread); }); // 늦은 응답으로 되돌아와도
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(count(ASKS)).toBe(1);
  });
});

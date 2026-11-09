import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CoverLetter, coverLetterKeys } from '@/api/coverLetters';
import { keys } from '@/api/keys';
import { CardSummary } from '@/api/schemas';
import { CoverLetterPage } from '@/features/coverLetters/CoverLetterPage';
import { BLANK_TEXT, blanksIn } from '@/lib/draftText';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

/**
 * 초안이 나오면 대화 탭이 먼저 열려 빈칸이 있는 문항부터 스스로 묻는다.
 * 채우거나 넘기면 다음 빈칸 문항으로 이어 가고, 기록이 있는 채로 돌아왔거나 스스로 연 질문이 실패하면 새로 열지 않는다.
 * 목 서버가 결정적으로 답한다(733 시험과 같다): 항목 빈칸이 있는 문항은 그 빈칸부터 묻는다.
 */
const req = (method: string, path: string, body: unknown = {}) =>
  handle(method, path, new URLSearchParams(), body as Record<string, unknown>, true, {});
let shift = 0;
const realNow = Date.now;
const advance = (sec: number) => { shift += sec * 1000; Date.now = () => realNow() + shift; };

function draftedFromMock(count = 2) {
  const created = CoverLetter.parse(req('POST', '/cover-letters', { jobRole: '백엔드 개발', memo: '결제 서비스의 오류를 줄이는 일을 직접 해 보고 싶어 지원했어요', questions: [
    { text: '팀으로 함께 성과를 낸 경험을 적어 주세요', charLimit: null },
    { text: '어려운 문제를 해결한 경험을 적어 주세요', charLimit: null },
    { text: '실패에서 배운 경험을 적어 주세요', charLimit: null },
  ].slice(0, count) }).data);
  advance(7);
  req('POST', `/cover-letters/${created.id}/asks`);
  advance(5);
  req('POST', `/cover-letters/${created.id}/drafts`);
  advance(30);
  return CoverLetter.parse(req('GET', `/cover-letters/${created.id}`).data);
}
/** 문항마다 항목 빈칸을 글 끝에 붙인다 — 목 서버와 화면이 같은 글을 보게 둘 다 바꾼다 */
function withBlanks(l: CoverLetter, blanks: string[][]): CoverLetter {
  return {
    ...l,
    questions: l.questions.map((q, i) => {
      const text = [q.draft!.text, ...(blanks[i] ?? [])].join(' ');
      db.coverLetters[0].questions[i].draft.text = text;
      return { ...q, draft: { ...q.draft!, text } };
    }),
  };
}
const openPath = (l: CoverLetter, i = 0) => `/cover-letters/${l.id}/questions/${l.questions[i].id}/turns/open`;
const skipPath = (l: CoverLetter, i = 0) => `/cover-letters/${l.id}/questions/${l.questions[i].id}/turns/skip`;

/** fetch 를 목 서버로 보내고 보낸 요청을 남긴다. failOpens 를 주면 열기(open) 요청 앞의 그만큼은 429 로 실패한다 */
function stubFetch(log: string[], failOpens = 0) {
  let failed = 0;
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (resource: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(typeof resource === 'string' ? resource : resource instanceof URL ? resource.toString() : resource.url, 'http://localhost');
    const path = url.pathname.replace(/^\/api/, '');
    const method = (init?.method ?? 'GET').toUpperCase();
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : {};
    log.push(`${method} ${path}`);
    if (method === 'POST' && path.endsWith('/turns/open') && failed < failOpens) {
      failed += 1;
      return new Response(JSON.stringify({ data: null, error: { code: 'RATE_LIMITED', message: '잠시 뒤에 다시 시도해 주세요.' } }), { status: 429, headers: { 'Content-Type': 'application/json' } });
    }
    const r = handle(method, path, url.searchParams, body, true, {});
    return new Response(JSON.stringify({ data: r.data, error: r.error }), { status: r.status, headers: { 'Content-Type': 'application/json' } });
  });
}

function mount(l: CoverLetter) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(coverLetterKeys.one(l.id), l);
  client.setQueryData(keys.cards, (req('GET', '/cards').data as unknown[]).map((c) => CardSummary.parse(c)));
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/cover-letters/${l.id}`]}>
        <Routes><Route path="/cover-letters/:coverLetterId" element={<CoverLetterPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
const area = (n: number) => screen.getByLabelText(`${n}번 문항 초안`) as HTMLTextAreaElement;
const panel = () => screen.getByRole('complementary', { name: '다듬기 패널' });
const input = () => within(panel()).getByLabelText('한 줄로 답하기') as HTMLTextAreaElement;
/** 소리로 읽는 칸(sr-only)은 화면 글과 같은 말을 담으므로 화면 글만 찾는다 */
const SEEN = { ignore: 'script, style, .sr-only' };
const LONG = '백엔드를 맡아 AI 담당 팀원과 로그를 같이 보며 원인을 좁혔어요';
/** 스스로 하는 일이 더 없는지 보려고 잠깐 기다린다 */
const later = () => act(async () => { await new Promise((r) => setTimeout(r, 60)); });
const opens = (log: string[]) => log.filter((x) => x.endsWith('/turns/open'));
/** 보낼 수 있을 때까지(생각하는 중이 끝날 때까지) 기다렸다가 보낸다 */
async function say(text: string) {
  fireEvent.change(input(), { target: { value: text } });
  const send = within(panel()).getByRole('button', { name: '보내기' });
  await waitFor(() => expect(send).toBeEnabled());
  fireEvent.click(send);
}
/** 넘길 수 있을 때까지 기다렸다가 넘긴다 */
async function pass() {
  const button = within(panel()).getByRole('button', { name: '넘기기' });
  await waitFor(() => expect(button).toBeEnabled());
  fireEvent.click(button);
}

beforeEach(() => { resetDb(); shift = 0; Date.now = realNow; });
afterEach(() => { cleanup(); Date.now = realNow; vi.restoreAllMocks(); });

describe('대화가 먼저 열려 스스로 묻는다', () => {
  it('초안이 나오면 대화 탭이 먼저 열려 있다', async () => {
    stubFetch([]);
    mount(draftedFromMock());
    const chat = screen.getByRole('tab', { name: '대화' });
    expect(chat).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: '진단과 첨삭' })).toHaveAttribute('aria-selected', 'false');
    expect(screen.getByRole('tabpanel')).toHaveAttribute('aria-labelledby', chat.id);
    await within(panel()).findByRole('button', { name: 'Q1 얘기하기' });
  });

  it('대화 기록이 없고 빈칸이 있으면 누르지 않아도 그 문항부터 묻는다', async () => {
    const log: string[] = [];
    stubFetch(log);
    const l = withBlanks(draftedFromMock(), [[], [BLANK_TEXT.RESULT]]);
    mount(l);
    const ask = await within(panel()).findByText(/Q2 글에 '결과' 빈칸이 있어요/, SEEN);
    expect(ask.closest('.chat-bub--it')?.querySelector('.chat-tag')?.textContent).toBe('Q2, 결과');
    expect(opens(log)).toEqual([`POST ${openPath(l, 1)}`]);
    expect(input()).not.toHaveFocus();
  });

  it('답으로 자리가 채워진 뒤 빈칸이 남은 문항이 있으면 이어서 묻는다', async () => {
    const log: string[] = [];
    stubFetch(log);
    const l = withBlanks(draftedFromMock(), [[BLANK_TEXT.RESULT, BLANK_TEXT.LEARNING], [BLANK_TEXT.RESULT]]);
    mount(l);
    await within(panel()).findByText(/Q1 글에 '결과' 빈칸이 있어요/, SEEN);
    await say(LONG);
    // 같은 문항에 빈칸이 남았으면 그 문항을 다시 연다
    await waitFor(() => expect(opens(log)).toHaveLength(2), { timeout: 3000 });
    expect(area(1).value).not.toContain(BLANK_TEXT.RESULT);
    expect(area(1).value).toContain(BLANK_TEXT.LEARNING);
    await say(LONG);
    // 그 문항의 빈칸이 다 찼으면 다음 빈칸 문항으로 간다
    expect(await within(panel()).findByText(/Q2 얘기로 갈게요/, SEEN, { timeout: 3000 })).toBeInTheDocument();
    expect(area(1).value).not.toContain(BLANK_TEXT.LEARNING);
    expect(opens(log)).toEqual([`POST ${openPath(l, 0)}`, `POST ${openPath(l, 0)}`, `POST ${openPath(l, 1)}`]);
  }, 15_000);

  it('빈칸이 없으면 스스로 묻지 않는다', async () => {
    const log: string[] = [];
    stubFetch(log);
    const l = draftedFromMock();
    expect(l.questions.some((q) => blanksIn(q.draft!.text).length > 0)).toBe(false);
    mount(l);
    expect(await within(panel()).findByText('빈 곳이 없어요. 더 다듬고 싶은 문항이 있으면 얘기해 주세요.')).toBeInTheDocument();
    expect(within(panel()).getByRole('button', { name: 'Q1 얘기하기' })).toBeInTheDocument();
    await later();
    expect(opens(log)).toEqual([]);
  });

  it('넘긴 문항은 다시 스스로 묻지 않고 다음 빈칸 문항으로 간다', async () => {
    const log: string[] = [];
    stubFetch(log);
    const l = withBlanks(draftedFromMock(3), [[BLANK_TEXT.RESULT], [BLANK_TEXT.RESULT], [BLANK_TEXT.LEARNING]]);
    // Q1 은 물을 수 있는 질문을 이미 다 써서, 스스로 열어도 더 물을 게 없다(DONE)고 돌아온다
    db.coverLetters[0].questions[0]._turn = { asked: 2, passed: [], pending: null, followed: false };
    mount(l);
    expect(await within(panel()).findByText(/Q2 글에 '결과' 빈칸이 있어요/, SEEN)).toBeInTheDocument();
    await pass();
    expect(await within(panel()).findByText(/Q3 글에 '배운 점' 빈칸이 있어요/, SEEN)).toBeInTheDocument();
    await pass();
    await waitFor(() => expect(log).toContain(`POST ${skipPath(l, 2)}`));
    await later();
    // 끝난 Q1 · 넘긴 Q2 · 넘긴 Q3 는 빈칸이 남아 있어도 처음으로 돌아가 다시 묻지 않는다
    expect(opens(log)).toEqual([0, 1, 2].map((i) => `POST ${openPath(l, i)}`));
    expect(input()).toBeDisabled();
  });

  it('대화 기록이 있는 자소서를 다시 열면 새 질문을 스스로 열지 않는다', async () => {
    const log: string[] = [];
    const l = withBlanks(draftedFromMock(), [[BLANK_TEXT.RESULT], [BLANK_TEXT.RESULT]]);
    // 지난번에 Q2 질문을 열어 두고 답하지 않은 채로 나갔다
    req('POST', openPath(l, 1));
    stubFetch(log);
    mount(l);
    expect(await within(panel()).findByText(/Q2 글에 '결과' 빈칸이 있어요/, SEEN)).toBeInTheDocument();
    await later();
    expect(log.filter((x) => x.startsWith('POST '))).toEqual([]);
    expect(input()).not.toBeDisabled();
  });

  it('스스로 연 질문이 실패하면 다시 시도하지 않고 다시 보내기만 남긴다', async () => {
    const log: string[] = [];
    stubFetch(log, 1);
    const l = withBlanks(draftedFromMock(), [[BLANK_TEXT.RESULT], [BLANK_TEXT.RESULT]]);
    mount(l);
    expect(await within(panel()).findByRole('alert')).toHaveTextContent('잠시 뒤 다시 보내 주세요');
    await later();
    expect(opens(log)).toEqual([`POST ${openPath(l, 0)}`]);
    // 사용자가 다시 보내면 열리지만, 그 뒤 채워도 스스로 다음 문항을 열지 않는다
    fireEvent.click(within(panel()).getByRole('button', { name: '다시 보내기' }));
    await within(panel()).findByText(/Q1 글에 '결과' 빈칸이 있어요/, SEEN);
    await say(LONG);
    await within(panel()).findByText('Q1에 넣었어요', SEEN, { timeout: 3000 });
    await later();
    expect(opens(log)).toEqual([`POST ${openPath(l, 0)}`, `POST ${openPath(l, 0)}`]);
  }, 15_000);
});

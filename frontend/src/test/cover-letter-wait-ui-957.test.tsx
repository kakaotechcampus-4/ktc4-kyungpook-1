import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CoverLetter, coverLetterKeys } from '@/api/coverLetters';
import { keys } from '@/api/keys';
import { CardSummary } from '@/api/schemas';
import { CoverLetterPage } from '@/features/coverLetters/CoverLetterPage';
import { BLANK_TEXT } from '@/lib/draftText';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

/** #957 자소서 화면 다듬기 — 쓰는 동안의 머리 버튼, 첨삭으로 생긴 빈칸의 대화, 초안을 쓰는 동안의 기다림 화면 */
const req = (method: string, path: string, body: unknown = {}) =>
  handle(method, path, new URLSearchParams(), body as Record<string, unknown>, true, {});
let shift = 0;
const realNow = Date.now;
const advance = (sec: number) => { shift += sec * 1000; Date.now = () => realNow() + shift; };

const question = (over: Record<string, unknown> = {}) => ({
  id: 'q1', seq: 1, text: '어려운 문제를 해결한 경험을 적어 주세요', charLimit: 700, theme: 'PROBLEM_SOLVING',
  primaryCard: { id: 'card_03', title: '토큰 만료 자동 재발급', kind: 'TECH' }, primaryCardDeleted: false,
  supportingCard: null, assignedBy: 'SUB_AGENT', pickReason: '기술로 문제를 푼 과정이 분명해요.',
  considered: [], materials: ['SITUATION', 'ACTION', 'RESULT'], asks: [], draft: null, writing: null, ...over,
});
const letter = (over: Record<string, unknown> = {}) => CoverLetter.parse({
  id: 'cl_1', stage: 'DRAFTING', failedStep: null, errorCode: null, retryable: null, pollAfterMs: 1500,
  jobRole: '백엔드 개발', memo: null, createdAt: '2026-11-03T00:00:00Z', updatedAt: '2026-11-03T00:00:00Z',
  questions: [question()], steps: { assign: [], draft: [] }, ...over,
});

/** 목으로 만들기 → 카드 고르기 → 질문 → 초안까지 끝낸 문항 둘짜리 자소서 */
function draftedFromMock() {
  const created = CoverLetter.parse(req('POST', '/cover-letters', { jobRole: '백엔드 개발', memo: '결제 서비스의 오류를 줄이는 일을 직접 해 보고 싶어 지원했어요', questions: [
    { text: '팀으로 함께 성과를 낸 경험을 적어 주세요', charLimit: null },
    { text: '어려운 문제를 해결한 경험을 적어 주세요', charLimit: null },
  ] }).data);
  advance(7);
  req('POST', `/cover-letters/${created.id}/asks`);
  advance(5);
  req('POST', `/cover-letters/${created.id}/drafts`);
  advance(30);
  return CoverLetter.parse(req('GET', `/cover-letters/${created.id}`).data);
}

/** fetch 를 목 서버로 보내고 보낸 요청을 남긴다 */
function stubFetch(log: string[]) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (resource: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(typeof resource === 'string' ? resource : resource instanceof URL ? resource.toString() : resource.url, 'http://localhost');
    const path = url.pathname.replace(/^\/api/, '');
    const method = (init?.method ?? 'GET').toUpperCase();
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : {};
    log.push(`${method} ${path}`);
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
const panel = () => screen.getByRole('complementary', { name: '다듬기 패널' });

beforeEach(() => { resetDb(); shift = 0; Date.now = realNow; });
afterEach(() => { cleanup(); Date.now = realNow; vi.restoreAllMocks(); vi.useRealTimers(); delete (window as { matchMedia?: unknown }).matchMedia; });

describe('W1 쓰는 동안의 머리 버튼', () => {
  it('쓰는 단계에는 문항 고치기가 없고 지우기는 쓰기 그만두기이며, 초안이 나오면 문항 고치기와 자소서 삭제가 있다', () => {
    for (const stage of ['ASSIGNING', 'ASKING', 'DRAFTING'] as const) {
      mount(letter({ stage }));
      expect(screen.queryByRole('link', { name: '문항 고치기' })).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: '쓰기 그만두기' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: '자소서 삭제' })).not.toBeInTheDocument();
      cleanup();
    }
    stubFetch([]);
    mount(draftedFromMock());
    expect(screen.getByRole('link', { name: '문항 고치기' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '자소서 삭제' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '쓰기 그만두기' })).not.toBeInTheDocument();
  });
});

describe('W2 첨삭으로 생긴 빈칸', () => {
  /** 목 초안의 빈칸을 모두 평범한 글로 바꿔 빈칸 없는 초안으로 시작한다 */
  const draftedClean = () => {
    const made = draftedFromMock();
    for (const q of db.coverLetters[0].questions) {
      const clear = (t: string) => Object.values(BLANK_TEXT).reduce((s, b) => s.split(b).join('내용을 적었습니다.'), t).replace(/\[더 쓸 거리: [^[\]\n]{1,60}\]/g, '내용을 더했습니다.');
      q.draft.text = clear(q.draft.text);
      q.draft.sentences = q.draft.sentences.map((s: { seq: number; text: string }) => ({ ...s, text: clear(s.text) }));
    }
    return CoverLetter.parse(req('GET', `/cover-letters/${made.id}`).data);
  };

  it('첨삭 적용으로 더 쓸 거리 빈칸이 새로 생기면 대화 탭이 그 문항 이야기를 한 번 연다', async () => {
    const log: string[] = [];
    stubFetch(log);
    const l = draftedClean();
    mount(l);
    const opens = () => log.filter((r) => r.endsWith('/turns/open')).length;
    await waitFor(() => expect(log).toContain(`GET /cover-letters/${l.id}/turns`));
    expect(opens()).toBe(0);

    fireEvent.click(screen.getByRole('tab', { name: '진단과 첨삭' }));
    fireEvent.click(within(panel()).getByRole('button', { name: '첨삭 받기' }));
    await within(panel()).findByRole('button', { name: '적용하기' }, { timeout: 3000 });
    db.coverLetters[0].questions[0].polish.draft.text += ' [더 쓸 거리: 관심을 가진 계기]';
    fireEvent.click(within(panel()).getByRole('button', { name: '적용하기' }));

    await waitFor(() => expect(screen.getByRole('tab', { name: '대화' })).toHaveAttribute('aria-selected', 'true'));
    await waitFor(() => expect(opens()).toBe(1));
    expect(log.find((r) => r.endsWith('/turns/open'))).toContain(`/questions/${l.questions[0].id}/turns/open`);
    expect(await within(panel()).findByText(/빈칸이 하나 있어요\(관심을 가진 계기\)/, { ignore: 'script, style, .sr-only' })).toBeInTheDocument();
  });

  it('대화가 닫혀 있고 활성 문항에 빈칸이 남아 있으면 입력 칸이 Q1 얘기하기를 누르라고 하고, 빈칸이 없으면 이전 문구다', async () => {
    stubFetch([]);
    const made = draftedClean();
    const q = db.coverLetters[0].questions[0];
    q.draft.text += ' [더 쓸 거리: 관심을 가진 계기]';
    // 열려 있던 질문을 넘겨 대화를 닫는다 — 빈칸은 글에 그대로 남는다
    req('POST', `/cover-letters/${made.id}/questions/${q.id}/turns/open`);
    req('POST', `/cover-letters/${made.id}/questions/${q.id}/turns/skip`);
    mount(CoverLetter.parse(req('GET', `/cover-letters/${made.id}`).data));
    const box = await within(panel()).findByLabelText('한 줄로 답하기');
    expect(box).toHaveAttribute('placeholder', 'Q1 얘기하기를 누르면 남은 칸을 물어요');
    cleanup();

    resetDb(); shift = 0; Date.now = realNow;
    stubFetch([]);
    const plain = draftedClean();
    req('POST', `/cover-letters/${plain.id}/questions/${plain.questions[0].id}/turns/open`);
    req('POST', `/cover-letters/${plain.id}/questions/${plain.questions[0].id}/turns/skip`);
    mount(CoverLetter.parse(req('GET', `/cover-letters/${plain.id}`).data));
    expect(await within(panel()).findByLabelText('한 줄로 답하기')).toHaveAttribute('placeholder', '채울 곳이 생기면 다시 물어볼게요');
  });
});

describe('W3 초안을 쓰는 동안의 기다림 화면', () => {
  it('아무도 쓰지 않으면 재료를 읽고 첫 문장을 준비하는 중이고, 다른 문항이 쓰는 중이면 차례를 기다리는 중이다', () => {
    mount(letter());
    expect(screen.getAllByRole('status').map((s) => s.textContent)).toEqual(['재료를 읽고 첫 문장을 준비하는 중']);
    cleanup();

    const q2 = question({ id: 'q2', seq: 2, text: '팀으로 함께 성과를 낸 경험을 적어 주세요', writing: null });
    const q1 = question({ writing: { text: '장애 원인을 로그로 좁혔습니다.', round: 0 } });
    mount(letter({ questions: [q1, q2] }));
    expect(screen.getAllByRole('status').map((s) => s.textContent)).toEqual(['쓰는 중', '차례를 기다리는 중']);
  });

  it('제목 아래에 쓰기 시작한 뒤 시간과 마지막 확인 뒤 시간이 매초 늘어난다', () => {
    vi.useFakeTimers();
    mount(letter());
    expect(screen.getByText('0초째 쓰고 있어요 · 마지막 확인 0초 전')).toBeInTheDocument();
    act(() => { vi.advanceTimersByTime(3000); });
    expect(screen.getByText('3초째 쓰고 있어요 · 마지막 확인 3초 전')).toBeInTheDocument();
    act(() => { vi.advanceTimersByTime(60_000); });
    expect(screen.getByText('1분 3초째 쓰고 있어요 · 마지막 확인 1분 3초 전')).toBeInTheDocument();
  });
});

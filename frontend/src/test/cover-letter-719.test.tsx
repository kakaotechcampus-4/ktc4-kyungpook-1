import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CoverLetter, coverLetterKeys, type DraftStep } from '@/api/coverLetters';
import { keys } from '@/api/keys';
import { CardSummary } from '@/api/schemas';
import { CoverLetterPage } from '@/features/coverLetters/CoverLetterPage';
import { draftLine } from '@/lib/coverLetterCopy';
import { STATUS_LABEL, buildQuestionNotes, cellLabel } from '@/lib/coverLetterNotes';
import { withoutSentence } from '@/lib/draftPaper';
import { BLANK_TEXT } from '@/lib/draftText';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

/**
 * #719 ② AI 추론 표시 — AI 가 재료에 없는 해석(배운 점·판단 이유·직무 연결·포부)을 지어 쓴 문장은 marks 의 INFERRED 로 오고,
 * 화면은 노란 형광펜 + 'AI 추론 · 확인 필요' 메모 + 점검표 '추론' 칸으로 보이며 메모에서 확인·고치기·빼기를 한다.
 */
const S1 = '결제 모듈의 장애 대응을 맡았다.';
const S2 = '로그를 모아 원인을 찾았다.';
const LEARNED = '작게 나눠 확인하는 습관이 중요하다는 것을 배웠다.';
const TEXT = [S1, S2, LEARNED].join(' ');
/** 목이 배운 점 답이 없는 첫 문항에 넣는 추론 문장 */
const MOCK_LEARNED = '이 일을 겪으며 문제를 작게 나눠 하나씩 확인하는 습관이 중요하다는 것을 배웠습니다.';

const step = (over: Partial<DraftStep> & { seq: number; kind: string }): DraftStep =>
  ({ actor: 'SUB_AGENT', questionId: 'q1', code: null, value: null, sentenceSeq: null, sentence: null, action: null, reason: null, ...over });
const sentences = (text: string) => text.split(/(?<=[.])\s+/).map((t, i) => ({ seq: i + 1, text: t }));
const draft = (text: string, marks: unknown[] = []) => ({ text, aiText: text, edited: false, charCount: text.length, overBy: 0, sentences: sentences(text), marks, blanks: [], source: 'LLM' });
const question = (over: Record<string, unknown> = {}) => ({
  id: 'q1', seq: 1, text: '팀으로 함께 성과를 낸 경험을 적어 주세요', charLimit: 700, theme: 'COLLABORATION',
  primaryCard: { id: 'card_03', title: '토큰 만료 자동 재발급', kind: 'TECH' }, primaryCardDeleted: false, supportingCard: null,
  assignedBy: 'SUB_AGENT', pickReason: null, considered: [], materials: [], asks: [], draft: null, writing: null, ...over,
});
const inferredStep = step({ seq: 2, kind: 'MARKED', code: 'INFERRED', sentenceSeq: 3, sentence: LEARNED });

const req = (method: string, path: string, body: unknown = {}) =>
  handle(method, path, new URLSearchParams(), body as Record<string, unknown>, true, {});
let shift = 0;
const realNow = Date.now;
const advance = (sec: number) => { shift += sec * 1000; Date.now = () => realNow() + shift; };

/** 목으로 만들기 → 카드 고르기 → 질문 → 초안까지 끝낸 자소서 */
function draftedFromMock() {
  const created = CoverLetter.parse(req('POST', '/cover-letters', { jobRole: '백엔드 개발', memo: null, questions: [
    { text: '팀으로 함께 성과를 낸 경험을 적어 주세요', charLimit: null },
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
    log.push(`${method} ${path}${method === 'PATCH' ? ` ${body.text}` : ''}`);
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
const inferredCard = () => document.querySelector<HTMLElement>('[data-note-card^="i:"]');
const area = () => screen.getByLabelText('1번 문항 초안') as HTMLTextAreaElement;

beforeEach(() => { resetDb(); shift = 0; Date.now = realNow; });
afterEach(() => { cleanup(); Date.now = realNow; vi.restoreAllMocks(); });

describe('추론 메모와 점검표', () => {
  it('INFERRED 표시는 같은 문장의 다른 검사와 따로 추론 메모가 되고, 점검표 추론 칸에 확인 필요로 센다', () => {
    const q = question({ draft: draft(TEXT, [{ sentenceSeq: 3, code: 'UNSOURCED_TERM', value: '습관' }, { sentenceSeq: 3, code: 'INFERRED', value: null }]) });
    const notes = buildQuestionNotes(q, [step({ seq: 1, kind: 'WROTE' }), inferredStep], TEXT, 'edit');
    const inferred = notes.threads.find((t) => t.status === 'inferred')!;
    expect(inferred).toMatchObject({ sentence: LEARNED, group: 'inference', sentenceSeq: 3 });
    expect(inferred.items[0]).toMatchObject({ actor: 'SUB_AGENT', text: '추론한 문장이라 확인 필요 표시를 남겼어요' });
    expect(notes.threads.find((t) => t.group === 'evidence')?.status).toBe('marked');
    expect(STATUS_LABEL.inferred).toBe('AI 추론 · 확인 필요');
    expect(cellLabel(notes.checklist.inference)).toBe('확인 필요 1');
    expect(cellLabel(notes.checklist.evidence)).toBe('확인 필요 1');
  });

  it('추론 표시가 없어진(확인한) 문장은 단계가 남아 있어도 메모를 만들지 않는다', () => {
    const notes = buildQuestionNotes(question({ draft: draft(TEXT) }), [inferredStep], TEXT, 'edit');
    expect(notes.threads).toHaveLength(0);
    expect(cellLabel(notes.checklist.inference)).toBe('통과');
  });

  it('추론 문장을 고쳐 원문이 사라지면 고침 · 나 로 바뀌고 확인 필요로 세지 않는다', () => {
    const q = question({ draft: draft(TEXT, [{ sentenceSeq: 3, code: 'INFERRED', value: null }]) });
    const notes = buildQuestionNotes(q, [inferredStep], TEXT.replace(LEARNED, '장애 대응 순서를 문서로 남겼다.'), 'edit');
    expect(notes.threads[0].status).toBe('fixed-user');
    expect(cellLabel(notes.checklist.inference)).toBe('고침 1');
  });

  it('표시만 있고 단계가 없는 옛 응답도 추론 메모를 만들고, 쓰는 동안의 단계 줄은 추론이라고 말한다', () => {
    const notes = buildQuestionNotes(question({ draft: draft(TEXT, [{ sentenceSeq: 3, code: 'INFERRED', value: null }]) }), [], TEXT, 'edit');
    expect(notes.threads[0].items[0].text).toBe('AI가 재료를 바탕으로 추론해 쓴 문장이에요. 맞으면 확인, 다르면 고치거나 빼 주세요.');
    expect(draftLine(inferredStep, [{ id: 'q1', seq: 1 }])?.text).toBe('1번 문항 추론한 문장이라 확인 필요 표시를 남겼어요');
  });
});

describe('빼기의 띄어쓰기', () => {
  it('문장을 빼면 문장 사이 띄어쓰기를 하나만 남기고, 줄의 문장이면 줄바꿈을 겹치지 않는다', () => {
    expect(withoutSentence(TEXT, S2)).toBe(`${S1} ${LEARNED}`);
    expect(withoutSentence(TEXT, S1)).toBe(`${S2} ${LEARNED}`);
    expect(withoutSentence(TEXT, LEARNED)).toBe(`${S1} ${S2}`);
    expect(withoutSentence(`${S1}\n${S2}\n${LEARNED}`, S2)).toBe(`${S1}\n${LEARNED}`);
    expect(withoutSentence(TEXT, '없는 문장.')).toBeNull();
  });
});

describe('목 서버', () => {
  it('초안을 쓰면 배운 점 답이 없는 첫 문항에 추론 문장 하나를 넣고, 작성 AI 가 단 단계와 INFERRED 표시를 함께 내린다', () => {
    const l = draftedFromMock();
    const q = l.questions[0];
    const seq = q.draft!.sentences.find((s) => s.text === MOCK_LEARNED)!.seq;
    expect(q.draft!.marks.filter((m) => m.code === 'INFERRED')).toEqual([{ sentenceSeq: seq, code: 'INFERRED', value: null }]);
    expect(l.steps.draft.filter((s) => s.code === 'INFERRED')).toEqual([expect.objectContaining({ kind: 'MARKED', actor: 'SUB_AGENT', sentenceSeq: seq, sentence: MOCK_LEARNED })]);
  });

  it('DELETE …/marks/{seq} 는 그 문장의 INFERRED 표시만 지우고, 표시가 없거나 다른 표시만 있으면 409 다', () => {
    const l = draftedFromMock();
    const q = l.questions[0];
    const seq = q.draft!.marks.find((m) => m.code === 'INFERRED')!.sentenceSeq!;
    const before = db.coverLetters[0].questions[0].draft.text;
    const path = (n: number) => `/cover-letters/${l.id}/questions/${q.id}/marks/${n}`;
    const removed = req('DELETE', path(seq));
    expect(removed.status).toBe(200);
    expect(CoverLetter.parse(removed.data).questions[0].draft!.marks.some((m) => m.code === 'INFERRED')).toBe(false);
    expect(db.coverLetters[0].questions[0].draft.text).toBe(before);
    expect(req('DELETE', path(seq))).toMatchObject({ status: 409, error: { code: 'INVALID_STATE' } });
    db.coverLetters[0].questions[0].draft.marks = [{ sentenceSeq: 1, code: 'REPEATED', value: null }];
    expect(req('DELETE', path(1)).status).toBe(409);
    expect(db.coverLetters[0].questions[0].draft.marks).toHaveLength(1);
  });
});

describe('⑥ 메모의 확인 · 고치기 · 빼기', () => {
  it('확인을 누르면 표시 지우기 API 를 부르고, 노란 형광펜과 추론 메모가 사라진다', async () => {
    const log: string[] = [];
    stubFetch(log);
    const l = draftedFromMock();
    const seq = l.questions[0].draft!.marks.find((m) => m.code === 'INFERRED')!.sentenceSeq;
    mount(l);
    expect(document.querySelector('.pedit__mirror mark.phl--inferred')?.textContent).toBe(MOCK_LEARNED);
    expect(within(inferredCard()!).getByText('AI 추론 · 확인 필요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^추론\s?확인 필요 1$/ })).toBeEnabled();
    fireEvent.click(within(inferredCard()!).getByRole('button', { name: '확인' }));
    await waitFor(() => expect(document.querySelector('.phl--inferred')).toBeNull());
    expect(inferredCard()).toBeNull();
    expect(log).toContain(`DELETE /cover-letters/${l.id}/questions/${l.questions[0].id}/marks/${seq}`);
    expect(area().value).toContain(MOCK_LEARNED);
  });

  it('고치기를 누르면 글상자에서 그 문장을 골라 둔다', () => {
    const l = draftedFromMock();
    mount(l);
    fireEvent.click(within(inferredCard()!).getByRole('button', { name: '고치기' }));
    const start = area().value.indexOf(MOCK_LEARNED);
    expect(document.activeElement).toBe(area());
    expect([area().selectionStart, area().selectionEnd]).toEqual([start, start + MOCK_LEARNED.length]);
  });

  it('빼기를 누르면 그 문장을 뺀 글을 바로 저장하고, 메모는 고침 · 나 로 바뀐다', async () => {
    const log: string[] = [];
    stubFetch(log);
    const l = draftedFromMock();
    mount(l);
    fireEvent.click(within(inferredCard()!).getByRole('button', { name: '빼기' }));
    expect(area().value).not.toContain(MOCK_LEARNED);
    expect(area().value).not.toMatch(/ {2}/);
    await waitFor(() => expect(log.some((x) => x.startsWith('PATCH '))).toBe(true));
    expect(log.find((x) => x.startsWith('PATCH '))).toContain(area().value);
    await waitFor(() => expect(db.coverLetters[0].questions[0].draft.text).toBe(area().value));
    expect(within(inferredCard()!).getByText('고침 · 나')).toBeInTheDocument();
    expect(within(inferredCard()!).queryByRole('button', { name: '확인' })).toBeNull();
  });

  it('복사 알림은 지금 글에 남은 확인하지 않은 추론 문장 수를 빈칸 수와 함께 알린다', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    mount(draftedFromMock());
    fireEvent.change(area(), { target: { value: `${area().value} ${BLANK_TEXT.RESULT}` } });
    fireEvent.click(screen.getByRole('button', { name: '복사' }));
    expect(await screen.findByText('복사했어요. 확인하지 않은 AI 추론 문장 1개와 빈칸 1개가 함께 복사됐어요.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '전체 복사' }));
    expect(await screen.findByText('모든 문항을 복사했어요. 확인하지 않은 AI 추론 문장 1개와 빈칸 1개가 함께 복사됐어요.')).toBeInTheDocument();
    fireEvent.change(area(), { target: { value: area().value.replace(MOCK_LEARNED, '') } });
    fireEvent.click(screen.getByRole('button', { name: '복사' }));
    expect(await screen.findByText('복사했어요. 아직 채우지 않은 빈칸 1개가 함께 복사됐어요.')).toBeInTheDocument();
  });

  it('쓰는 중(⑤)에는 추론 메모가 보이기만 하고 확인 · 고치기 · 빼기가 없다', () => {
    mount(CoverLetter.parse({
      id: 'cl_1', stage: 'DRAFTING', pollAfterMs: 1500, jobRole: '백엔드 개발', createdAt: 't', updatedAt: 't',
      questions: [question({ writing: { text: TEXT, round: 0 } })], steps: { assign: [], draft: [step({ seq: 1, kind: 'WROTE' }), inferredStep] },
    }));
    expect(within(inferredCard()!).getByText('AI 추론 · 확인 필요')).toBeInTheDocument();
    expect(within(inferredCard()!).queryAllByRole('button')).toHaveLength(0);
  });
});

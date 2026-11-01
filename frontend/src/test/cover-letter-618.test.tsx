import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { CoverLetter, coverLetterKeys, type DraftStep } from '@/api/coverLetters';
import { keys } from '@/api/keys';
import { CardSummary } from '@/api/schemas';
import { CoverLetterPage } from '@/features/coverLetters/CoverLetterPage';
import { draftLine } from '@/lib/coverLetterCopy';
import { buildAssignNotes, buildQuestionNotes, cellLabel } from '@/lib/coverLetterNotes';
import { DEL_MS, advanceLive, isSettled, liveFrom, locateSentence, sentenceDiff, shownText, splitSentences, type LiveState } from '@/lib/draftPaper';
import { BLANK_TEXT } from '@/lib/draftText';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

// ───────────────────────────── 재료 ─────────────────────────────
const S1 = '장애를 30% 줄인 팀이라서.';
const S2 = '액세스 토큰이 만료되면 사용자가 다시 로그인해야 했다.';
const S3 = '리프레시 토큰으로 자동 재발급하는 인터셉터를 추가했다.';
const TEXT = [S1, S2, S3].join(' ');
const S2B = '만료 직전 갱신으로 바꿔 경계 조건을 줄였다.';
const TEXT_B = [S1, S2B, S3].join(' ');

const step = (over: Partial<DraftStep> & { seq: number; kind: string }): DraftStep =>
  ({ actor: 'SUB_AGENT', questionId: 'q1', code: null, value: null, sentenceSeq: null, sentence: null, action: null, reason: null, ...over });
const sentences = (text: string) => text.split(/(?<=[.])\s+/).map((t, i) => ({ seq: i + 1, text: t }));
const question = (over: Record<string, unknown> = {}) => ({
  id: 'q1', seq: 1, text: '지원 동기와 입사 후 포부를 적어 주세요', charLimit: 700, theme: 'MOTIVATION',
  primaryCard: { id: 'card_03', title: '토큰 만료 자동 재발급', kind: 'TECH' }, primaryCardDeleted: false, supportingCard: null,
  assignedBy: 'SUB_AGENT', pickReason: null, considered: [], materials: [], asks: [], draft: null, writing: null, ...over,
});
const draft = (text: string, marks: unknown[] = []) => ({ text, aiText: text, edited: false, charCount: text.length, overBy: 0, sentences: sentences(text), marks, blanks: [], source: 'LLM' });
const letter = (over: Record<string, unknown> = {}) => CoverLetter.parse({
  id: 'cl_1', stage: 'DRAFTING', pollAfterMs: 1500, jobRole: '백엔드 개발', createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z',
  questions: [question()], steps: { assign: [], draft: [] }, ...over,
});

const req = (method: string, path: string, body: unknown = {}, headers: Record<string, string> = {}) =>
  handle(method, path, new URLSearchParams(), body as Record<string, unknown>, true, headers);
let shift = 0;
const realNow = Date.now;
const advance = (sec: number) => { shift += sec * 1000; Date.now = () => realNow() + shift; };
/** 만들기 → 카드 고르기 → 질문까지 끝난 자소서(목) */
function askedLetter() {
  const created = CoverLetter.parse(req('POST', '/cover-letters', { jobRole: '백엔드 개발', memo: null, questions: [
    { text: '지원 동기와 입사 후 포부를 적어 주세요', charLimit: null }, { text: '의견 차이를 조율해 해결한 경험을 적어 주세요', charLimit: 1000 },
  ] }).data);
  advance(7);
  req('POST', `/cover-letters/${created.id}/asks`);
  advance(5);
  return CoverLetter.parse(req('GET', `/cover-letters/${created.id}`).data);
}

type Gate = { holdPut?: Promise<void>; failGet?: boolean; log?: string[] };
/** fetch 를 목 서버로 보낸다. 답 저장(PUT)을 멈춰 두거나 조회(GET)를 500 으로 떨어뜨릴 수 있다 */
function stubFetch(gate: Gate = {}) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (resource: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(typeof resource === 'string' ? resource : resource instanceof URL ? resource.toString() : resource.url, 'http://localhost');
    const path = url.pathname.replace(/^\/api/, '');
    const method = (init?.method ?? 'GET').toUpperCase();
    gate.log?.push(`${method} ${path}`);
    if (method === 'PUT' && gate.holdPut) await gate.holdPut;
    if (method === 'GET' && gate.failGet) return new Response(JSON.stringify({ data: null, error: { code: 'INTERNAL', message: '서버 오류' } }), { status: 500 });
    const headers: Record<string, string> = {};
    new Headers(init?.headers).forEach((v, k) => { headers[k.toLowerCase()] = v; });
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : {};
    const r = handle(method, path, url.searchParams, body, true, headers);
    return new Response(JSON.stringify({ data: r.data, error: r.error }), { status: r.status, headers: { 'Content-Type': 'application/json' } });
  });
}

function mount(l: CoverLetter, staleTime: number = Infinity) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime } } });
  client.setQueryData(coverLetterKeys.one(l.id), l);
  client.setQueryData(keys.cards, (req('GET', '/cards').data as unknown[]).map((c) => CardSummary.parse(c)));
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/cover-letters/${l.id}`]}>
        <Routes><Route path="/cover-letters/:coverLetterId" element={<CoverLetterPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return client;
}
const reduceMotion = (on: boolean) => {
  window.matchMedia = vi.fn().mockImplementation((q: string) => ({ matches: on && q.includes('reduced-motion'), media: q, addEventListener: vi.fn(), removeEventListener: vi.fn() })) as unknown as typeof window.matchMedia;
};

beforeEach(() => { resetDb(); shift = 0; Date.now = realNow; });
afterEach(() => { cleanup(); Date.now = realNow; vi.restoreAllMocks(); delete (window as { matchMedia?: unknown }).matchMedia; });

// ───────────────────────────── 계약 ─────────────────────────────
describe('계약 읽기', () => {
  it('writing 과 단계의 sentence 가 없는 옛 응답은 null 로 읽는다', () => {
    const l = CoverLetter.parse({ id: 'x', stage: 'DRAFTING', jobRole: 'a', createdAt: 't', updatedAt: 't',
      questions: [{ id: 'q', seq: 1, text: 't', charLimit: 700 }], steps: { assign: [], draft: [{ seq: 1, actor: 'CODE', kind: 'CHECK_FAILED', questionId: 'q' }] } });
    expect(l.questions[0].writing).toBeNull();
    expect(l.steps.draft[0].sentence).toBeNull();
    expect(CoverLetter.parse({ ...l, questions: [{ ...l.questions[0], writing: { text: '글', round: 2 } }] }).questions[0].writing).toEqual({ text: '글', round: 2 });
  });
});

// ───────────────────────────── 종이 위의 글 ─────────────────────────────
describe('종이 위의 글 계산', () => {
  it('고쳐 쓴 글은 문장 단위로 그대로·지움·더함으로 나뉘고, 되돌림은 거꾸로 나온다', () => {
    expect(sentenceDiff(TEXT, TEXT_B).map((o) => `${o.kind}:${o.text.trim()}`)).toEqual([`keep:${S1}`, `del:${S2}`, `add:${S2B}`, `keep:${S3}`]);
    expect(sentenceDiff(TEXT_B, TEXT).map((o) => o.kind)).toEqual(['keep', 'del', 'add', 'keep']);
  });

  it('걸린 문장은 글로 찾고, 200자로 잘린 문장은 앞부분으로 찾아 문장 끝까지 긋는다', () => {
    expect(locateSentence(TEXT, S2)).toEqual({ start: TEXT.indexOf(S2), end: TEXT.indexOf(S2) + S2.length });
    expect(locateSentence(TEXT, '없는 문장.')).toBeNull();
    const long = `${'가'.repeat(230)}.`;
    const text = `앞 문장. ${long} 뒤 문장.`;
    expect(locateSentence(text, long.slice(0, 200))).toEqual({ start: 6, end: 6 + long.length });
    expect(locateSentence(`${S1} ${S1}`, S1)?.start).toBe(0);
  });

  it('처음 받은 글은 바로 보이고, 늘어난 글은 다음 조회 전에 다 따라잡는 속도로 한 글자씩 보인다', () => {
    const first = liveFrom(null, S1, 0);
    expect(shownText(first)).toBe(S1);
    expect(isSettled(first)).toBe(true);
    // 한 번에 문장 여러 개(약 150자)가 와도 다음 조회(1.5초의 8할 = 1.2초) 전에 다 따라잡는다
    const more = `${S1} ${`${S2} `.repeat(5).trim()}`;
    let s = liveFrom(first, more, 1000);
    expect(shownText(s)).toBe(S1);
    s = advanceLive(s, 1300, 300, 1200);
    expect(shownText(s).length).toBeGreaterThan(S1.length);
    expect(shownText(s).length).toBeLessThan(more.length);
    for (let t = 1400; t <= 1900; t += 100) s = advanceLive(s, t, 100, 1200);
    // 시간의 3/4 가 지나면 글도 3/4 넘게 보여야 한다(최소 속도로만 치면 27자뿐)
    expect(shownText(s).length - S1.length).toBeGreaterThanOrEqual(100);
    for (let t = 2000; t <= 2200; t += 100) s = advanceLive(s, t, 100, 1200);
    expect(shownText(s)).toBe(more);
    expect(isSettled(s)).toBe(true);
  });

  it('고쳐 쓴 글은 지운 문장이 취소선으로 잠깐 남았다 사라지고 새 문장을 다시 친다, 움직임을 줄이면 바로 바뀐다', () => {
    const before = liveFrom(null, TEXT, 0);
    const s = liveFrom(before, TEXT_B, 1000);
    expect(s.segs.map((x) => x.kind)).toEqual(['keep', 'del', 'add', 'keep']);
    expect(s.segs[1].until).toBe(1000 + DEL_MS);
    expect(shownText(s)).toBe(`${S1} ${S3}`);
    const after = advanceLive(s, 1000 + DEL_MS + 1, 16, 1200);
    expect(after.segs.some((x) => x.kind === 'del')).toBe(false);
    expect(shownText(liveFrom(before, TEXT_B, 1000, true))).toBe(TEXT_B);
  });

  it('다 치기 전에 고쳐 쓴 글이 와도 그대로 둔 문장은 친 만큼만 보이고, 지운 문장은 보였던 만큼만 긋는다', () => {
    let s = liveFrom(liveFrom(null, S1, 0), TEXT, 0);
    s = advanceLive(s, 100, 100, 1200);
    const typed = shownText(s).length - S1.length;
    expect(typed).toBeGreaterThan(0);
    expect(typed).toBeLessThan(S2.length);
    const next = liveFrom(s, TEXT_B, 100);
    expect(next.segs.map((x) => [x.kind, x.shown])).toEqual([['keep', S1.length], ['del', typed], ['add', 0], ['keep', 0]]);
    expect(next.segs[1].text).toBe(` ${S2}`.slice(0, typed));
    expect(shownText(next)).toBe(S1);
  });

  it('문장 끝은 AI 처럼 … 와 줄바꿈에서도 끊는다', () => {
    expect(splitSentences('그런데… 다음 문장!\n[결과: 적어 주세요]')).toEqual(['그런데…', ' 다음 문장!', '\n[결과: 적어 주세요]']);
    const long = `${'가'.repeat(230)}…`;
    expect(locateSentence(`${long} 뒤 문장.`, long.slice(0, 200))).toEqual({ start: 0, end: long.length });
    expect(locateSentence(`${'가'.repeat(230)}\n다음 줄`, long.slice(0, 200))).toEqual({ start: 0, end: 230 });
  });

  it('잘린 문장은 200번째 글자가 공백이어도 잘린 것으로 보고 밑줄을 문장 끝까지 긋는다', () => {
    const long = `${'가'.repeat(199)} ${'나'.repeat(30)}.`;
    const clipped = long.slice(0, 200);
    expect(clipped.trim().length).toBe(199);
    expect(locateSentence(`앞 문장. ${long} 뒤 문장.`, clipped)).toEqual({ start: 6, end: 6 + long.length });
  });

  it('목은 첫 초안을 문장마다 늘리고 고쳐 쓴 글은 다 쓴 글로 한 번에(되돌림도 같은 바퀴로) 보내며, 화면은 바뀐 문장만 지우고 다시 친다', () => {
    const l = askedLetter();
    const q1 = l.questions[0];
    req('PUT', `/cover-letters/${l.id}/questions/${q1.id}/answers`, { answers: [{ item: 'REASON', text: '장애를 30% 줄인 팀이라서', skipped: false }] });
    req('POST', `/cover-letters/${l.id}/drafts`);
    const writes: Record<string, { text: string; round: number }[]> = {};
    for (let i = 0; i < 90; i++) {
      for (const q of CoverLetter.parse(req('GET', `/cover-letters/${l.id}`).data).questions) {
        const list = (writes[q.id] ??= []);
        const last = list[list.length - 1];
        if (q.writing && (!last || last.text !== q.writing.text || last.round !== q.writing.round)) list.push(q.writing);
      }
      advance(0.1);
    }
    for (const list of Object.values(writes)) {
      const first = list.filter((w) => w.round === 0);
      expect(first.length).toBeGreaterThan(1);
      first.slice(1).forEach((w, i) => expect(w.text.startsWith(first[i].text)).toBe(true));
      list.slice(1).forEach((w, i) => {
        expect(w.round).toBeGreaterThanOrEqual(list[i].round);
        if (w.round > 0) expect(splitSentences(w.text)).toHaveLength(splitSentences(list[i].text).length);
      });
    }
    const mine = writes[q1.id];
    expect(mine.some((w, i) => i > 0 && w.round > 0 && w.round === mine[i - 1].round)).toBe(true);
    // 받은 차례대로 화면에 넣으면, 고쳐 쓴 글마다 바뀐 문장 하나만 긋고 다시 치고 나머지는 그대로 둔다
    for (const list of Object.values(writes)) {
      let state: LiveState = liveFrom(null, list[0].text, 0);
      let now = 0;
      for (const w of list.slice(1)) {
        for (let k = 0; k < 200 && !(isSettled(state) && state.segs.every((x) => x.kind !== 'del')); k++) state = advanceLive(state, (now += 16), 16, 1200);
        const before = state.target;
        state = liveFrom(state, w.text, now);
        if (w.text.startsWith(before)) continue;
        const kinds = state.segs.map((x) => x.kind);
        expect(kinds.filter((k) => k === 'del')).toHaveLength(1);
        expect(kinds.filter((k) => k === 'add')).toHaveLength(1);
        expect(kinds.filter((k) => k === 'keep')).toHaveLength(splitSentences(w.text).length - 1);
      }
    }
  });
});

// ───────────────────────────── 메모·점검표 ─────────────────────────────
describe('여백 메모와 점검표', () => {
  const q = question();
  it('검사기가 걸고 작성 AI 가 고쳐 쓰면 그 문장 메모는 고침, 점검표는 고침 1', () => {
    const steps = [step({ seq: 1, kind: 'WROTE' }), step({ seq: 2, actor: 'CODE', kind: 'CHECK_FAILED', code: 'UNSOURCED_NUMBER', value: '30%', sentenceSeq: 1, sentence: S1 })];
    const open = buildQuestionNotes(q, steps, TEXT, 'live');
    expect(open.threads[0]).toMatchObject({ sentence: S1, value: '30%', group: 'evidence', status: 'open' });
    expect(open.threads[0].items[0]).toMatchObject({ actor: 'CODE', text: "'30%'는 카드에 없는 수치예요" });
    expect(cellLabel(open.checklist.evidence)).toBe('검사 중');
    expect(open.phase).toBe('검사 중');
    const fixed = buildQuestionNotes(q, [...steps, step({ seq: 3, kind: 'DECIDED', action: 'FIX', reason: '표현을 바꿔요' }), step({ seq: 4, kind: 'REWROTE', value: '140' })], TEXT, 'live');
    expect(fixed.threads[0].status).toBe('fixed-ai');
    expect(fixed.threads[0].items.map((i) => i.actor)).toEqual(['CODE', 'SUB_AGENT', 'SUB_AGENT']);
    expect(cellLabel(fixed.checklist.evidence)).toBe('고침 1');
  });

  it('확인 필요로 남기면 확인 필요 1, 그대로 두기로 하면 통과, 검토 AI 가 돌려보낸 문장을 고치면 중복 고침 1', () => {
    const marked = buildQuestionNotes(q, [step({ seq: 1, kind: 'MARKED', code: 'UNSOURCED_NUMBER', value: '30%', sentenceSeq: 1, sentence: S1 })], TEXT, 'live');
    expect(marked.threads[0].status).toBe('marked');
    expect(cellLabel(marked.checklist.evidence)).toBe('확인 필요 1');
    const kept = buildQuestionNotes(q, [step({ seq: 1, actor: 'CODE', kind: 'CHECK_FAILED', code: 'UNDER_LENGTH', value: '371' }), step({ seq: 2, kind: 'DECIDED', action: 'KEEP', reason: '더 쓸 사실이 없어요' })], TEXT, 'live');
    expect(kept.questionNotes[0]).toMatchObject({ status: 'kept', group: 'length' });
    expect(kept.questionNotes[0].items[1].reason).toBe('더 쓸 사실이 없어요');
    expect(cellLabel(kept.checklist.length)).toBe('통과');
    const sentBack = buildQuestionNotes(q, [step({ seq: 1, actor: 'SUPERVISOR', kind: 'SENT_BACK', code: 'REPEATED', sentenceSeq: 2, sentence: S2, reason: '1번 문항과 같은 이야기라서' }), step({ seq: 2, kind: 'REWROTE', value: '150' })], TEXT_B, 'live');
    expect(sentBack.threads[0]).toMatchObject({ group: 'repeat', status: 'fixed-ai' });
    expect(sentBack.threads[0].items[0]).toMatchObject({ actor: 'SUPERVISOR', reason: '1번 문항과 같은 이야기라서' });
    expect(cellLabel(sentBack.checklist.repeat)).toBe('고침 1');
  });

  it("고침 n 은 작성 AI 가 고친 것과 내가 고친 것을 함께 세고, 내가 고친 메모에는 '나' 가 붙는다", () => {
    const steps = [
      step({ seq: 1, actor: 'CODE', kind: 'CHECK_FAILED', code: 'UNSOURCED_TERM', value: 'k8s', sentenceSeq: 2, sentence: S2 }), step({ seq: 2, kind: 'DECIDED', action: 'FIX' }), step({ seq: 3, kind: 'REWROTE' }),
      step({ seq: 4, kind: 'MARKED', code: 'UNSOURCED_NUMBER', value: '30%', sentenceSeq: 1, sentence: S1 }),
    ];
    const withDraft = question({ draft: draft(TEXT_B, [{ sentenceSeq: 1, code: 'UNSOURCED_NUMBER', value: '30%' }]) });
    const untouched = buildQuestionNotes(withDraft, steps, TEXT_B, 'edit');
    expect(cellLabel(untouched.checklist.evidence)).toBe('확인 필요 1 · 고침 1');
    const edited = buildQuestionNotes(withDraft, steps, TEXT_B.replace(S1, '장애를 크게 줄인 팀이라서.'), 'edit');
    const mine = edited.threads.find((t) => t.sentence === S1)!;
    expect(mine.status).toBe('fixed-user');
    expect(mine.items[mine.items.length - 1]).toMatchObject({ actor: 'USER' });
    expect(cellLabel(edited.checklist.evidence)).toBe('고침 2');
  });

  it('다 쓴 뒤에도 열린 메모는 확인 필요로 닫혀, 점검표가 검사 중으로 남지 않는다', () => {
    const steps = [
      step({ seq: 1, kind: 'WROTE', value: '760' }), step({ seq: 2, actor: 'CODE', kind: 'CHECK_FAILED', code: 'OVER_LIMIT', value: '60' }),
      step({ seq: 3, kind: 'REWROTE', value: '720' }), step({ seq: 4, actor: 'CODE', kind: 'CHECK_FAILED', code: 'OVER_LIMIT', value: '20' }),
      step({ seq: 5, actor: 'CODE', kind: 'CHECK_FAILED', code: 'UNSOURCED_TERM', value: 'k8s', sentenceSeq: 2, sentence: S2 }),
    ];
    const notes = buildQuestionNotes(question({ draft: draft(TEXT) }), steps, TEXT, 'edit');
    expect(notes.threads[0].status).toBe('marked');
    expect(notes.questionNotes.find((t) => t.group === 'length')?.status).toBe('marked');
    expect(cellLabel(notes.checklist.evidence)).toBe('확인 필요 1');
    expect(cellLabel(notes.checklist.length)).toBe('확인 필요 1');
  });

  it('되돌린 글에서 걸린 것은 되돌림으로 닫혀 확인 필요로 세지 않는다', () => {
    const tried = '장애를 50% 넘게 줄인 팀이라서.';
    const steps = [
      step({ seq: 1, kind: 'WROTE' }), step({ seq: 2, actor: 'SUPERVISOR', kind: 'SENT_BACK', code: 'UNSUPPORTED_CLAIM', sentenceSeq: 1, sentence: S1, reason: '성과가 카드에 없어서' }),
      step({ seq: 3, kind: 'REWROTE' }), step({ seq: 4, actor: 'CODE', kind: 'CHECK_FAILED', code: 'UNSOURCED_NUMBER', value: '50%', sentenceSeq: 1, sentence: tried }),
      step({ seq: 5, actor: 'CODE', kind: 'CHECK_FAILED', code: 'OVER_LIMIT', value: '12' }), step({ seq: 6, actor: 'CODE', kind: 'REVERTED' }),
      step({ seq: 7, actor: 'CODE', kind: 'MARKED', code: 'UNSUPPORTED_CLAIM', sentenceSeq: 1, sentence: S1 }),
    ];
    const notes = buildQuestionNotes(question({ draft: draft(TEXT, [{ sentenceSeq: 1, code: 'UNSUPPORTED_CLAIM', value: null }]) }), steps, TEXT, 'edit');
    expect(notes.threads.find((t) => t.sentence === tried)?.status).toBe('reverted');
    expect(notes.questionNotes.find((t) => t.group === 'length')?.status).toBe('reverted');
    expect(cellLabel(notes.checklist.evidence)).toBe('확인 필요 1');
    expect(cellLabel(notes.checklist.length)).toBe('통과');
  });

  it('문장에 걸리지 않는 검사는 점검표 칸마다 메모가 따로라, 빠뜨린 빈칸이 글자 수 검사에 덮이지 않는다', () => {
    const steps = [
      step({ seq: 1, kind: 'WROTE' }), step({ seq: 2, actor: 'CODE', kind: 'CHECK_FAILED', code: 'MISSING_BLANK', value: 'LEARNING' }),
      step({ seq: 3, actor: 'CODE', kind: 'CHECK_FAILED', code: 'OVER_LIMIT', value: '30' }),
    ];
    const notes = buildQuestionNotes(q, steps, TEXT, 'live');
    expect(notes.questionNotes.map((t) => t.key)).toEqual(['q:q1:evidence', 'q:q1:length']);
    expect(cellLabel(notes.checklist.evidence)).toBe('검사 중');
    expect(cellLabel(notes.checklist.length)).toBe('검사 중');
  });

  it('200자로 잘린 단계 문장과 전체 문장 표시는 메모 하나로 묶여 점검표에 한 번만 센다', () => {
    const long = `${'가'.repeat(199)} ${'나'.repeat(30)} k8s를 썼다.`;
    const text = `${S1} ${long}`;
    const steps = [step({ seq: 1, actor: 'CODE', kind: 'CHECK_FAILED', code: 'UNSOURCED_TERM', value: 'k8s', sentenceSeq: 2, sentence: long.slice(0, 200) })];
    const notes = buildQuestionNotes(question({ draft: draft(text, [{ sentenceSeq: 2, code: 'UNSOURCED_TERM', value: 'k8s' }]) }), steps, text, 'edit');
    expect(notes.threads).toHaveLength(1);
    expect(notes.threads[0].sentence).toBe(long);
    expect(cellLabel(notes.checklist.evidence)).toBe('확인 필요 1');
  });

  it('점검표 글자는 검사 전 · 검사 중 · 통과 · 확인 필요 n · 고침 n 이다', () => {
    expect(cellLabel({ phase: 'before', marked: 0, fixed: 0 })).toBe('검사 전');
    expect(cellLabel({ phase: 'checking', marked: 0, fixed: 0 })).toBe('검사 중');
    expect(cellLabel({ phase: 'done', marked: 0, fixed: 0 })).toBe('통과');
    expect(cellLabel({ phase: 'done', marked: 2, fixed: 1 })).toBe('확인 필요 2 · 고침 1');
  });

  it('카드 고르기 메모는 고른 카드와 이유를 말하고, 모르는 actor 의 단계는 건너뛴다', () => {
    const picked = { seq: 1, actor: 'SUB_AGENT', kind: 'PICKED', questionId: 'q1', cardId: 'c', cardTitle: '토큰 만료 자동 재발급', reason: '직무와 이어져서' };
    const notes = buildAssignNotes(q, [picked, { ...picked, seq: 2, actor: 'ROBOT' }], true);
    expect(notes.questionNote?.items).toHaveLength(1);
    expect(notes.questionNote?.items[0]).toMatchObject({ actor: 'SUB_AGENT', text: "'토큰 만료 자동 재발급' 카드를 골랐어요", reason: '직무와 이어져서' });
    expect(notes.phase).toBe('카드를 골랐어요');
    expect(buildAssignNotes(q, [{ ...picked, kind: 'NO_FIT' }], true).phase).toBe('맞는 카드가 없어요');
    expect(draftLine({ ...step({ seq: 1, kind: 'WROTE', value: '100' }), actor: 'ROBOT' }, [])).toBeNull();
  });
});

// ───────────────────────────── ⑤ 쓰는 중 ─────────────────────────────
describe('⑤ 종이 위에 쓰는 중', () => {
  it('움직임을 줄이면 글이 바로 다 보이고, 걸린 문장은 칠해지고 여백 메모와 점검표가 붙는다', () => {
    reduceMotion(true);
    mount(letter({ questions: [question({ writing: { text: TEXT, round: 0 } })], steps: { assign: [], draft: [
      step({ seq: 1, kind: 'WROTE', value: '100' }), step({ seq: 2, actor: 'CODE', kind: 'CHECK_FAILED', code: 'UNSOURCED_NUMBER', value: '30%', sentenceSeq: 1, sentence: S1 }),
    ] } }));
    expect(screen.getByText('초안을 쓰고 있어요')).toBeInTheDocument();
    expect(screen.getByText('Q1.')).toBeInTheDocument();
    const mark = document.querySelector('.pq__answer mark[data-note]');
    expect(mark?.textContent).toBe(S1);
    expect(document.querySelector('.pq__answer')?.textContent).toBe(TEXT);
    expect(screen.getByRole('button', { name: /^근거\s?검사 중$/ })).toBeDisabled();
    expect(screen.getByText("'30%'는 카드에 없는 수치예요")).toBeInTheDocument();
    expect(screen.getByText(new RegExp(`^${TEXT.length} / 700자`))).toBeInTheDocument();
  });

  it('이미 있던 글은 바로 보이고 늘어난 글만 따라 치며 커서가 깜빡인다', async () => {
    const client = mount(letter({ questions: [question({ writing: { text: S1, round: 0 } })] }));
    expect(document.querySelector('.pq__answer')?.textContent).toBe(S1);
    expect(document.querySelector('.pcaret')).not.toBeNull();
    client.setQueryData(coverLetterKeys.one('cl_1'), letter({ questions: [question({ writing: { text: `${S1} ${S2}`, round: 0 } })] }));
    await waitFor(() => expect(document.querySelector('.pq__answer')?.textContent?.length).toBeGreaterThan(S1.length));
    expect(document.querySelector('.pq__answer')?.textContent?.length).toBeLessThan(`${S1} ${S2}`.length);
    await waitFor(() => expect(document.querySelector('.pq__answer')?.textContent).toBe(`${S1} ${S2}`), { timeout: 3000 });
  });

  it('첫 초안을 다 쓴(WROTE) 문항은 따라 칠 글이 없으면 커서가 멈춘다', () => {
    mount(letter({ questions: [question({ writing: { text: TEXT, round: 0 } })], steps: { assign: [], draft: [step({ seq: 1, kind: 'WROTE', value: '100' })] } }));
    expect(document.querySelector('.pq__answer')?.textContent).toBe(TEXT);
    expect(document.querySelector('.pcaret')).toBeNull();
  });
});

// ───────────────────────────── ② ③ 카드 고르기 ─────────────────────────────
describe('②·③ 카드 고르기 메모', () => {
  const picked = { seq: 1, actor: 'SUB_AGENT', kind: 'PICKED', questionId: 'q1', cardId: 'card_03', cardTitle: '토큰 만료 자동 재발급', reason: '직무와 이어져서' };
  it('② 는 종이의 문항 옆에 고른 카드 메모가 붙고 상태 글이 바뀐다', () => {
    mount(letter({ stage: 'ASSIGNING', questions: [question({ primaryCard: null, assignedBy: null })], steps: { assign: [picked], draft: [] } }));
    expect(screen.getByText('문항마다 쓸 카드를 고르고 있어요')).toBeInTheDocument();
    expect(screen.getByText("'토큰 만료 자동 재발급' 카드를 골랐어요")).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('카드를 골랐어요');
  });

  it('③ 카드 확인에도 같은 메모가 보인다', () => {
    mount(letter({ stage: 'ASSIGNED', pollAfterMs: null, steps: { assign: [picked], draft: [] } }));
    expect(screen.getByText('문항마다 쓸 카드')).toBeInTheDocument();
    expect(screen.getByText("'토큰 만료 자동 재발급' 카드를 골랐어요")).toBeInTheDocument();
    expect(screen.getByText('직무와 이어져서')).toBeInTheDocument();
  });

  it('카드 바꾸기는 중심 카드를 고르기 전에는 보조 카드를 못 고른다', async () => {
    mount(letter({ stage: 'ASSIGNED', pollAfterMs: null, questions: [question({ primaryCard: null, assignedBy: 'SUB_AGENT' })] }));
    fireEvent.click(screen.getByRole('button', { name: '카드 바꾸기' }));
    const support = await screen.findByRole('radiogroup', { name: '보조 카드' });
    expect(within(support).getAllByRole('radio').every((r) => (r as HTMLButtonElement).disabled)).toBe(true);
    expect(screen.getByText('중심 카드를 먼저 골라 주세요.')).toBeInTheDocument();
    fireEvent.click(within(screen.getByRole('radiogroup', { name: '중심 카드' })).getByRole('radio', { name: /토큰 만료 자동 재발급/ }));
    expect(within(support).getAllByRole('radio').some((r) => !(r as HTMLButtonElement).disabled)).toBe(true);
  });

  it('확정을 푼 카드는 카드 바꾸기의 기본값으로 남지 않는다', async () => {
    mount(letter({ stage: 'ASSIGNED', pollAfterMs: null, questions: [question({ primaryCard: { id: 'card_99', title: '확정 푼 카드', kind: 'TECH' } })] }));
    fireEvent.click(screen.getByRole('button', { name: '카드 바꾸기' }));
    const primary = await screen.findByRole('radiogroup', { name: '중심 카드' });
    expect(within(primary).queryByRole('radio', { checked: true })).toBeNull();
    expect(screen.getByRole('button', { name: '바꾸기' })).toBeEnabled();
  });
});

// ───────────────────────────── ⑥ 고치기 ─────────────────────────────
describe('⑥ 같은 종이에서 고치기', () => {
  const drafted = () => letter({ stage: 'DRAFTED', pollAfterMs: null, questions: [question({ draft: draft(TEXT, [{ sentenceSeq: 1, code: 'UNSOURCED_NUMBER', value: '30%' }]) })],
    steps: { assign: [], draft: [step({ seq: 1, kind: 'MARKED', code: 'UNSOURCED_NUMBER', value: '30%', sentenceSeq: 1, sentence: S1 })] } });

  it('한국어를 조합해 넣어도 글상자 값과 뒤의 칠하기 층이 같은 글이고 밑줄이 유지된다', () => {
    mount(drafted());
    const area = screen.getByLabelText('1번 문항 초안') as HTMLTextAreaElement;
    fireEvent.compositionStart(area);
    fireEvent.change(area, { target: { value: `${TEXT} ㅅ` } });
    fireEvent.compositionUpdate(area, { data: '사' });
    fireEvent.change(area, { target: { value: `${TEXT} 사` } });
    fireEvent.compositionEnd(area, { data: '사' });
    expect(area.value).toBe(`${TEXT} 사`);
    expect(document.querySelector('.pedit__mirror')?.textContent).toBe(`${TEXT} 사​`);
    expect(document.querySelector('.pedit__mirror mark[data-note]')?.textContent).toBe(S1);
  });

  it('걸린 문장을 고쳐 원문이 사라지면 밑줄은 지우고 메모는 고침 · 나 로 남으며 점검표가 고침 1 이 된다', () => {
    mount(drafted());
    expect(screen.getByText('확인 필요')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('1번 문항 초안'), { target: { value: TEXT.replace(S1, '장애를 크게 줄인 팀이라서.') } });
    expect(document.querySelector('.pedit__mirror mark[data-note]')).toBeNull();
    expect(screen.getByText('고침 · 나')).toBeInTheDocument();
    expect(screen.getByText(`원래 문장: ${S1}`)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^근거\s?고침 1$/ })).toBeEnabled();
  });

  it('전체 복사는 화면에 있는 지금 글로 만든다', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    mount(drafted());
    fireEvent.change(screen.getByLabelText('1번 문항 초안'), { target: { value: '아직 저장하지 않은 글.' } });
    fireEvent.click(screen.getByRole('button', { name: '전체 복사' }));
    await waitFor(() => expect(writeText).toHaveBeenCalled());
    expect(writeText.mock.calls[0][0]).toContain('아직 저장하지 않은 글.');
  });

  it('글상자는 폭이 바뀌면 줄바꿈이 달라진 만큼 높이를 다시 잰다', () => {
    const observers: { cb: ResizeObserverCallback; els: Element[] }[] = [];
    vi.stubGlobal('ResizeObserver', class {
      private o: { cb: ResizeObserverCallback; els: Element[] };
      constructor(cb: ResizeObserverCallback) { this.o = { cb, els: [] }; observers.push(this.o); }
      observe(el: Element) { this.o.els.push(el); }
      unobserve() {}
      disconnect() {}
    });
    try {
      mount(drafted());
      const area = screen.getByLabelText('1번 문항 초안') as HTMLTextAreaElement;
      Object.defineProperty(area, 'scrollHeight', { configurable: true, get: () => 321 });
      act(() => {
        for (const o of observers) if (o.els.includes(area)) o.cb([{ contentRect: { width: 480 } } as ResizeObserverEntry], {} as ResizeObserver);
      });
      expect(area.style.height).toBe('321px');
    } finally { vi.unstubAllGlobals(); }
  });

  it('움직임을 줄이면 점검표에서 메모로 갈 때 굴리지 않고 바로 간다', () => {
    reduceMotion(true);
    const scroll = vi.fn();
    Element.prototype.scrollIntoView = scroll;
    try {
      mount(drafted());
      fireEvent.click(screen.getByRole('button', { name: /^근거\s?확인 필요 1$/ }));
      expect(scroll).toHaveBeenCalledWith(expect.objectContaining({ behavior: 'auto' }));
    } finally { delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView; }
  });
});

// ───────────────────────────── ④ 질문 보완 ─────────────────────────────
describe('④ 답 저장', () => {
  it('저장하는 사이에 더 쓴 답은 저장됨으로 보이지 않고, 같은 문항 저장은 앞 저장이 끝난 뒤에 나간다', async () => {
    const l = askedLetter();
    let release!: () => void;
    const log: string[] = [];
    stubFetch({ holdPut: new Promise<void>((r) => { release = r; }), log });
    mount(l);
    const area = screen.getByLabelText('이 회사를 고른 이유는 무엇인가요?');
    fireEvent.change(area, { target: { value: '첫 답' } });
    fireEvent.blur(area);
    await waitFor(() => expect(log.filter((x) => x.startsWith('PUT')).length).toBe(1));
    fireEvent.change(area, { target: { value: '첫 답을 더 씀' } });
    fireEvent.blur(area);
    expect(log.filter((x) => x.startsWith('PUT')).length).toBe(1);
    release();
    await waitFor(() => expect(log.filter((x) => x.startsWith('PUT')).length).toBe(2));
    await waitFor(() => expect(screen.getByText('저장됨')).toBeInTheDocument());
    expect(db.coverLetters[0].questions[0].answers.find((a: { item: string }) => a.item === 'REASON').text).toBe('첫 답을 더 씀');
  });

  it("'초안 쓰기' 는 저장 중에도 눌리고, 누르면 저장이 끝난 뒤 초안을 시작한다", async () => {
    const l = askedLetter();
    let release!: () => void;
    const log: string[] = [];
    stubFetch({ holdPut: new Promise<void>((r) => { release = r; }), log });
    mount(l);
    const area = screen.getByLabelText('이 회사를 고른 이유는 무엇인가요?');
    fireEvent.change(area, { target: { value: '답' } });
    fireEvent.blur(area);
    await waitFor(() => expect(log.some((x) => x.startsWith('PUT'))).toBe(true));
    const button = screen.getByRole('button', { name: '초안 쓰기' });
    expect(button).toBeEnabled();
    fireEvent.click(button);
    expect(log.some((x) => x.startsWith('POST'))).toBe(false);
    release();
    await waitFor(() => expect(log.some((x) => x.endsWith('/drafts'))).toBe(true));
    expect(log.findIndex((x) => x.endsWith('/drafts'))).toBeGreaterThan(log.findIndex((x) => x.startsWith('PUT')));
  });

  it("'초안 쓰기' 는 저장을 기다리는 동안 누른 것이 보이고, 두 번 눌러도 초안 쓰기는 한 번만 보낸다", async () => {
    const l = askedLetter();
    let release!: () => void;
    const log: string[] = [];
    stubFetch({ holdPut: new Promise<void>((r) => { release = r; }), log });
    mount(l);
    const area = screen.getByLabelText('이 회사를 고른 이유는 무엇인가요?');
    fireEvent.change(area, { target: { value: '답' } });
    fireEvent.blur(area);
    await waitFor(() => expect(log.some((x) => x.startsWith('PUT'))).toBe(true));
    const button = screen.getByRole('button', { name: '초안 쓰기' });
    fireEvent.click(button);
    fireEvent.click(button);
    expect(button).toHaveAttribute('aria-busy', 'true');
    release();
    await waitFor(() => expect(log.some((x) => x.endsWith('/drafts'))).toBe(true));
    await new Promise((r) => setTimeout(r, 50));
    expect(log.filter((x) => x.endsWith('/drafts'))).toHaveLength(1);
  });

  it('글이 남아 있는 필수 재료라도 넘기면 비운 것으로 센다', () => {
    const l = askedLetter();
    stubFetch();
    mount(l);
    // 비운 필수 재료가 0개면 문구가 '답을 다 했으면 초안을 써요' 로 바뀐다
    const missingNow = () => { const m = screen.queryByText(/꼭 필요한 재료 \d+개/); return m ? Number(m.textContent!.match(/\d+/)![0]) : 0; };
    const before = missingNow();
    expect(before).toBeGreaterThan(0);
    const area = screen.getByLabelText('이 회사를 고른 이유는 무엇인가요?');
    fireEvent.change(area, { target: { value: '답' } });
    expect(missingNow()).toBe(before - 1);
    fireEvent.click(within(area.closest('li')!).getByRole('button', { name: '넘기기' }));
    expect(missingNow()).toBe(before);
  });

  it('화면의 빈칸 문구는 서버(DraftText.java)와 글자 그대로 같다', () => {
    const java = readFileSync(resolve(process.cwd(), '../backend/src/main/java/com/gitory/backend/coverletter/domain/DraftText.java'), 'utf-8');
    const server = Object.fromEntries([...java.matchAll(/MaterialItem\.(\w+), "([^"]+)"/g)].map((m) => [m[1], m[2]]));
    expect(server).toEqual(BLANK_TEXT);
  });
});

// ───────────────────────────── 조회 실패 ─────────────────────────────
describe('잠깐의 조회 실패', () => {
  it('받아 둔 자소서가 있으면 조회가 실패해도 화면을 유지한다', async () => {
    const spy = stubFetch({ failGet: true });
    mount(letter({ stage: 'ASSIGNED', pollAfterMs: null }), 0);
    await waitFor(() => expect(spy).toHaveBeenCalled());
    await waitFor(() => expect(spy.mock.results.length).toBeGreaterThan(0));
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.getByText('문항마다 쓸 카드')).toBeInTheDocument();
    expect(screen.queryByText('자소서를 불러오지 못했어요')).not.toBeInTheDocument();
  });
});

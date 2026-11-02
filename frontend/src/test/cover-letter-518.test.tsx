import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CoverLetter, coverLetterKeys } from '@/api/coverLetters';
import { CoverLetterPage } from '@/features/coverLetters/CoverLetterPage';
import { answersFor } from '@/features/coverLetters/AnswerQuestions';
import { checkMessage, draftLine } from '@/lib/coverLetterCopy';
import { BLANK_TEXT, blanksIn, countableLength, moreToWrite } from '@/lib/draftText';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';
import { connectMockServer } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

const req = (method: string, path: string, body: unknown = {}) =>
  handle(method, path, new URLSearchParams(), body as Record<string, unknown>, true, {});
const parse = (r: { data: unknown }) => CoverLetter.parse(r.data);
/** Mock 진행은 경과 시간으로 정해진다 — 시계를 앞으로 민다(누적) */
let shift = 0;
const realNow = Date.now;
const advance = (sec: number) => { shift += sec * 1000; Date.now = () => realNow() + shift; };

/** 만들기 → 카드 고르기 → 질문까지 끝난 자소서 */
function askedLetter() {
  const created = parse(req('POST', '/cover-letters', { jobRole: '백엔드 개발', memo: null, questions: [
    { text: '지원 동기와 입사 후 포부를 적어 주세요', charLimit: null }, { text: '의견 차이를 조율해 해결한 경험을 적어 주세요', charLimit: 1000 },
  ] }));
  advance(7);
  parse(req('POST', `/cover-letters/${created.id}/asks`));
  advance(5);
  return parse(req('GET', `/cover-letters/${created.id}`));
}

function mount(letter: CoverLetter) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(coverLetterKeys.one(letter.id), letter);
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/cover-letters/${letter.id}`]}>
        <Routes><Route path="/cover-letters/:coverLetterId" element={<CoverLetterPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return client;
}

beforeEach(() => { resetDb(); shift = 0; Date.now = realNow; });
afterEach(() => { cleanup(); Date.now = realNow; vi.unstubAllGlobals(); });

describe('초안 글 세기 — 서버 DraftText 와 같은 기준', () => {
  it('항목 빈칸과 더 쓸 거리는 세지 않고, 이모지는 한 글자로 센다', () => {
    const text = `좋아요😀 ${BLANK_TEXT.LEARNING} [더 쓸 거리: 맡은 역할을 적어 보세요]`;
    expect(countableLength(text)).toBe(Array.from('좋아요😀  ').length);
    expect(blanksIn(text)).toEqual(['LEARNING']);
    expect(moreToWrite(text)).toEqual(['맡은 역할을 적어 보세요']);
    expect(blanksIn('[배운 점: 직접 바꾼 문구]')).toEqual([]); // 문구가 다르면 빈칸이 아니다(사용자가 채운 것)
  });
});

describe('⑤ AI 가 한 일 문장', () => {
  const qs = [{ id: 'q1', seq: 1 }];
  const step = (over: Record<string, unknown>) => ({ seq: 1, actor: 'SUB_AGENT', kind: 'WROTE', questionId: 'q1', code: null, value: null, sentenceSeq: null, action: null, reason: null, ...over });
  it('쓰기, 검사, 고칠지 정하기, 감독 검토를 서버 값으로 말한다', () => {
    expect(draftLine(step({ value: '642' }), qs)?.text).toBe('1번 문항 초안을 썼어요(642자)');
    expect(draftLine(step({ kind: 'CHECK_FAILED', code: 'UNSOURCED_NUMBER', value: '30%' }), qs)?.text).toBe("1번 문항 검사: '30%'는 카드에 없는 수치예요");
    expect(draftLine(step({ kind: 'DECIDED', action: 'KEEP', reason: "'k8s'와 같은 뜻" }), qs)).toMatchObject({ text: '1번 문항 그대로 두기로 했어요', reason: "'k8s'와 같은 뜻" });
    expect(draftLine(step({ kind: 'REVIEWED', questionId: null, actor: 'SUPERVISOR', value: '2' }), qs)?.text).toBe('모든 문항을 함께 읽고 2문장을 다시 보게 했어요');
    expect(draftLine(step({ kind: 'SENT_BACK', sentenceSeq: 3, code: 'REPEATED' }), qs)).toMatchObject({ text: '1번 문항 3번째 문장을 다시 쓰게 했어요', reason: '다른 문항과 같은 이야기예요' });
    expect(draftLine(step({ kind: 'SOMETHING_NEW' }), qs)).toBeNull();
    expect(checkMessage('MISSING_BLANK', 'LEARNING')).toBe("빈칸 '배운 점'을 빠뜨렸어요");
  });
});

describe('④ 답 보내기', () => {
  it('넘긴 질문은 text null, 비운 질문은 빼고, 손대지 않은 지난 답도 다시 보낸다', () => {
    const q = { asks: [
      { item: 'RESULT', question: '?', templates: [], required: true, answer: null, skipped: false },
      { item: 'REASON', question: '?', templates: [], required: true, answer: null, skipped: false },
      { item: 'LEARNING', question: null, templates: [], required: false, answer: { text: '지난 답', reused: true }, skipped: false },
    ] };
    expect(answersFor(q, { RESULT: { text: '', skipped: true }, REASON: { text: '  ', skipped: false } })).toEqual([
      { item: 'RESULT', text: null, skipped: true },
      { item: 'LEARNING', text: '지난 답', skipped: false },
    ]);
  });
});

describe('Mock 서버 — 질문, 답, 초안, 고치기', () => {
  it('답 저장은 ASKED 에서만, 질문에 없는 항목과 두 번은 400, 넘기면 skipped', () => {
    const l = askedLetter();
    expect(l.stage).toBe('ASKED');
    const q = l.questions[0];
    const path = `/cover-letters/${l.id}/questions/${q.id}/answers`;
    expect(req('PUT', path, { answers: [{ item: 'JOB_LINK', text: 'x', skipped: false }] }).status).toBe(400);
    const item = q.asks[0].item;
    expect(req('PUT', path, { answers: [{ item, text: 'a', skipped: false }, { item, text: 'b', skipped: false }] }).status).toBe(400);
    expect(req('PUT', path, { answers: [{ item, text: '   ', skipped: false }] }).status).toBe(400);
    const saved = parse(req('PUT', path, { answers: [{ item, text: null, skipped: true }] }));
    expect(saved.questions[0].asks[0]).toMatchObject({ skipped: true, answer: null });
  });

  it('초안을 쓰면 줄이 쌓이고, 끝나면 필수 재료를 비운 자리에 빈칸이 남는다. 고치면 다시 센다', () => {
    const l = askedLetter();
    const drafting = parse(req('POST', `/cover-letters/${l.id}/drafts`));
    expect(drafting).toMatchObject({ stage: 'DRAFTING', pollAfterMs: 1500 });
    advance(9);
    const done = parse(req('GET', `/cover-letters/${l.id}`));
    expect(done.stage).toBe('DRAFTED');
    expect(done.steps.draft.map((s) => s.kind)).toContain('REVIEWED');
    const motivation = done.questions[0];
    expect(motivation.draft?.blanks).toContain('REASON'); // 지원 이유를 비웠다
    const edited = parse(req('PATCH', `/cover-letters/${l.id}/questions/${motivation.id}`, { text: '고친 글이에요.' }));
    expect(edited.questions[0].draft).toMatchObject({ text: '고친 글이에요.', edited: true, charCount: 8, blanks: [] });
    expect(edited.questions[0].draft?.aiText).toBe(motivation.draft?.aiText);
    expect(req('PATCH', `/cover-letters/${l.id}/questions/${motivation.id}`, { text: 'x'.repeat(10_001) }).status).toBe(400);
  });

  it('같은 카드로 답한 재료는 다음 자소서에서 묻지 않고 지난 답으로 미리 채운다', () => {
    const l = askedLetter();
    const q = l.questions[1];
    parse(req('PUT', `/cover-letters/${l.id}/questions/${q.id}/answers`, { answers: [{ item: 'LEARNING', text: '기록으로 합의하는 법을 배웠어요', skipped: false }] }));
    req('POST', `/cover-letters/${l.id}/drafts`);
    advance(9);
    req('GET', `/cover-letters/${l.id}`);
    expect(db.cardMaterialAnswers[q.primaryCard!.id]).toMatchObject({ LEARNING: '기록으로 합의하는 법을 배웠어요' });
    const next = askedLetter();
    const reused = next.questions[1].asks.find((a) => a.item === 'LEARNING');
    expect(reused).toMatchObject({ question: null, answer: { text: '기록으로 합의하는 법을 배웠어요', reused: true } });
  });
});

describe('④ ⑥ 화면', () => {
  it('질문마다 필수 표시와 넘기기, 지난 답 제목을 보이고 비운 필수 재료 수를 말한다', () => {
    mount(askedLetter());
    expect(screen.getByText('조금만 더 알려 주세요')).toBeInTheDocument();
    expect(screen.getAllByText('꼭 필요').length).toBeGreaterThan(0);
    expect(screen.getByText(/꼭 필요한 재료 \d+개가 비어 있어요/)).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole('button', { name: '넘기기' })[0]);
    expect(screen.getAllByRole('button', { name: '다시 답하기' })).toHaveLength(1);
  });

  it('초안 화면은 글자 수를 바로 다시 세고, 빈칸과 확인 필요를 알려 주며, 고치면 저장할 수 있다', async () => {
    const l = askedLetter();
    req('PUT', `/cover-letters/${l.id}/questions/${l.questions[0].id}/answers`, { answers: [{ item: 'REASON', text: '장애를 30% 줄인 팀이라서', skipped: false }] });
    req('POST', `/cover-letters/${l.id}/drafts`);
    advance(9);
    mount(parse(req('GET', `/cover-letters/${l.id}`)));
    connectMockServer(); // 저장은 화면 → api() → fetch → 목 라우터 실제 경로로
    expect(screen.getByText('초안이 나왔어요')).toBeInTheDocument();
    expect(screen.getByText("'30%'는 카드에 없는 수치예요")).toBeInTheDocument();
    const area = screen.getByLabelText('2번 문항 초안');
    fireEvent.change(area, { target: { value: `짧은 글 ${BLANK_TEXT.RESULT}` } });
    expect(screen.getByText(/^5 \/ 1,000자/)).toBeInTheDocument();
    expect(screen.getByText(/결과/, { selector: 'b' })).toBeInTheDocument();
    const saveBtns = screen.getAllByRole('button', { name: '저장' });
    expect(saveBtns).toHaveLength(1);
    fireEvent.click(saveBtns[0]);
    await waitFor(() => expect(screen.getAllByRole('button', { name: '저장됨' })).toHaveLength(2));
    expect(db.coverLetters[0].questions[1].draft.text).toBe(`짧은 글 ${BLANK_TEXT.RESULT}`);
  });
});

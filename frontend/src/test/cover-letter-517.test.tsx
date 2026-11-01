import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CoverLetter, coverLetterKeys } from '@/api/coverLetters';
import { keys } from '@/api/keys';
import { CardSummary } from '@/api/schemas';
import { CoverLetterPage } from '@/features/coverLetters/CoverLetterPage';
import { CoverLetterNewPage } from '@/features/coverLetters/CoverLetterNewPage';
import { assignLine, exampleThemes, primaryState } from '@/lib/coverLetterCopy';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

const req = (method: string, path: string, body: unknown = {}, headers: Record<string, string> = {}) =>
  handle(method, path, new URLSearchParams(), body as Record<string, unknown>, true, headers);
const input = { jobRole: '백엔드 개발', memo: null, questions: [{ text: '지원 동기와 입사 후 포부를 적어 주세요', charLimit: null }, { text: '의견 차이를 조율해 해결한 경험을 적어 주세요', charLimit: 1000 }] };
/** Mock 진행은 경과 시간으로 정해진다 — 시계를 앞으로 민다 */
const later = (sec: number) => { const real = Date.now; const base = real(); Date.now = () => base + sec * 1000; return () => { Date.now = real; }; };

const question = (over: Record<string, unknown> = {}) => ({
  id: 'q1', seq: 1, text: '갈등을 조율한 경험', charLimit: 700, theme: 'CONFLICT',
  primaryCard: { id: 'card_04', title: '팀원과 토큰 저장 위치로 갈린 경험', kind: 'QUALITATIVE' }, primaryCardDeleted: false,
  supportingCard: null, assignedBy: 'SUB_AGENT', pickReason: '의견을 맞춘 과정이 분명해서',
  considered: [], materials: ['SITUATION', 'ACTION'], asks: [], draft: null, ...over,
});
const letter = (over: Record<string, unknown> = {}) => CoverLetter.parse({
  id: 'cl_1', stage: 'ASSIGNED', failedStep: null, errorCode: null, retryable: null, pollAfterMs: null,
  jobRole: '백엔드 개발', memo: null, createdAt: '2026-10-31T00:00:00Z', updatedAt: '2026-10-31T00:00:00Z',
  questions: [question()], steps: { assign: [], draft: [] }, ...over,
});

function mount(seed: CoverLetter | null, path = '/cover-letters/cl_1') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  if (seed) client.setQueryData(coverLetterKeys.one(seed.id), seed);
  client.setQueryData(keys.cards, (req('GET', '/cards').data as unknown[]).map((c) => CardSummary.parse(c)));
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/cover-letters/new" element={<CoverLetterNewPage />} />
          <Route path="/cover-letters/:coverLetterId" element={<CoverLetterPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return client;
}

beforeEach(resetDb);
afterEach(cleanup);

describe('자소서 응답 읽기', () => {
  it('아직 없는 칸이나 모르는 값이 와도 멈추지 않는다', () => {
    const l = CoverLetter.parse({
      id: 'x', stage: 'ASSIGNING', jobRole: 'a', createdAt: 't', updatedAt: 't',
      questions: [{ id: 'q', seq: 1, text: 't', charLimit: 700, theme: 'NEW_THEME', materials: ['SITUATION', 'SOMETHING'] }],
    });
    expect(l.steps).toEqual({ assign: [], draft: [] });
    expect(l.retryable).toBeNull();
    expect(l.questions[0]).toMatchObject({ theme: null, primaryCard: null, primaryCardDeleted: false, materials: ['SITUATION'], asks: [], draft: null });
  });
});

describe('화면 문장', () => {
  it('예시 문항은 지원 동기를 먼저, 그다음 내 카드에 많은 유형 3개', () => {
    const { top, more } = exampleThemes([['CONFLICT', 'GROWTH'], ['GROWTH'], null]);
    expect(top).toEqual(['MOTIVATION', 'GROWTH', 'CONFLICT', 'PROBLEM_SOLVING']);
    expect(more).not.toContain('MOTIVATION');
    expect(top.length + more.length).toBe(8);
    expect(exampleThemes([]).top).toEqual(['MOTIVATION', 'PROBLEM_SOLVING', 'COLLABORATION', 'CONFLICT']); // 카드가 없으면 계약 표 순서
  });

  it('AI 가 한 일 줄은 카드 이름으로 말하고, 지운 카드와 모르는 줄은 그에 맞게', () => {
    const qs = [{ id: 'q1', seq: 1 }, { id: 'q2', seq: 2 }];
    const step = (over: Record<string, unknown>) => ({ seq: 1, actor: 'SUB_AGENT', kind: 'PICKED', questionId: 'q2', cardId: 'c', cardTitle: '배포 시간 단축', reason: '직무와 이어져서', ...over });
    expect(assignLine(step({}), qs)).toEqual({ text: "2번 문항: '배포 시간 단축' 카드를 골랐어요", reason: '직무와 이어져서' });
    expect(assignLine(step({ actor: 'SUPERVISOR', kind: 'SWITCHED', questionId: 'q1', cardTitle: null }), qs)?.text).toBe('1번 문항은 지운 카드로 바꿨어요');
    expect(assignLine(step({ actor: 'SUPERVISOR', kind: 'CHECKED', questionId: null }), qs)?.text).toBe('모든 문항을 함께 보고 겹치는 카드를 확인했어요');
    expect(assignLine(step({ actor: 'ROBOT', kind: 'DANCED' }), qs)).toBeNull();
  });

  it('중심 카드 자리 — 카드 있음, 지움, 고르기 실패, 맞는 카드 없음을 가른다', () => {
    const q = (over: Record<string, unknown>) => letter({ questions: [question(over)] }).questions[0];
    expect(primaryState(q({}))).toBe('picked');
    expect(primaryState(q({ primaryCard: null, primaryCardDeleted: true }))).toBe('deleted');
    expect(primaryState(q({ primaryCard: null, assignedBy: 'FAILED' }))).toBe('failed');
    expect(primaryState(q({ primaryCard: null }))).toBe('no-fit');
  });
});

describe('Mock 서버는 계약대로 동작한다', () => {
  it('같은 키는 내용과 상관없이 처음 자소서, 6초 뒤 카드 고르기가 끝나고 줄이 쌓인다', () => {
    const first = req('POST', '/cover-letters', input, { 'idempotency-key': 'k1' });
    expect(first.status).toBe(200);
    const created = CoverLetter.parse(first.data);
    expect(created).toMatchObject({ stage: 'ASSIGNING', pollAfterMs: 1500 });
    expect(created.questions.map((q) => q.charLimit)).toEqual([700, 1000]);
    const again = CoverLetter.parse(req('POST', '/cover-letters', { ...input, jobRole: '다른 직무' }, { 'idempotency-key': 'k1' }).data);
    expect(again.id).toBe(created.id);
    const restore = later(7);
    try {
      const done = CoverLetter.parse(req('GET', `/cover-letters/${created.id}`).data);
      expect(done).toMatchObject({ stage: 'ASSIGNED', pollAfterMs: null });
      expect(done.questions.every((q) => q.primaryCard)).toBe(true);
      expect(done.steps.assign.length).toBeGreaterThan(2);
      expect(done.steps.assign.every((s) => s.cardTitle !== undefined)).toBe(true);
    } finally { restore(); }
  });

  it('확정 카드가 없으면 422, 입력 규칙을 어기면 400', () => {
    expect(req('POST', '/cover-letters', { ...input, questions: [] }).error?.code).toBe('INVALID_REQUEST');
    expect(req('POST', '/cover-letters', { ...input, questions: [{ text: 'a', charLimit: 99 }] }).error?.code).toBe('INVALID_REQUEST');
    db.cards.forEach((c) => { c.status = 'DRAFT'; });
    expect(req('POST', '/cover-letters', input)).toMatchObject({ status: 422, error: { code: 'NO_CONFIRMED_CARDS' } });
  });

  it('카드 바꾸기는 ASSIGNED 에서만, 중심과 보조가 같으면 400, 바꾸면 USER', () => {
    const created = CoverLetter.parse(req('POST', '/cover-letters', input).data);
    const path = `/cover-letters/${created.id}/questions/${created.questions[0].id}/cards`;
    expect(req('PUT', path, { primaryCardId: 'card_03', supportingCardId: null }).status).toBe(409);
    const restore = later(7);
    try {
      expect(req('PUT', path, { primaryCardId: 'card_03', supportingCardId: 'card_03' }).status).toBe(400);
      expect(req('PUT', path, { primaryCardId: 'card_01', supportingCardId: null }).status).toBe(400); // 확정 카드가 아님
      const changed = CoverLetter.parse(req('PUT', path, { primaryCardId: 'card_04', supportingCardId: null }).data);
      expect(changed.questions[0]).toMatchObject({ primaryCard: { id: 'card_04' }, assignedBy: 'USER', pickReason: null });
      const asking = CoverLetter.parse(req('POST', `/cover-letters/${created.id}/asks`).data);
      expect(asking).toMatchObject({ stage: 'ASKING', pollAfterMs: 1500 });
      expect(req('PUT', path, { primaryCardId: 'card_03', supportingCardId: null }).status).toBe(409);
    } finally { restore(); }
  });

  it('배정된 중심 카드를 지우면 primaryCardDeleted 로 알린다', () => {
    const created = CoverLetter.parse(req('POST', '/cover-letters', input).data);
    const restore = later(7);
    try {
      const assigned = CoverLetter.parse(req('GET', `/cover-letters/${created.id}`).data);
      const id = assigned.questions[0].primaryCard!.id;
      db.cards = db.cards.filter((c) => c.id !== id);
      const after = CoverLetter.parse(req('GET', `/cover-letters/${created.id}`).data);
      expect(after.questions[0]).toMatchObject({ primaryCard: null, primaryCardDeleted: true });
    } finally { restore(); }
  });
});

describe('③ 카드 확인 화면', () => {
  it('지운 카드, 고르기 실패, 맞는 카드 없음을 서로 다르게 안내한다', () => {
    mount(letter({ questions: [
      question({ id: 'a', seq: 1, primaryCard: null, primaryCardDeleted: true }),
      question({ id: 'b', seq: 2, primaryCard: null, assignedBy: 'FAILED', pickReason: null }),
      question({ id: 'c', seq: 3, primaryCard: null, assignedBy: 'SUB_AGENT', pickReason: null }),
    ] }));
    expect(screen.getByText('고른 카드가 지워졌어요')).toBeInTheDocument();
    expect(screen.getByText('이 문항은 카드를 고르지 못했어요')).toBeInTheDocument();
    expect(screen.getByText('맞는 카드가 없어요')).toBeInTheDocument();
    expect(screen.getByText(/카드가 없는 문항 3개는 질문으로 재료를 모아요/)).toBeInTheDocument();
  });

  it('고른 카드와 이유, 재료 점검을 보이고 카드 바꾸기에서 같은 카드를 보조로 고르지 못하게 한다', async () => {
    mount(letter());
    expect(screen.getByRole('link', { name: '팀원과 토큰 저장 위치로 갈린 경험' })).toHaveAttribute('href', '/cards/card_04');
    expect(screen.getByText('의견을 맞춘 과정이 분명해서')).toBeInTheDocument();
    expect(screen.getByText('결과').closest('li')).not.toHaveClass('cl-material--have');
    expect(screen.getByText('상황').closest('li')).toHaveClass('cl-material--have');
    fireEvent.click(screen.getByRole('button', { name: '카드 바꾸기' }));
    const support = await screen.findByRole('radiogroup', { name: '보조 카드' });
    expect(within(support).getByRole('radio', { name: /팀원과 토큰 저장 위치로 갈린 경험/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: '바꾸기' })).toBeDisabled(); // 바꾼 게 없으면 저장하지 않는다
  });

  it('실패는 retryable 이 true 일 때만 다시 시도를 연다', () => {
    mount(letter({ stage: 'FAILED', failedStep: 'ASSIGN', errorCode: 'INTERNAL_ERROR', retryable: false }));
    expect(screen.getByText('카드 고르기를 마치지 못했어요')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument();
    cleanup();
    mount(letter({ stage: 'FAILED', failedStep: 'ASSIGN', errorCode: 'AI_UNAVAILABLE', retryable: true }));
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeEnabled();
  });
});

describe('① 입력 화면', () => {
  it('빈 칸과 글자 수 범위를 그 자리에서 알리고 보내지 않는다', () => {
    mount(null, '/cover-letters/new');
    fireEvent.change(screen.getByLabelText('1번 문항 글자 수'), { target: { value: '50' } });
    fireEvent.click(screen.getByRole('button', { name: '카드 고르기 시작' }));
    expect(screen.getByText('지원 직무를 적어 주세요')).toBeInTheDocument();
    expect(screen.getByText('문항을 적어 주세요')).toBeInTheDocument();
    expect((db as { coverLetters: unknown[] }).coverLetters).toHaveLength(0);
  });

  it('예시 문항 버튼은 빈 칸을 먼저 채우고, 다 차 있으면 문항을 더한다', () => {
    mount(null, '/cover-letters/new');
    fireEvent.click(screen.getByRole('button', { name: '지원 동기' }));
    expect(screen.getByLabelText('1번 문항')).toHaveValue('지원 동기와 입사 후 포부를 적어 주세요');
    fireEvent.click(screen.getByRole('button', { name: '갈등 해결' }));
    expect(screen.getByLabelText('2번 문항')).toHaveValue('의견 차이를 조율해 해결한 경험을 적어 주세요');
  });

  it('확정 카드가 없으면 먼저 알리고 시작 버튼을 막는다', async () => {
    db.cards.forEach((c) => { c.status = 'DRAFT'; });
    mount(null, '/cover-letters/new');
    await waitFor(() => expect(screen.getByText('확정한 카드가 없어요')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: '카드 고르기 시작' })).toBeDisabled();
  });
});

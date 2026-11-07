import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { COVER_LETTER_STAGES, CoverLetter, coverLetterKeys, type DraftStep } from '@/api/coverLetters';
import { keys } from '@/api/keys';
import { CardSummary } from '@/api/schemas';
import { CoverLetterNewPage } from '@/features/coverLetters/CoverLetterNewPage';
import { CoverLetterPage } from '@/features/coverLetters/CoverLetterPage';
import * as copy from '@/lib/coverLetterCopy';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

/** #745 단계 표시줄과 만들기·기다림 화면 정리 */
const req = (method: string, path: string) => handle(method, path, new URLSearchParams(), {}, true, {});
const question = (over: Record<string, unknown> = {}) => ({
  id: 'q1', seq: 1, text: '어려운 문제를 해결한 경험을 적어 주세요', charLimit: 700, theme: 'PROBLEM_SOLVING',
  primaryCard: { id: 'card_03', title: '토큰 만료 자동 재발급', kind: 'TECH' }, primaryCardDeleted: false,
  supportingCard: null, assignedBy: 'SUB_AGENT', pickReason: '기술로 문제를 푼 과정이 분명해요.',
  considered: [], materials: ['SITUATION', 'ACTION', 'RESULT'], asks: [], draft: null, writing: null, ...over,
});
const letter = (over: Record<string, unknown> = {}) => CoverLetter.parse({
  id: 'cl_1', stage: 'ASSIGNED', failedStep: null, errorCode: null, retryable: null, pollAfterMs: null,
  jobRole: '백엔드 개발', memo: null, createdAt: '2026-11-03T00:00:00Z', updatedAt: '2026-11-03T00:00:00Z',
  questions: [question()], steps: { assign: [], draft: [] }, ...over,
});
const step = (over: Partial<DraftStep> & { seq: number; kind: string }): DraftStep =>
  ({ actor: 'SUB_AGENT', questionId: 'q1', code: null, value: null, sentenceSeq: null, sentence: null, action: null, reason: null, ...over });

function mount(path: string, seed?: CoverLetter) {
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
}
/** 지금 단계 이름 — 번호(aria-hidden)는 빼고 읽는다 */
const current = () => screen.getByRole('list', { name: '진행 단계' }).querySelector('[aria-current="step"]')?.textContent?.replace(/\d/g, '');

beforeEach(resetDb);
afterEach(cleanup);

describe('단계 표시줄', () => {
  it('새 자소서에는 단계 표시줄이 없고, 카드 고르기 · 재료 확인(카드를 고른 뒤 포함) · 초안(재료 확인이 끝난 뒤 포함)을 가르며, 실패는 실패한 단계에 머문다', () => {
    const expected: Record<string, number> = { ASSIGNING: 2, ASSIGNED: 3, ASKING: 3, ASKED: 4, DRAFTING: 4, DRAFTED: 4 };
    for (const stage of COVER_LETTER_STAGES.filter((s) => s !== 'FAILED')) expect(copy.flowStep(stage, null)).toBe(expected[stage]);
    expect(copy.flowStep('FAILED', 'ASSIGN')).toBe(2);
    expect(copy.flowStep('FAILED', 'ASK')).toBe(3);
    expect(copy.flowStep('FAILED', 'DRAFT')).toBe(4);

    mount('/cover-letters/new');
    expect(screen.queryByRole('list', { name: '진행 단계' })).not.toBeInTheDocument();
    cleanup();
    mount('/cover-letters/cl_1', letter({ stage: 'ASKING', pollAfterMs: 1500 }));
    expect(current()).toBe('재료 확인');
    // 지난 단계는 ✓ 와 함께 '끝남'으로 읽힌다
    expect(screen.getByRole('list', { name: '진행 단계' })).toHaveTextContent('문항 끝남카드 고르기 끝남3재료 확인4초안');
    cleanup();
    mount('/cover-letters/cl_1', letter({ stage: 'FAILED', failedStep: 'DRAFT', retryable: true }));
    expect(current()).toBe('초안');
  });
});

describe('① 새 자소서', () => {
  it('어디에 내나요 · 문항 두 묶음이고, 자주 나오는 문항 칩이 문항 목록보다 위에, 문항 칸은 한 줄로 시작한다', () => {
    mount('/cover-letters/new');
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(['1어디에 내나요', '2문항']);
    expect(screen.getByText('회사가 묻는 문항 문장을 그대로 붙여 넣어요 — 답은 여기에 쓰지 않아요')).toBeInTheDocument();
    const chips = screen.getByRole('group', { name: '자주 나오는 문항' });
    const field = screen.getByLabelText('1번 문항');
    expect(chips.compareDocumentPosition(field) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(field).toHaveAttribute('rows', '1');
    expect(screen.queryByText(/^\d+\/500$/)).not.toBeInTheDocument(); // 문항마다 있던 글자 수는 뺐다
  });

  it("'700자 이내'를 누르면 그 자리에서 숫자 칸이 되고 Enter 는 저장, Esc 는 취소다", () => {
    mount('/cover-letters/new');
    fireEvent.click(screen.getByRole('button', { name: /^1번 문항 답 700자 이내/ }));
    const box = screen.getByLabelText('1번 문항 글자 수');
    fireEvent.change(box, { target: { value: '800' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    expect(screen.getByRole('button', { name: /^1번 문항 답 800자 이내/ })).toHaveTextContent('800자 이내');
    fireEvent.click(screen.getByRole('button', { name: /^1번 문항 답 800자 이내/ }));
    fireEvent.change(screen.getByLabelText('1번 문항 글자 수'), { target: { value: '1200' } });
    fireEvent.keyDown(screen.getByLabelText('1번 문항 글자 수'), { key: 'Escape' });
    expect(screen.getByRole('button', { name: /^1번 문항 답 800자 이내/ })).toBeInTheDocument();
    expect(screen.queryByLabelText('1번 문항 글자 수')).not.toBeInTheDocument();
  });
});

describe('기다리는 화면', () => {
  it("문항마다 상태는 한 줄뿐이고 점검표 칩과 '0 / 700자'가 없으며, 제목 아래에 걸리는 시간을 한 줄로 말한다", () => {
    const q2 = question({ id: 'q2', seq: 2, text: '팀으로 함께 성과를 낸 경험을 적어 주세요', writing: { text: '팀원과 의견이 갈렸습니다.', round: 0 } });
    mount('/cover-letters/cl_1', letter({ stage: 'DRAFTING', pollAfterMs: 1500, questions: [question(), q2],
      steps: { assign: [], draft: [step({ seq: 1, questionId: 'q2', kind: 'WROTE', value: '13' })] } }));
    expect(screen.getByText('보통 1~2분 걸려요')).toBeInTheDocument();
    expect(screen.getAllByRole('status').map((s) => s.textContent)).toEqual(['차례를 기다리는 중', '검사 중']);
    expect(document.querySelector('.pcheck')).toBeNull();
    expect(screen.queryByText(/\/ 700자/)).not.toBeInTheDocument();
    expect(screen.getAllByText('토큰 만료 자동 재발급 카드로 써요', { selector: '.pq__card' })).toHaveLength(2);
  });
});

describe("'AI가' 줄이기", () => {
  it("자소서 문구 모듈의 글에 'AI가' 가 없다", () => {
    const strings: string[] = [];
    const collect = (v: unknown) => {
      if (typeof v === 'string') strings.push(v);
      else if (Array.isArray(v)) v.forEach(collect);
      else if (v && typeof v === 'object') Object.values(v).forEach(collect);
    };
    collect(Object.values(copy));
    expect(strings.length).toBeGreaterThan(50);
    expect(strings.filter((s) => /AI ?가/.test(s))).toEqual([]);
  });
});

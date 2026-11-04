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

/** #745 단계 표시줄과 만들기·카드·기다림·질문 화면 정리 */
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
  it('새 자소서는 문항, 카드 고르기와 확인은 카드, 질문은 질문, 쓰기와 초안은 초안, 실패는 실패한 단계에 머문다', () => {
    const expected: Record<string, number> = { ASSIGNING: 2, ASSIGNED: 2, ASKING: 3, ASKED: 3, DRAFTING: 4, DRAFTED: 4 };
    for (const stage of COVER_LETTER_STAGES.filter((s) => s !== 'FAILED')) expect(copy.flowStep(stage, null)).toBe(expected[stage]);
    expect(copy.flowStep('FAILED', 'ASSIGN')).toBe(2);
    expect(copy.flowStep('FAILED', 'ASK')).toBe(3);
    expect(copy.flowStep('FAILED', 'DRAFT')).toBe(4);

    mount('/cover-letters/new');
    expect(current()).toBe('문항');
    cleanup();
    mount('/cover-letters/cl_1', letter({ stage: 'ASKING', pollAfterMs: 1500 }));
    expect(current()).toBe('질문');
    // 지난 단계는 ✓ 와 함께 '끝남'으로 읽힌다
    expect(screen.getByRole('list', { name: '진행 단계' })).toHaveTextContent('문항 끝남카드 끝남3질문4초안');
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

describe('③ 문항마다 쓸 카드', () => {
  const considered = [
    { cardId: 'card_03', title: '토큰 만료 자동 재발급', note: '결과까지 있어요' },
    { cardId: 'card_02', title: '로그인 세션 오류 수정', note: '결과가 덜 분명해요' },
  ];

  it("고른 이유는 한 줄만 두고 '작성 AI' 같은 꼬리표와 같은 이유를 되풀이하는 메모가 없다", () => {
    const picked = { seq: 1, actor: 'SUB_AGENT', kind: 'PICKED', questionId: 'q1', cardId: 'card_03', cardTitle: '토큰 만료 자동 재발급', reason: '기술로 문제를 푼 과정이 분명해요.' };
    mount('/cover-letters/cl_1', letter({ steps: { assign: [picked], draft: [] } }));
    expect(screen.getAllByText(/기술로 문제를 푼 과정이 분명해요/)).toHaveLength(1);
    expect(screen.queryByText(/작성 AI|AI가/)).not.toBeInTheDocument();
    expect(screen.getByRole('article', { name: '이 경험으로 써요: 토큰 만료 자동 재발급' })).toHaveTextContent('기술');
  });

  it("재료 한 줄은 '재료 3/5 (배운 점, 계기는 다음에 물어요)'이고 빠진 이름만 호박색으로 칠한다", () => {
    mount('/cover-letters/cl_1', letter());
    const line = document.querySelector('.cl-ready');
    expect(line).toHaveTextContent('재료 3/5 (배운 점, 계기는 다음에 물어요)');
    expect(line?.querySelector('.cl-ready__miss')?.textContent).toBe('배운 점, 계기');
    expect(copy.materialLine(['SITUATION', 'RESULT', 'LEARNING', 'TRIGGER'])).toMatchObject({ missing: ['행동'], rest: '은 다음에 물어요' });
    expect(copy.materialLine(['SITUATION', 'ACTION', 'RESULT', 'LEARNING', 'TRIGGER'])).toMatchObject({ have: 5, missing: [], rest: '다 있어요' });
  });

  it("'견준 카드 N장'을 누르면 견준 카드가 펼쳐지고 고른 카드에 '고름'이 붙으며, 다시 누르면 접힌다", () => {
    mount('/cover-letters/cl_1', letter({ questions: [question({ considered })] }));
    const toggle = screen.getByRole('button', { name: '견준 카드 2장' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('link', { name: '로그인 세션 오류 수정' })).not.toBeInTheDocument();
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('link', { name: '로그인 세션 오류 수정' })).toBeInTheDocument();
    expect(document.querySelector('.cl-compared--picked')).toHaveTextContent('토큰 만료 자동 재발급 (고름)');
    fireEvent.click(toggle);
    expect(screen.queryByRole('link', { name: '로그인 세션 오류 수정' })).not.toBeInTheDocument();
  });
});

describe('기다리는 화면', () => {
  it("문항마다 상태는 한 줄뿐이고 점검표 칩과 '0 / 700자'가 없으며, 제목 아래에 걸리는 시간을 한 줄로 말한다", () => {
    const q2 = question({ id: 'q2', seq: 2, text: '팀으로 함께 성과를 낸 경험을 적어 주세요', writing: { text: '팀원과 의견이 갈렸습니다.', round: 0 } });
    mount('/cover-letters/cl_1', letter({ stage: 'DRAFTING', pollAfterMs: 1500, questions: [question(), q2],
      steps: { assign: [], draft: [step({ seq: 1, questionId: 'q2', kind: 'WROTE', value: '13' })] } }));
    expect(screen.getByText('보통 1~2분 걸리니 떠나도 돼요')).toBeInTheDocument();
    expect(screen.getAllByRole('status').map((s) => s.textContent)).toEqual(['차례를 기다리는 중', '검사 중']);
    expect(document.querySelector('.pcheck')).toBeNull();
    expect(screen.queryByText(/\/ 700자/)).not.toBeInTheDocument();
    expect(screen.getAllByText('토큰 만료 자동 재발급 카드로 써요', { selector: '.pq__card' })).toHaveLength(2);
  });
});

describe('④ 질문', () => {
  const asked = () => letter({ stage: 'ASKED', questions: [question({ asks: [
    { item: 'LEARNING', question: '이 일을 겪고 무엇을 배웠나요?', templates: [], required: false, answer: null, skipped: false },
  ] })] });

  it("질문 아래 '예:' 한 줄을 두고 답 칸이 그 줄을 설명으로 읽는다", () => {
    mount('/cover-letters/cl_1', asked());
    const example = screen.getByText(copy.ITEM_EXAMPLE.LEARNING);
    expect(example.textContent).toMatch(/^예: /);
    expect(screen.getByLabelText('이 일을 겪고 무엇을 배웠나요?')).toHaveAttribute('aria-describedby', example.id);
    expect(screen.getByText('100자쯤 쓰면 글이 좋아져요')).toBeInTheDocument();
    expect(screen.getByText('답하지 않은 질문은 넘긴 것으로 봐요')).toBeInTheDocument();
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

import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CoverLetter, coverLetterKeys } from '@/api/coverLetters';
import { CoverLetterPage } from '@/features/coverLetters/CoverLetterPage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

const req = (method: string, path: string, body: unknown = {}) =>
  handle(method, path, new URLSearchParams(), body as Record<string, unknown>, true, {});
const parse = (r: { data: unknown }) => CoverLetter.parse(r.data);
let shift = 0;
const realNow = Date.now;
const advance = (sec: number) => { shift += sec * 1000; Date.now = () => realNow() + shift; };

/** 만들기 → 카드 고르기 → 질문까지 끝난 자소서 */
function askedLetter() {
  const created = parse(req('POST', '/cover-letters', { jobRole: '백엔드 개발', memo: '결제 서비스의 오류를 줄이는 일을 직접 해 보고 싶어 지원했어요', questions: [
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
}

beforeEach(() => { resetDb(); shift = 0; Date.now = realNow; });
afterEach(() => { cleanup(); Date.now = realNow; });

describe('질문 화면: 카드만으로 쓸 수 있어 물을 게 없을 때 (실제 사이트 점검)', () => {
  it('모든 문항에 질문이 없으면 "조금만 더 알려 주세요" 대신 바로 쓸 수 있다고 말한다', () => {
    const letter = askedLetter();
    mount({ ...letter, questions: letter.questions.map((q) => ({ ...q, asks: [] })) });
    expect(screen.getByRole('heading', { level: 1, name: '바로 초안을 쓸 수 있어요' })).toBeInTheDocument();
    expect(screen.queryByText('조금만 더 알려 주세요')).not.toBeInTheDocument();
    expect(screen.getByText('초안을 써요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '초안 쓰기' })).toBeInTheDocument();
    // 문항마다 어느 카드로 쓰는지는 그대로 보인다
    expect(screen.getAllByText('더 물어볼 게 없어요. 카드만으로 쓸 수 있어요.')).toHaveLength(letter.questions.length);
  });

  it('한 문항이라도 물을 게 있으면 제목은 그대로다', () => {
    const letter = askedLetter();
    expect(letter.questions.some((q) => q.asks.length > 0)).toBe(true);
    mount({ ...letter, questions: letter.questions.map((q, i) => (i === 0 ? q : { ...q, asks: [] })) });
    expect(screen.getByRole('heading', { level: 1, name: '조금만 더 알려 주세요' })).toBeInTheDocument();
    expect(screen.queryByText('바로 초안을 쓸 수 있어요')).not.toBeInTheDocument();
  });
});

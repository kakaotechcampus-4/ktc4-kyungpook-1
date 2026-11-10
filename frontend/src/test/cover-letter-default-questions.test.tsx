import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CoverLetter, coverLetterKeys } from '@/api/coverLetters';
import { CoverLetterPage } from '@/features/coverLetters/CoverLetterPage';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
afterEach(cleanup);

const NOTE = '자주 나오는 문항으로 썼어요 · 실제 문항이 있으면 문항 고치기로 바꿔 주세요';
const WRITING_NOTE = '자주 나오는 문항으로 쓰는 중이에요 · 다 쓴 뒤 문항 고치기로 실제 문항으로 바꿀 수 있어요';
const base = { id: 'cl_1', stage: 'ASSIGNING', jobRole: '백엔드 개발', createdAt: 't', updatedAt: 't', questions: [] };
// 초안이 나온 단계는 글상자를 그리므로 초안이 든 문항 하나가 있어야 한다
const drafted = {
  id: 'q1', seq: 1, text: '지원 동기와 입사 후 포부를 적어 주세요', charLimit: 700, theme: 'MOTIVATION',
  primaryCard: { id: 'card_03', title: '토큰 만료 자동 재발급', kind: 'TECH' }, primaryCardDeleted: false,
  supportingCard: null, assignedBy: 'SUB_AGENT', pickReason: '지원 동기와 이어지는 경험이에요.',
  considered: [], materials: ['SITUATION', 'ACTION', 'RESULT'], asks: [], writing: null,
  draft: { text: '액세스 토큰이 만료되면 다시 로그인해야 했다.', aiText: '액세스 토큰이 만료되면 다시 로그인해야 했다.', edited: false, charCount: 24, overBy: 0, sentences: [], marks: [], blanks: [], source: 'LLM' },
};

function open(letter: object) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(coverLetterKeys.one('cl_1'), CoverLetter.parse({ ...base, ...letter }));
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/cover-letters/cl_1']}>
        <Routes><Route path="/cover-letters/:coverLetterId" element={<CoverLetterPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('자주 나오는 문항으로 쓴 자소서', () => {
  it('쓰는 동안에는 다 쓴 뒤 바꿀 수 있다고 말하고, 초안이 나오면 문항 고치기로 바꾸라고 말한다', () => {
    open({ defaultQuestions: true });
    expect(screen.getByText(WRITING_NOTE)).toBeInTheDocument();
    expect(screen.queryByText(NOTE)).not.toBeInTheDocument();
    cleanup();
    open({ defaultQuestions: true, stage: 'DRAFTED', questions: [drafted] });
    expect(screen.getByText(NOTE)).toBeInTheDocument();
    expect(screen.queryByText(WRITING_NOTE)).not.toBeInTheDocument();
  });

  it('defaultQuestions 가 false 거나 칸이 없으면 어느 안내 줄도 보이지 않는다', () => {
    open({ defaultQuestions: false });
    expect(screen.queryByText(NOTE)).not.toBeInTheDocument();
    expect(screen.queryByText(WRITING_NOTE)).not.toBeInTheDocument();
    cleanup();
    open({});
    expect(screen.queryByText(NOTE)).not.toBeInTheDocument();
    expect(screen.queryByText(WRITING_NOTE)).not.toBeInTheDocument();
  });

  it('실패한 자소서에는 안내 줄을 두지 않는다', () => {
    open({ defaultQuestions: true, stage: 'FAILED', failedStep: 'ASSIGN', errorCode: 'AI_UNAVAILABLE' });
    expect(screen.queryByText(NOTE)).not.toBeInTheDocument();
    expect(screen.queryByText(WRITING_NOTE)).not.toBeInTheDocument();
  });
});

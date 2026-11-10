import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CoverLetter, coverLetterKeys } from '@/api/coverLetters';
import { CoverLetterPage } from '@/features/coverLetters/CoverLetterPage';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
afterEach(cleanup);

const NOTE = '자주 나오는 문항으로 썼어요 · 실제 문항이 있으면 문항 고치기로 바꿔 주세요';
const base = { id: 'cl_1', stage: 'ASSIGNING', jobRole: '백엔드 개발', createdAt: 't', updatedAt: 't', questions: [] };

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
  it('defaultQuestions 가 true 면 안내 한 줄이 보이고, 아니거나 칸이 없으면 보이지 않는다', () => {
    open({ defaultQuestions: true });
    expect(screen.getByText(NOTE)).toBeInTheDocument();
    cleanup();
    open({ defaultQuestions: false });
    expect(screen.queryByText(NOTE)).not.toBeInTheDocument();
    cleanup();
    open({});
    expect(screen.queryByText(NOTE)).not.toBeInTheDocument();
  });

  it('실패한 자소서에는 안내 줄을 두지 않는다', () => {
    open({ defaultQuestions: true, stage: 'FAILED', failedStep: 'ASSIGN', errorCode: 'AI_UNAVAILABLE' });
    expect(screen.queryByText(NOTE)).not.toBeInTheDocument();
  });
});

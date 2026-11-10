import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CoverLetter, coverLetterKeys } from '@/api/coverLetters';
import { CoverLetterPage } from '@/features/coverLetters/CoverLetterPage';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

const question = (over: Record<string, unknown> = {}) => ({
  id: 'q1', seq: 1, text: '갈등을 조율한 경험', charLimit: 700, theme: 'CONFLICT',
  primaryCard: { id: 'card_04', title: '팀원과 토큰 저장 위치로 갈린 경험', kind: 'QUALITATIVE' }, primaryCardDeleted: false,
  supportingCard: null, assignedBy: 'SUB_AGENT', pickReason: null,
  considered: [], materials: ['SITUATION', 'ACTION'], asks: [], draft: null, ...over,
});
const asking = (questions: Record<string, unknown>[]) => CoverLetter.parse({
  id: 'cl_1', stage: 'ASKING', failedStep: null, errorCode: null, retryable: null, pollAfterMs: 1500,
  jobRole: '백엔드 개발', memo: null, createdAt: '2026-11-02T00:00:00Z', updatedAt: '2026-11-02T00:00:00Z',
  questions, steps: { assign: [], draft: [] },
});

function mount(letter: CoverLetter) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(coverLetterKeys.one(letter.id), letter);
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/cover-letters/cl_1']}>
    <Routes><Route path="/cover-letters/:coverLetterId" element={<CoverLetterPage />} /></Routes></MemoryRouter></QueryClientProvider>);
}
afterEach(cleanup);

describe('자소서 질문을 고르는 동안(ASKING)', () => {
  it('②와 같은 종이에 문항마다 쓰는 카드 한 줄과 상태 한 줄을 두고, 카드를 바꾸는 버튼은 없다', () => {
    mount(asking([question()]));
    expect(screen.getByRole('heading', { name: '카드에 없는 재료를 확인하고 있어요' })).toBeInTheDocument();
    expect(screen.getByText('보통 1~2분 걸려요')).toBeInTheDocument();
    expect(screen.getByText('팀원과 토큰 저장 위치로 갈린 경험 카드로 써요')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('물어볼 것을 고르는 중');
    expect(screen.queryByRole('button', { name: /카드 바꾸기/ })).not.toBeInTheDocument();
  });

  it('카드를 못 고른 문항은 카드 줄 대신 질문으로 재료를 모은다고 말한다', () => {
    mount(asking([question({ id: 'q2', seq: 2, text: '지원 동기', primaryCard: null, assignedBy: 'CODE', materials: [] })]));
    expect(screen.getByText('카드 없이 질문으로 재료를 모아요')).toBeInTheDocument();
  });
});

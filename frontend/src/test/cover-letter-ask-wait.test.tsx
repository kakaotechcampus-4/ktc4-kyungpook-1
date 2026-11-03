import { cleanup, render, screen, within } from '@testing-library/react';
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
const sectionOf = (name: string) => screen.getByRole('heading', { name }).closest('section') as HTMLElement;

afterEach(cleanup);

describe('자소서 질문을 만드는 동안(ASKING)', () => {
  it('AI가 무엇을 하는 중인지 한 문장으로 말하고, 문항마다 쓸 카드를 보인다', () => {
    mount(asking([question()]));
    expect(screen.getByRole('heading', { name: '카드에 모자란 재료를 찾고 있어요' })).toBeInTheDocument();
    expect(screen.getByText('문항마다 카드를 읽고 무엇을 더 물어볼지 정하고 있어요')).toBeInTheDocument();
    const q = sectionOf('갈등을 조율한 경험');
    expect(within(q).getByText('중심')).toBeInTheDocument();
    expect(within(q).getByRole('link', { name: '팀원과 토큰 저장 위치로 갈린 경험' })).toHaveAttribute('href', '/cards/card_04');
    // 카드를 바꾸는 단계는 지났다
    expect(within(q).queryByRole('button', { name: '카드 바꾸기' })).not.toBeInTheDocument();
  });

  it('고른 카드에 이미 있는 재료와 없는 재료를 글자로도 가르고, 없는 재료가 있을 때만 그 말을 한다', () => {
    mount(asking([question()]));
    const q = sectionOf('갈등을 조율한 경험');
    expect(within(q).getByText('재료 점검')).toBeInTheDocument();
    expect(within(q).getByText('상황').closest('li')).toHaveTextContent('상황 있음');
    expect(within(q).getByText('결과').closest('li')).toHaveTextContent('결과 없음');
    expect(within(q).getByText('없는 재료를 어떻게 물을지 정하고 있어요.')).toBeInTheDocument();
  });

  it('재료가 다 있으면 없는 재료를 물을 거라고 말하지 않는다', () => {
    mount(asking([question({ materials: ['SITUATION', 'ACTION', 'RESULT', 'LEARNING', 'TRIGGER'] })]));
    expect(screen.queryByText('없는 재료를 어떻게 물을지 정하고 있어요.')).not.toBeInTheDocument();
  });

  it('카드를 못 고른 문항은 카드 줄과 재료 점검 대신 질문으로 모은다고 말한다', () => {
    mount(asking([question({ id: 'q2', seq: 2, text: '지원 동기', primaryCard: null, assignedBy: 'CODE', materials: [] })]));
    const q = sectionOf('지원 동기');
    expect(within(q).getByText('고른 카드가 없어서 질문으로 재료를 모아요.')).toBeInTheDocument();
    expect(within(q).queryByText('재료 점검')).not.toBeInTheDocument();
    expect(within(q).queryByText('중심')).not.toBeInTheDocument();
  });
});

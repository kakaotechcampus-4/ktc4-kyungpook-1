import { cleanup, fireEvent, screen, within } from '@testing-library/react';
import { QueryClient } from '@tanstack/react-query';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CoverLetterSummary, coverLetterKeys } from '@/api/coverLetters';
import { CoverLettersPage } from '@/features/coverLetters/CoverLettersPage';
import { mountApp, page } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

/** 자소서 목록은 자소서마다 종이 한 장(#912) — 종이 위쪽은 기업과 상태, 가운데는 직무, 아래는 문항 수와 첫 문항이다 */
afterEach(cleanup);

const POSTING = { id: '3f6c2a10-6b1d-4c2e-9a51-0d7e8b4f1a01', company: '가온소프트', title: '백엔드 엔지니어 (Java·Spring)' };
const summary = (over: Record<string, unknown>) => CoverLetterSummary.parse({
  id: 'a', stage: 'DRAFTED', jobRole: '백엔드 개발', posting: null, questionCount: 1, firstQuestion: '지원 동기를 적어 주세요',
  createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z', ...over,
});

function mount(letters: CoverLetterSummary[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(coverLetterKeys.all, letters);
  return mountApp([page(<CoverLettersPage />, '/cover-letters'), page(<p>자소서 화면</p>, '/cover-letters/:id')], '/cover-letters', client);
}

describe('자소서 목록 종이', () => {
  it('S1 자소서마다 종이 한 장이고, 직무는 제목(h2)으로, 종이는 그 자소서로 가는 링크다', () => {
    mount([summary({ id: 'a', jobRole: '백엔드 개발' }), summary({ id: 'b', jobRole: '데이터 엔지니어' })]);
    const sheets = screen.getAllByRole('listitem');
    expect(sheets).toHaveLength(2);
    expect(within(sheets[1]).getByRole('heading', { level: 2, name: '데이터 엔지니어' })).toBeInTheDocument();
    expect(within(sheets[1]).getByRole('link')).toHaveAttribute('href', '/cover-letters/b');
  });

  it('S2 공고에서 시작한 자소서는 종이 위쪽에 기업 이름을 보이고, 공고 없이 쓴 자소서는 "공고 없이 쓴 자소서"를 보인다', () => {
    mount([summary({ id: 'a', posting: POSTING }), summary({ id: 'b' })]);
    const [withPosting, without] = screen.getAllByRole('listitem');
    expect(within(withPosting).getByText('가온소프트')).toBeInTheDocument();
    expect(within(withPosting).queryByText('공고 없이 쓴 자소서')).not.toBeInTheDocument();
    expect(within(without).getByText('공고 없이 쓴 자소서')).toBeInTheDocument();
  });

  it('S3 공고 제목이 직무와 다르면 직무 아래에 공고 제목을 보이고, 같으면 한 번만 보인다', () => {
    mount([summary({ id: 'a', jobRole: '서버 개발', posting: POSTING }), summary({ id: 'b', jobRole: POSTING.title, posting: POSTING })]);
    const [differs, same] = screen.getAllByRole('listitem');
    expect(within(differs).getByText(POSTING.title)).toBeInTheDocument();
    expect(within(same).getAllByText(POSTING.title)).toHaveLength(1);
  });

  it('S4 문항 수와 첫 문항을 보인다', () => {
    mount([summary({ questionCount: 3, firstQuestion: '가장 어려웠던 문제를 적어 주세요' })]);
    expect(screen.getByText('문항 3개')).toBeInTheDocument();
    expect(screen.getByText('가장 어려웠던 문제를 적어 주세요')).toBeInTheDocument();
  });

  it('S5 삭제 버튼을 누르면 확인 창이 열리고 자소서로 이동하지 않는다', async () => {
    mount([summary({ id: 'a' })]);
    fireEvent.click(screen.getByRole('button', { name: '백엔드 개발 자소서 삭제' }));
    expect(await screen.findByRole('dialog', { name: '자소서를 삭제할까요?' })).toBeInTheDocument();
    expect(screen.queryByText('자소서 화면')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: '자소서' })).toBeInTheDocument();
  });

  it('S6 단계마다 상태 이름을 보이고, 초안이 끝난 자소서만 완성 표시다', () => {
    mount([summary({ id: 'a', stage: 'DRAFTED' }), summary({ id: 'b', stage: 'DRAFTING' }), summary({ id: 'c', stage: 'FAILED' })]);
    const [done, drafting, failed] = screen.getAllByRole('listitem');
    expect(within(done).getByText('초안 완성')).toHaveClass('badge--CONFIRMED');
    expect(within(drafting).getByText('초안 쓰는 중')).toHaveClass('badge--NEUTRAL');
    expect(within(failed).getByText('멈춤')).toHaveClass('badge--NEUTRAL');
  });
});

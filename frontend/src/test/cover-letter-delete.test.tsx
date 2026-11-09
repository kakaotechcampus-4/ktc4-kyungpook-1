import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CoverLetter, CoverLetterSummary, coverLetterKeys } from '@/api/coverLetters';
import { CoverLetterPage } from '@/features/coverLetters/CoverLetterPage';
import { CoverLettersPage } from '@/features/coverLetters/CoverLettersPage';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';
import { connectMockServer, mountApp, page } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

/**
 * 자소서 지우기 — 서버(DELETE /api/cover-letters/{id})에는 있었지만 화면이 없었다.
 * 개인정보처리방침이 "카드와 자소서는 각 화면에서 지울 수 있다"고 적으므로 목록과 상세에 둔다.
 */
beforeEach(() => { resetDb(); localStorage.clear(); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

const MEMO = '결제 서비스의 오류를 줄이는 일을 직접 해 보고 싶어 지원했어요';
const create = (jobRole: string) => CoverLetter.parse(handle('POST', '/cover-letters', new URLSearchParams(), { jobRole, memo: MEMO, questions: [{ text: '지원 동기와 입사 후 포부를 적어 주세요', charLimit: null }] }, true, {}).data);
const routes = () => [page(<CoverLettersPage />, '/cover-letters'), page(<CoverLetterPage />, '/cover-letters/:coverLetterId')];
const deletes = (server: ReturnType<typeof connectMockServer>) => server.calls.filter((call) => call.method === 'DELETE');

describe('자소서 지우기', () => {
  it('목록에서 삭제를 누르면 확인 창이 먼저 열리고, 삭제하면 그 자소서만 목록에서 빠지고 서버에서도 지워진다', async () => {
    const a = create('백엔드 개발');
    const b = create('데이터 엔지니어');
    const server = connectMockServer();
    mountApp(routes(), '/cover-letters');
    await screen.findByText('백엔드 개발');
    const trigger = screen.getByRole('button', { name: '백엔드 개발 자소서 삭제' });
    expect(trigger).toHaveClass('btn--danger'); // 삭제를 여는 버튼은 위험 버튼(빨강)
    fireEvent.click(trigger);
    const dialog = await screen.findByRole('dialog', { name: '자소서를 삭제할까요?' });
    expect(within(dialog).getByText('백엔드 개발')).toBeInTheDocument();
    expect(within(dialog).getByText('문항, 답, 초안이 모두 지워져요. 카드에 기억해 둔 답은 다른 자소서도 쓰므로 남아요.')).toBeInTheDocument();
    expect(within(dialog).getByText('삭제한 자소서는 되돌릴 수 없어요')).toBeInTheDocument();
    expect(deletes(server)).toHaveLength(0); // 확인하기 전에는 지우지 않는다
    fireEvent.click(within(dialog).getByRole('button', { name: '삭제' }));
    await waitFor(() => expect(screen.queryByText('백엔드 개발')).not.toBeInTheDocument());
    expect(screen.getByText('데이터 엔지니어')).toBeInTheDocument();
    expect(deletes(server).map((call) => call.path)).toEqual([`/cover-letters/${a.id}`]);
    expect(db.coverLetters.map((l) => l.id)).toEqual([b.id]);
  });

  it('취소하면 아무것도 지우지 않는다', async () => {
    create('백엔드 개발');
    const server = connectMockServer();
    mountApp(routes(), '/cover-letters');
    await screen.findByText('백엔드 개발');
    fireEvent.click(screen.getByRole('button', { name: '백엔드 개발 자소서 삭제' }));
    const dialog = await screen.findByRole('dialog', { name: '자소서를 삭제할까요?' });
    fireEvent.click(within(dialog).getByRole('button', { name: '취소' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.getByText('백엔드 개발')).toBeInTheDocument();
    expect(deletes(server)).toHaveLength(0);
    expect(db.coverLetters).toHaveLength(1);
  });

  it('이미 지워진 자소서(404)를 지우려 해도 지운 것으로 보고 목록에서 뺀다', async () => {
    create('백엔드 개발');
    connectMockServer();
    mountApp(routes(), '/cover-letters');
    await screen.findByText('백엔드 개발');
    fireEvent.click(screen.getByRole('button', { name: '백엔드 개발 자소서 삭제' }));
    const dialog = await screen.findByRole('dialog', { name: '자소서를 삭제할까요?' });
    db.coverLetters.splice(0); // 다른 곳에서 먼저 지웠다
    fireEvent.click(within(dialog).getByRole('button', { name: '삭제' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(await screen.findByText('아직 쓴 자소서가 없어요')).toBeInTheDocument();
  });

  it('자소서 화면의 자소서 삭제는 확인한 뒤 지우고 목록으로 돌아간다', async () => {
    const a = create('백엔드 개발');
    const server = connectMockServer();
    mountApp(routes(), `/cover-letters/${a.id}`);
    fireEvent.click(await screen.findByRole('button', { name: '자소서 삭제' }));
    const dialog = await screen.findByRole('dialog', { name: '자소서를 삭제할까요?' });
    fireEvent.click(within(dialog).getByRole('button', { name: '삭제' }));
    expect(await screen.findByRole('heading', { name: '자소서' })).toBeInTheDocument();
    expect(await screen.findByText('아직 쓴 자소서가 없어요')).toBeInTheDocument();
    expect(deletes(server).map((call) => call.path)).toEqual([`/cover-letters/${a.id}`]);
    expect(db.coverLetters).toHaveLength(0);
  });

  it('AI가 도는 단계면 그 결과를 버린다고 알리고, 끝난 자소서에는 알리지 않는다', async () => {
    const summary = (over: Record<string, unknown>) => CoverLetterSummary.parse({
      id: 'a', stage: 'DRAFTED', jobRole: '백엔드 개발', posting: null, questionCount: 1, firstQuestion: '지원 동기',
      createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z', ...over,
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    client.setQueryData(coverLetterKeys.all, [summary({ id: 'a', stage: 'DRAFTING', jobRole: '쓰는 중인 자소서' }), summary({ id: 'b', stage: 'DRAFTED', jobRole: '끝난 자소서' })]);
    mountApp(routes(), '/cover-letters', client);
    fireEvent.click(screen.getByRole('button', { name: '쓰는 중인 자소서 자소서 삭제' }));
    expect(within(await screen.findByRole('dialog')).getByText('AI가 쓰는 중이면 그 결과는 저장하지 않고 버려요.')).toBeInTheDocument();
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '취소' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '끝난 자소서 자소서 삭제' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).queryByText('AI가 쓰는 중이면 그 결과는 저장하지 않고 버려요.')).not.toBeInTheDocument();
  });
});

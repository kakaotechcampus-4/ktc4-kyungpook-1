import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { keys } from '@/api/keys';
import { RepoDetail, RepoSummary } from '@/api/schemas';
import { ReposPage } from '@/features/repos/ReposPage';
import { handle } from '@/mock/router';

afterEach(cleanup);

const get = (path: string) => handle('GET', path, new URLSearchParams(), {}, true).data;

function open(repoId: string, patch?: (detail: Record<string, unknown>) => Record<string, unknown>) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(keys.repos, (get('/repos') as unknown[]).map((item) => RepoSummary.parse(item)));
  const raw = get(`/repos/${repoId}`) as Record<string, unknown>;
  client.setQueryData(keys.repo(repoId), RepoDetail.parse(patch ? patch(raw) : raw));
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[`/repos?select=${repoId}&disclose=1`]}><Routes><Route path="/repos" element={<ReposPage />} /></Routes></MemoryRouter></QueryClientProvider>);
  return screen.getByRole('dialog');
}

describe('다시 정리 창 (#967)', () => {
  it('R1 새 기록이 있으면 새 기록만 읽는다는 제목과 개수·예상 시간을 보이고 읽는 범위는 눌러야 펼쳐진다', () => {
    const dialog = open('r_auth');

    expect(screen.getByRole('dialog', { name: '새 기록만 읽어요' })).toBe(dialog);
    expect(within(dialog).getByText(/^지난 정리\(.+\) 뒤 새 커밋 3개 · PR 1개$/)).toBeInTheDocument();
    expect(within(dialog).getByText('약 40초')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: '다시 정리' })).toBeInTheDocument();
    expect(within(dialog).queryByText('전에 정리한 레포예요')).not.toBeInTheDocument();
    expect(within(dialog).queryByText(/^정리한 뒤에 새 커밋이 생기면/)).not.toBeInTheDocument();
    expect(within(dialog).queryByText('다른 사람만 작업한 PR')).not.toBeInTheDocument();

    fireEvent.click(within(dialog).getByRole('button', { name: '읽는 범위 보기' }));

    expect(within(dialog).getByText('다른 사람만 작업한 PR')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: '읽는 범위 접기' })).toBeInTheDocument();
  });

  it('R2 새 기록이 없으면 지난 후보 보기가 주된 동작이고 다시 정리는 보조로 남는다', () => {
    const dialog = open('r_fts');

    expect(within(dialog).getByText(/^지난 정리\(.+\) 뒤 새 기록이 없어요$/)).toBeInTheDocument();
    expect(within(dialog).getByText('지난 후보를 그대로 둬요')).toBeInTheDocument();
    expect(within(dialog).getByText('약 12초')).toBeInTheDocument();
    expect(within(dialog).getByRole('link', { name: '지난 후보 보기' })).toHaveAttribute('href', '/repos/r_fts/candidates');
    expect(within(dialog).getByRole('button', { name: '그래도 다시 정리' })).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: '다시 정리' })).not.toBeInTheDocument();
  });

  it('R3 처음 정리하는 레포는 읽는 범위 확인 창이 그대로고 처음 정리 시간을 보인다', () => {
    const dialog = open('r_web');

    expect(screen.getByRole('dialog', { name: '읽는 범위 확인' })).toBe(dialog);
    expect(within(dialog).getByText('다른 사람만 작업한 PR')).toBeInTheDocument();
    expect(within(dialog).getByText('약 30초')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: '정리 시작' })).toBeInTheDocument();
  });

  it('R4 새 필드를 못 받은 서버면 정리한 레포도 처음 정리 모양으로 보인다', () => {
    const dialog = open('r_auth', (detail) => {
      const { newCommits, newPullRequests, reanalyzeSeconds, ...rest } = detail.disclosure as Record<string, unknown>;
      void newCommits; void newPullRequests; void reanalyzeSeconds;
      return { ...detail, disclosure: rest };
    });

    expect(screen.getByRole('dialog', { name: '읽는 범위 확인' })).toBe(dialog);
    expect(within(dialog).getByText('전에 정리한 레포예요')).toBeInTheDocument();
    expect(within(dialog).getByText('약 40초')).toBeInTheDocument();
  });
});

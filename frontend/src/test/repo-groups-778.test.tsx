import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { keys } from '@/api/keys';
import { RepoDetail, RepoSummary } from '@/api/schemas';
import { ReposPage } from '@/features/repos/ReposPage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

const get = (path: string) => handle('GET', path, new URLSearchParams(), {}, true).data;
const seeded = () => (get('/repos') as unknown[]).map((item) => RepoSummary.parse(item));
const repo = (id: string, over: Record<string, unknown> = {}) => RepoSummary.parse({
  id, owner: 'hong-dev', name: id, contribution: { mine: 1, team: 1, ratio: 0.5, level: 'SHARED' }, prCount: 0, reviewCount: 0, language: null,
  activeFrom: '2026-01-01', activeTo: '2026-02-01', lastAnalyzedAt: null, candidateCount: null, cardCount: 0, recommended: false, ...over,
});
const DONE = '2026-10-01T00:00:00Z';

beforeEach(resetDb);
afterEach(cleanup);

function mount(path: string, repos: RepoSummary[] = seeded()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(keys.repos, repos);
  for (const item of seeded()) client.setQueryData(keys.repo(item.id), RepoDetail.parse(get(`/repos/${item.id}`)));
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes><Route path="/repos" element={<ReposPage />} /></Routes></MemoryRouter></QueryClientProvider>);
}
const heads = () => screen.getAllByRole('heading', { level: 2 }).map((h) => [h.textContent, h.parentElement!.querySelector('.section-head__count')!.textContent]);
const names = (region: string) => [...screen.getByRole('region', { name: region }).querySelectorAll('.repo-row__name')].map((el) => el.textContent);
const search = (value: string) => fireEvent.change(screen.getByRole('textbox', { name: '레포 이름 검색' }), { target: { value } });

describe('레포 목록 세 묶음 (#778)', () => {
  it('세 묶음 제목이 개수와 함께 보이고 레포가 제 묶음 아래에 있다', () => {
    mount('/repos');

    expect(heads()).toEqual([['내 기여가 많은 레포', '3'], ['아직 정리하지 않은 레포', '4'], ['이미 정리한 레포', '2']]);
    expect(names('내 기여가 많은 레포')).toEqual(['big-monorepo', 'web-client', 'algorithm-study']);
    expect(names('아직 정리하지 않은 레포')).toEqual(['data-pipeline', 'legacy-monolith', 'team-board', 'side-project-new']);
    expect(names('이미 정리한 레포')).toEqual(['auth-service', 'free-tier-sleep']);
    expect(within(screen.getByRole('radiogroup', { name: '레포 선택' })).getAllByRole('radio')).toHaveLength(7);
  });

  it('빈 묶음은 제목을 그리지 않는다', () => {
    mount('/repos', seeded().map((r) => ({ ...r, recommended: false, lastAnalyzedAt: null })));
    expect(heads()).toEqual([['아직 정리하지 않은 레포', '9']]);
    cleanup();

    mount('/repos', seeded().map((r) => ({ ...r, lastAnalyzedAt: DONE })));
    expect(heads()).toEqual([['이미 정리한 레포', '9']]);
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
  });

  it('정리한 레포 줄은 라디오가 아니고 카드 수와 후보 보기·다시 정리 링크가 있다', () => {
    mount('/repos');
    const done = screen.getByRole('region', { name: '이미 정리한 레포' });
    const row = within(done).getByText('auth-service').closest('li')!;

    expect(within(done).queryAllByRole('radio')).toHaveLength(0);
    expect(within(screen.getByRole('radiogroup', { name: '레포 선택' })).queryByText('auth-service')).not.toBeInTheDocument();
    expect(within(row).getByText('카드 2장')).toBeInTheDocument();
    expect(within(row).getByRole('link', { name: '후보 보기' })).toHaveAttribute('href', '/repos/r_auth/candidates');
    expect(within(row).getByRole('link', { name: '다시 정리' })).toHaveAttribute('href', '/repos?select=r_auth&disclose=1');
    expect(row.querySelector('.repo-row__line, .repo-row__foot')).toBeNull();
    cleanup();

    mount('/repos', [repo('r a/b', { lastAnalyzedAt: DONE })]);
    expect(screen.getByRole('link', { name: '후보 보기' })).toHaveAttribute('href', '/repos/r%20a%2Fb/candidates');
    expect(screen.getByRole('link', { name: '다시 정리' })).toHaveAttribute('href', '/repos?select=r%20a%2Fb&disclose=1');
  });

  it('다시 정리를 누르면 그 레포의 새 기록만 읽는 창이 열린다', () => {
    mount('/repos');
    const row = screen.getByText('free-tier-sleep').closest('li')!;
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    fireEvent.click(within(row).getByRole('link', { name: '다시 정리' }));

    const dialog = screen.getByRole('dialog', { name: '새 기록만 읽어요' });
    expect(within(dialog).getByText('TaeHuiKKIM / free-tier-sleep')).toBeInTheDocument();
    expect(within(dialog).queryByText('전에 정리한 레포예요')).not.toBeInTheDocument();
  });

  it('정리한 레포의 읽는 범위 창을 취소하면 고른 상태가 풀려 아래 막대가 다시 레포를 고르라고 한다', () => {
    mount('/repos?select=r_auth&disclose=1');

    fireEvent.click(within(screen.getByRole('dialog', { name: '새 기록만 읽어요' })).getByRole('button', { name: '취소' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByText('정리할 레포를 고르면 다음으로 넘어가요')).toBeInTheDocument();
  });

  it('고르는 줄의 레포는 읽는 범위 창을 취소해도 고른 상태가 남는다', () => {
    mount('/repos?select=r_web&disclose=1');

    fireEvent.click(within(screen.getByRole('dialog', { name: '읽는 범위 확인' })).getByRole('button', { name: '취소' }));

    expect(screen.getByText('web-client 을 골랐어요')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /web-client/ })).toHaveAttribute('aria-checked', 'true');
  });

  it('설명이 있는 레포는 이름 아래 설명 한 줄이 보이고 없으면 비운다', () => {
    mount('/repos');
    const web = screen.getByRole('radio', { name: /web-client/ });
    const desc = web.querySelector('.repo-row__desc')!;

    expect(desc).toHaveTextContent('인증 서비스와 붙는 React 웹 클라이언트예요.');
    expect(web.querySelector('.repo-row__title')!.nextElementSibling).toBe(desc);
    expect(desc.nextElementSibling).toHaveClass('repo-row__line');
    expect(screen.getByRole('radio', { name: /algorithm-study/ }).querySelector('.repo-row__desc')).toBeNull();
    expect(screen.getByText('auth-service').closest('li')!.querySelector('.repo-row__desc')).toHaveTextContent('로그인과 세션 인증을 맡는 Spring Boot 서비스');
    cleanup();

    mount('/repos', seeded().map((r) => (r.id === 'r_auth' ? { ...r, description: null, descriptionSource: null } : r)));
    expect(screen.getByText('auth-service').closest('li')!.querySelector('.repo-row__desc')).toBeNull();
  });

  it('고르는 줄에는 먼저 정리해 보기 좋아요 글과 정리 완료 배지가 없다', () => {
    mount('/repos');
    const radios = screen.getByRole('radiogroup', { name: '레포 선택' });

    expect(screen.queryByText('먼저 정리해 보기 좋아요')).not.toBeInTheDocument();
    expect(screen.queryByText(/정리 완료/)).not.toBeInTheDocument();
    expect(radios.querySelectorAll('.repo-row__pick, .badge')).toHaveLength(0);
  });

  it('검색은 세 묶음 전부에 걸리고 결과가 없으면 검색 결과가 없어요가 보인다', () => {
    mount('/repos', [repo('x-rec', { recommended: true }), repo('x-plain'), repo('x-done', { lastAnalyzedAt: DONE }), repo('other', { recommended: true }), repo('other-done', { lastAnalyzedAt: DONE })]);
    expect(heads()).toEqual([['내 기여가 많은 레포', '2'], ['아직 정리하지 않은 레포', '1'], ['이미 정리한 레포', '2']]);

    search('x-');
    expect(heads()).toEqual([['내 기여가 많은 레포', '1'], ['아직 정리하지 않은 레포', '1'], ['이미 정리한 레포', '1']]);

    search('no-such-repo');
    expect(screen.getByText('검색 결과가 없어요')).toBeInTheDocument();
    expect(screen.queryAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(['검색 결과가 없어요']);
  });

  it('필터 드롭다운이 없고 ?filter=nopr 가 와도 전부 보인다', () => {
    mount('/repos?filter=nopr');

    expect(screen.queryByRole('combobox', { name: '필터' })).not.toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: '정렬' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /web-client/ })).toBeInTheDocument();
    expect(screen.getAllByRole('radio')).toHaveLength(7);
    expect(heads().map(([title]) => title)).toEqual(['내 기여가 많은 레포', '아직 정리하지 않은 레포', '이미 정리한 레포']);
  });
});

import { cleanup, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { coverLetterApi, coverLetterKeys } from '@/api/coverLetters';
import { keys } from '@/api/keys';
import { MatchDetail, matchingApi } from '@/api/matching';
import { CardSummary, RepoDetail, RepoSummary } from '@/api/schemas';
import { CoverLetterNewPage } from '@/features/coverLetters/CoverLetterNewPage';
import { CoverLettersPage } from '@/features/coverLetters/CoverLettersPage';
import { MatchDetailPage } from '@/features/match/MatchDetailPage';
import { ReposPage } from '@/features/repos/ReposPage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';
import sampleDetail from '../../docs/examples/matching/detail.json';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

/** #973 심사 경로 화면의 되풀이 문구·중복 주된 버튼 정리 */
const get = (path: string) => handle('GET', path, new URLSearchParams(), {}, true).data;
const cards = () => (get('/cards') as unknown[]).map((c) => CardSummary.parse(c));
const newClient = () => new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
const wrap = (client: QueryClient, path: string, routes: ReactNode) =>
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes>{routes}</Routes></MemoryRouter></QueryClientProvider>);

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe('/repos', () => {
  const mountRepos = (path: string) => {
    const client = newClient();
    const list = (get('/repos') as unknown[]).map((r) => RepoSummary.parse(r));
    client.setQueryData(keys.repos, list);
    for (const r of list) client.setQueryData(keys.repo(r.id), RepoDetail.parse(get(`/repos/${r.id}`)));
    wrap(client, path, <Route path="/repos" element={<ReposPage />} />);
  };

  it('위쪽에 레포 선택·읽는 범위 확인·후보 고르기 단계 표시가 없다', () => {
    mountRepos('/repos');
    expect(screen.queryByRole('list', { name: '진행 단계' })).not.toBeInTheDocument();
    expect(screen.queryByText('읽는 범위 확인')).not.toBeInTheDocument();
  });

  it('레포 줄의 작은 줄에는 언어만 있고 기간은 문장이 말한다', () => {
    mountRepos('/repos');
    const metas = [...document.querySelectorAll('.repo-row__meta')].map((el) => el.textContent ?? '');
    expect(metas.length).toBeGreaterThan(0);
    expect(metas.every((m) => !/\d{4}\.\d{2}/.test(m))).toBe(true);
  });

  it('읽는 범위 확인 창이 열리면 아래 취소·다음 막대가 숨는다', () => {
    mountRepos('/repos?select=r_auth&disclose=1');
    // 목의 r_auth 는 정리한 적이 있어 창 제목과 주 버튼이 다시 정리 모양이다(#974)
    const dialog = screen.getByRole('dialog', { name: /읽는 범위 확인|새 기록만 읽어요/ });
    expect(within(dialog).getByRole('button', { name: /정리 시작|다시 정리/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '다음' })).not.toBeInTheDocument();
    expect(screen.queryByText(/을 골랐어요$/)).not.toBeInTheDocument();
  });

  it('창이 닫혀 있으면 다음 버튼이 있다', () => {
    mountRepos('/repos?select=r_auth');
    expect(screen.getByRole('button', { name: '다음' })).toBeInTheDocument();
  });
});

describe('공고 요약 — 맞춰 본 뒤', () => {
  const base = MatchDetail.parse(sampleDetail.data);
  const mountDetail = (detail: MatchDetail) => {
    vi.spyOn(matchingApi, 'detail').mockResolvedValue(detail);
    const client = newClient();
    client.setQueryData(keys.cards, cards());
    wrap(client, `/match/${detail.id}`, <Route path="/match/:matchId" element={<MatchDetailPage />} />);
  };
  const confirmed = () => cards().filter((c) => c.status === 'CONFIRMED').length;

  it('읽었다·견줬다 줄 대신 한 줄만 남는다', async () => {
    mountDetail({ ...base, gapFill: null });
    const log = await screen.findByRole('list', { name: 'AI 가 한 일' });
    expect(within(log).getAllByRole('listitem').map((li) => li.textContent)).toEqual([`카드 ${confirmed()}장으로 맞춰 봤어요`]);
    expect(screen.queryByText(/장을 읽었어요/)).not.toBeInTheDocument();
    expect(screen.queryByText(/와 견줬어요/)).not.toBeInTheDocument();
  });

  it('제안이 있으면 같은 줄 뒤에 제안 k개를 붙이고, 없으면 붙이지 않는다', async () => {
    const gapFill = { state: 'DONE' as const, pollAfterMs: 0, conditions: [] };
    const target = base.conditions.find((c) => c.status === 'NOT_MET')!;
    const proposed = { conditionId: target.id, decision: 'SEARCH', decisionReason: null, forced: false, steps: [], result: 'PROPOSED', proposal: { action: 'CREATE_CARD', candidateId: 'c_51', repoId: 'r_auth', title: '로그인 API 응답 속도 개선', repoName: 'auth-service', reason: '응답 시간을 줄인 기록이 있어요' } };
    mountDetail({ ...base, gapFill: { ...gapFill, conditions: [proposed] } as never });
    expect(await screen.findByText(`카드 ${confirmed()}장으로 맞춰 봤고 제안 1개를 찾았어요`)).toBeInTheDocument();
    cleanup();
    mountDetail({ ...base, gapFill });
    expect(await screen.findByText(`카드 ${confirmed()}장으로 맞춰 봤어요`)).toBeInTheDocument();
  });

  it('자소서 목록을 받기 전에는 자소서 버튼 자리만 두어 이름이 바뀌어 보이지 않는다', async () => {
    vi.spyOn(coverLetterApi, 'list').mockReturnValue(new Promise(() => {}));
    mountDetail({ ...base, gapFill: null });
    await screen.findByRole('complementary', { name: '공고 요약' });
    expect(screen.queryByRole('link', { name: '이 공고로 자소서 쓰기' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '초안 보기' })).not.toBeInTheDocument();
  });

  it('조건마다 근거 카드 이름은 하나만 보이고 나머지는 +N 의 title 에 둔다', async () => {
    const met = base.conditions.find((c) => c.metBy.length > 0)!;
    const many = [0, 1, 2].map((i) => ({ ...met.metBy[0], cardId: `c${i}`, cardTitle: `카드 이름 ${i}` }));
    mountDetail({ ...base, conditions: base.conditions.map((c) => (c.id === met.id ? { ...c, metBy: many } : c)) });
    const more = await screen.findByText('+2');
    expect(more).toHaveAttribute('title', '카드 이름 1, 카드 이름 2');
    const evidence = more.closest('p')!;
    expect(within(evidence).getAllByRole('link')).toHaveLength(1);
    expect(within(evidence).getByRole('link')).toHaveTextContent('카드 이름 0');
  });
});

describe('/cover-letters/new', () => {
  it('아래 막대에 부제와 같은 말 문장이 없고 버튼만 있다', () => {
    const client = newClient();
    client.setQueryData(keys.cards, cards());
    wrap(client, '/cover-letters/new', <Route path="/cover-letters/new" element={<CoverLetterNewPage />} />);
    expect(screen.queryByText('만들면 카드를 고르고 초안까지 이어서 써요')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '초안 쓰기' })).toBeInTheDocument();
  });
});

describe('/cover-letters', () => {
  const mountList = (letters: unknown[]) => {
    const client = newClient();
    client.setQueryData(coverLetterKeys.all, letters);
    wrap(client, '/cover-letters', <Route path="/cover-letters" element={<CoverLettersPage />} />);
  };
  const isPrimary = (el: HTMLElement) => el.classList.contains('btn--primary');

  it('목록이 비면 안내의 새 자소서만 주된 버튼이고 머리의 것은 보조다', () => {
    mountList([]);
    const buttons = screen.getAllByRole('link', { name: '새 자소서' });
    expect(buttons).toHaveLength(2);
    expect(buttons.filter(isPrimary)).toHaveLength(1);
    expect(isPrimary(buttons[0])).toBe(false);
  });

  it('목록이 있으면 머리의 새 자소서 하나가 주된 버튼이다', () => {
    mountList([{ id: 'a', stage: 'DRAFTED', jobRole: '백엔드 개발', posting: null, questionCount: 1, emptyQuestionCount: 0, firstQuestion: '지원 동기', createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z' }]);
    const buttons = screen.getAllByRole('link', { name: '새 자소서' });
    expect(buttons).toHaveLength(1);
    expect(isPrimary(buttons[0])).toBe(true);
  });
});

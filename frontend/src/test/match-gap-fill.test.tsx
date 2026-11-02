import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { MatchDetailPage } from '@/features/match/MatchDetailPage';
import { planProceed, proceedDestinations, readProceeded, rememberProceeded, revealIntervalMs, stepText } from '@/features/match/gapFill';
import { toast } from '@/lib/toast';
import { gapFillPollMs, matchingApi, MatchDetail, type GapFill, type GapFillCondition, type GapFillProposal } from '@/api/matching';
import { endpoints } from '@/api/endpoints';
import { ApiError } from '@/api/client';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';
import sampleDetail from '../../docs/examples/matching/detail.json';
import sampleList from '../../docs/examples/matching/list.json';

vi.mock('@/lib/toast', () => ({ toast: vi.fn() }));
beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers(); sessionStorage.clear(); });

const base = MatchDetail.parse(sampleDetail.data);
const notMet = base.conditions.filter((c) => c.status === 'NOT_MET');
/** 맞춰 보기 전 모양 — 카드로 판단하는 조건만 UNJUDGED 로 돌린다 */
const unjudged = (detail: MatchDetail): MatchDetail => ({
  ...detail, needsJudgement: true, summary: null,
  conditions: detail.conditions.map((c) => (c.status === 'MET' || c.status === 'NOT_MET' ? { ...c, status: 'UNJUDGED' as const, metBy: [] } : c)),
});
/** 맞은 조건을 모두 모자람으로 돌려 ✕ 를 5개 만든다(네 결말을 한 공고에서 보려고) */
const five: MatchDetail = { ...base, conditions: base.conditions.map((c) => (c.status === 'MET' ? { ...c, status: 'NOT_MET' as const, metBy: [] } : c)) };
const fiveIds = five.conditions.filter((c) => c.status === 'NOT_MET').map((c) => c.id);
const condition = (conditionId: string, over: Partial<GapFillCondition> = {}): GapFillCondition =>
  ({ conditionId, decision: 'SEARCH', decisionReason: '관련 카드가 있어요', forced: false, steps: [], proposal: null, result: null, ...over });
const st = (kind: string, count: number | null = null, query: string | null = null, repoName: string | null = null) => ({ kind, count, query, repoName });
const proposalOf = (over: Partial<GapFillProposal> = {}): GapFillProposal =>
  ({ action: 'CREATE_CARD', candidateId: 'c_51', repoId: 'r_auth', title: '로그인 API 응답 속도 개선', repoName: 'auth-service', reason: '응답 시간을 줄인 기록이 있어요', ...over });
const done = (conditions: GapFillCondition[]): GapFill => ({ state: 'DONE', pollAfterMs: null, conditions });
const tiles = () => document.querySelectorAll('.posting-conditions .cond-tile').length;

const Probe = ({ label }: { label: string }) => { const params = useParams(); return <div>{label} {Object.values(params).join(' ')}</div>; };
const mount = (detail: MatchDetail, spy = true) => {
  if (spy) vi.spyOn(matchingApi, 'detail').mockResolvedValue(detail);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/match/${encodeURIComponent(detail.id)}`]}>
        <Routes>
          <Route path="/match/:matchId" element={<MatchDetailPage />} />
          <Route path="/cards/:cardId" element={<Probe label="card page" />} />
          <Route path="/cards/:cardId/interview" element={<Probe label="interview" />} />
          <Route path="/repos/:repoId/run" element={<Probe label="analyze" />} />
          <Route path="/cards" element={<div>cards list</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
};
/** 시간을 조금씩 앞으로 돌린다 — 한 타이머가 다음 타이머를 거는 구조라, 한 번에 다 돌리면 act 가 상태를 끝에 몰아 반영해 한 단계만 펼쳐진다 */
const advance = async (ms: number) => {
  for (let t = 0; t < ms; t += 250) await act(async () => { await vi.advanceTimersByTimeAsync(Math.min(250, ms - t)); });
};
const judgeButton = () => screen.queryByRole('button', { name: '이 공고와 맞춰 보기' });

it('맞춰 본 결과는 읽은 카드 수 → 견준 조건 수 → 조건마다 표시 순서로 차례로 펼쳐지고, 다 펼치기까지 6초를 넘긴다', async () => {
  // 항목이 적으면 간격을 늘려 전체 6초를 지키고, 많으면 0.9초로 둔다
  expect(revealIntervalMs(3)).toBe(2000);
  expect(revealIntervalMs(20)).toBe(900);
  vi.useFakeTimers({ shouldAdvanceTime: true });
  mount(unjudged(base));
  vi.spyOn(matchingApi, 'judge').mockResolvedValue(base);
  vi.spyOn(matchingApi, 'gapFill').mockResolvedValue(base);
  fireEvent.click(await screen.findByRole('button', { name: '이 공고와 맞춰 보기' }));
  await waitFor(() => expect(judgeButton()).not.toBeInTheDocument());
  // 받은 직후에는 아무 표시도 없다 — 끝난 일을 하는 중으로 꾸미는 게 아니라 받은 결과를 순서대로 보일 뿐
  expect(tiles()).toBe(0);
  await advance(1000);
  expect(screen.getByText(/^확정 카드.*읽었어요$/)).toBeInTheDocument();
  expect(screen.queryByText('필수 3개·우대 2개와 견줬어요')).not.toBeInTheDocument();
  await advance(900);
  expect(screen.getByText('필수 3개·우대 2개와 견줬어요')).toBeInTheDocument();
  expect(tiles()).toBe(0);
  await advance(900);
  expect(tiles()).toBe(1);
  // 5초 시점에는 아직 다 펼쳐지지 않았고 집계도 없다
  await advance(2200);
  expect(tiles()).toBeLessThan(5);
  expect(screen.queryByText(/2\/3 맞음/)).not.toBeInTheDocument();
  await advance(4000);
  expect(tiles()).toBe(5);
  expect(screen.getAllByText(/2\/3 맞음/)).toHaveLength(2);
});

it('✕ 가 있으면 화면이 채우기를 스스로 한 번 부르고, 다 ✓ 면 부르지 않는다', async () => {
  mount(unjudged(base));
  vi.spyOn(matchingApi, 'judge').mockResolvedValue(base);
  const gapFill = vi.spyOn(matchingApi, 'gapFill').mockResolvedValue({ ...base, gapFill: { state: 'RUNNING', pollAfterMs: 1500, conditions: [] } });
  fireEvent.click(await screen.findByRole('button', { name: '이 공고와 맞춰 보기' }));
  await waitFor(() => expect(gapFill).toHaveBeenCalledTimes(1));
  expect(gapFill).toHaveBeenCalledWith(base.id, undefined);
  cleanup();
  vi.restoreAllMocks();
  const allMet: MatchDetail = { ...base, conditions: base.conditions.map((c) => (c.status === 'NOT_MET' ? { ...c, status: 'MET' as const, metBy: base.conditions[0].metBy } : c)) };
  mount(unjudged(allMet));
  vi.spyOn(matchingApi, 'judge').mockResolvedValue(allMet);
  const again = vi.spyOn(matchingApi, 'gapFill').mockResolvedValue(allMet);
  fireEvent.click(await screen.findByRole('button', { name: '이 공고와 맞춰 보기' }));
  await waitFor(() => expect(judgeButton()).not.toBeInTheDocument());
  await act(async () => { await Promise.resolve(); });
  expect(again).not.toHaveBeenCalled();
});

it('조건마다 찾아볼지와 이유 → 한 일 → 결론이 보이고, 찾지 않기로 한 조건에만 그래도 찾아보기가 있다', async () => {
  const gapFill = done([
    condition(fiveIds[0], { steps: [st('CANDIDATES_CHECKED', 12, null), st('GITHUB_SEARCHED', 5, 'docker')], result: 'PROPOSED', proposal: proposalOf() }),
    condition(fiveIds[1], { decision: 'SKIP', decisionReason: '다른 분야의 일이라 거리가 멀어요', result: 'SKIPPED' }),
    condition(fiveIds[2], { steps: [st('SOMETHING_NEW', null, 'helm')], result: 'NOT_FOUND' }),
    condition(fiveIds[3], { steps: [st('OUTSIDE_REPO_FOUND', 2, null, 'kbu-capstone/team-board')], result: 'TIMED_OUT' }),
  ]);
  mount({ ...five, gapFill });
  const again = vi.spyOn(matchingApi, 'gapFill').mockResolvedValue({ ...five, gapFill });
  await screen.findByText('카드로 안 만든 후보 12개를 읽었어요');
  expect(screen.getByText("GitHub 에서 'docker' 로 내 커밋을 찾았어요 · 5개")).toBeInTheDocument();
  expect(screen.getByText("'helm' 로 자료를 살펴봤어요")).toBeInTheDocument();
  expect(screen.getByText('kbu-capstone/team-board 는 다른 사람 레포라 분석할 수 없어 뺐어요 · 커밋 2개')).toBeInTheDocument();
  expect(screen.getByRole('checkbox', { name: '후보로 카드 만들기 — 로그인 API 응답 속도 개선' })).toBeInTheDocument();
  expect(screen.getByText('응답 시간을 줄인 기록이 있어요')).toBeInTheDocument();
  expect(screen.getByText(/^찾지 않기로 했어요 — 다른 분야의 일이라 거리가 멀어요/)).toBeInTheDocument();
  expect(screen.getByText('채울 자료를 찾지 못했어요.')).toBeInTheDocument();
  expect(screen.getByText('시간이 부족해 여기까지 찾았어요.')).toBeInTheDocument();
  expect(screen.getByText('제안 1개 · 찾지 못함 1개 · 찾지 않음 1개 · 시간 초과 1개')).toBeInTheDocument();
  // 다섯 번째 ✕ 조건은 서버가 찾지 않아(상한) 아래에 아무것도 없다
  expect(document.querySelectorAll('.gap-fill')).toHaveLength(4);
  const buttons = screen.getAllByRole('button', { name: '그래도 찾아보기' });
  expect(buttons).toHaveLength(1);
  fireEvent.click(buttons[0]);
  await waitFor(() => expect(again).toHaveBeenCalledWith(five.id, [fiveIds[1]]));
  // 다시 찾은 조건(forced)은 decision 이 SKIP 그대로여도 '다시 찾아봤어요' 로 보이고 버튼은 다시 두지 않는다
  cleanup();
  mount({ ...five, gapFill: done([condition(fiveIds[1], { decision: 'SKIP', decisionReason: '거리가 멀어요', forced: true, result: 'PROPOSED', proposal: proposalOf() })]) });
  await screen.findByText(/^찾지 않기로 했지만 다시 찾아봤어요 — 거리가 멀어요/);
  expect(screen.queryByRole('button', { name: '그래도 찾아보기' })).not.toBeInTheDocument();
  expect(screen.getByRole('checkbox', { name: /후보로 카드 만들기/ })).toBeInTheDocument();
});

it('도는 동안에도 결론이 난 조건에는 찾고 있어요를 붙이지 않고, 아직 정하는 중인 조건에만 점을 둔다', async () => {
  const gapFill: GapFill = {
    state: 'RUNNING', pollAfterMs: 1500,
    conditions: [
      condition(fiveIds[0], { result: 'PROPOSED', proposal: proposalOf() }),
      condition(fiveIds[1], { decision: null, decisionReason: null }),
      condition(fiveIds[2], { steps: [st('DRAFT_CARDS_CHECKED', 2, null)] }),
    ],
  };
  mount({ ...five, gapFill });
  await screen.findByText('모자란 조건 3개를 채울 자료를 찾고 있어요');
  const blocks = document.querySelectorAll('.gap-fill');
  expect(blocks).toHaveLength(3);
  expect(blocks[0]).not.toHaveTextContent('찾고 있어요');
  expect(blocks[0]).toHaveTextContent('로그인 API 응답 속도 개선');
  expect(blocks[1]).toHaveTextContent('찾아볼지 정하고 있어요');
  expect(blocks[2]).toHaveTextContent('작성 중인 카드 2장을 읽었어요');
  expect(blocks[2]).toHaveTextContent('찾고 있어요');
  // 결론이 없는 동안 자소서는 보조 링크, 주 버튼은 제안을 고르기 전이라 비활성이다
  expect(screen.getByRole('button', { name: '제안을 골라 진행하기' })).toBeDisabled();
});

it('채우기 시작이 403 GITHUB_UNAUTHORIZED 로 막히면 다시 찾아보기 대신 GitHub 다시 연결을 보인다', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  mount(unjudged(base));
  vi.spyOn(matchingApi, 'judge').mockResolvedValue(base);
  vi.spyOn(matchingApi, 'gapFill').mockRejectedValue(new ApiError('GITHUB_UNAUTHORIZED', '쓸 수 있는 GitHub 연결이 없습니다.', 403));
  fireEvent.click(await screen.findByRole('button', { name: '이 공고와 맞춰 보기' }));
  await waitFor(() => expect(judgeButton()).not.toBeInTheDocument());
  await advance(9000);
  expect(screen.getByText('자료를 찾으려면 GitHub 연결이 필요해요.')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '다시 연결' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '다시 찾아보기' })).not.toBeInTheDocument();
});

it('gapFill 칸이 없는 구서버 응답은 null 로 받고, 모르는 kind 의 한 일은 일반 문장으로 보인다', () => {
  expect(MatchDetail.parse(sampleDetail.data).gapFill).toBeNull();
  const parsed = MatchDetail.parse({
    ...sampleDetail.data,
    gapFill: { state: 'RUNNING', pollAfterMs: 1500, conditions: [{ conditionId: 'x', decision: null, decisionReason: null, steps: [{ kind: 'SOMETHING_NEW' }], proposal: null, result: null }] },
  });
  expect(parsed.gapFill?.conditions[0].steps[0]).toEqual(st('SOMETHING_NEW'));
  expect(parsed.gapFill?.conditions[0].forced).toBe(false);
  expect(stepText(st('SOMETHING_NEW'))).toBe('자료를 살펴봤어요');
  expect(stepText(st('GITHUB_FAILED', null, 'kafka'))).toBe("GitHub 검색이 잠시 안 돼 'kafka' 로 찾는 건 건너뛰었어요");
  expect(stepText(st('SOMETHING_NEW', 3, 'kafka'))).toBe("'kafka' 로 자료를 살펴봤어요 · 3개");
});

it('도는 동안은 pollAfterMs 마다 상세를 다시 읽고, 한꺼번에 온 일도 0.8초마다 하나씩 보이며 이미 보인 줄은 남는다', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  const [docker] = notMet;
  const first: MatchDetail = { ...base, gapFill: { state: 'RUNNING', pollAfterMs: 1000, conditions: [condition(docker.id, { decision: null, decisionReason: null })] } };
  const second: MatchDetail = { ...base, gapFill: done([condition(docker.id, { steps: [st('CANDIDATES_CHECKED', 12, null), st('DRAFT_CARDS_CHECKED', 2, null)], result: 'PROPOSED', proposal: proposalOf() })]) };
  const detail = vi.spyOn(matchingApi, 'detail').mockResolvedValueOnce(first).mockResolvedValue(second);
  mount(first, false);
  await screen.findByText('찾아볼지 정하고 있어요');
  expect(gapFillPollMs(first)).toBe(1000);
  expect(gapFillPollMs({ ...first, gapFill: { ...first.gapFill!, pollAfterMs: 100 } })).toBe(500);
  expect(gapFillPollMs(second)).toBe(false);
  await advance(1100);
  await waitFor(() => expect(detail).toHaveBeenCalledTimes(2));
  await advance(1000);
  expect(screen.getByText(/^찾아볼게요/)).toBeInTheDocument();
  expect(screen.queryByText('카드로 안 만든 후보 12개를 읽었어요')).not.toBeInTheDocument();
  await advance(800);
  expect(screen.getByText('카드로 안 만든 후보 12개를 읽었어요')).toBeInTheDocument();
  expect(screen.getByText(/^찾아볼게요/)).toBeInTheDocument();
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  await advance(1700);
  expect(screen.getByRole('checkbox', { name: /후보로 카드 만들기/ })).toBeInTheDocument();
  await advance(3000);
  expect(detail).toHaveBeenCalledTimes(2);
});

it('고른 제안만 진행하기로 넘긴다 — 후보로 카드 만들기는 레포마다 한 번 부르고, 갈 곳이 하나면 그 화면으로 간다', async () => {
  expect(planProceed([
    proposalOf(), proposalOf({ candidateId: 'c_pay' }),
    proposalOf({ action: 'ANALYZE_REPO', candidateId: null, repoId: 'r_counting' }),
    proposalOf({ repoId: null }),
  ])).toEqual({ createCards: [{ repoId: 'r_auth', candidateIds: ['c_51', 'c_pay'] }], continueCards: [], analyzeRepos: ['r_counting'] });
  // 카드가 작업으로 만들어져 바로 받은 카드가 없으면 그 레포의 카드 폴더에서 진행을 본다
  expect(proceedDestinations({ created: [{ repoId: 'r_auth', candidateIds: ['c_51'], jobId: 'job_9', cardIds: [] }], continueCards: [], started: [], failed: 0 }))
    .toEqual([{ path: '/cards/repo/r_auth' }]);
  const gapFill = done([
    condition(fiveIds[0], { result: 'PROPOSED', proposal: proposalOf() }),
    condition(fiveIds[1], { result: 'PROPOSED', proposal: proposalOf({ candidateId: 'c_pay', title: '5.12 결제모듈 6커밋' }) }),
    condition(fiveIds[2], { result: 'PROPOSED', proposal: proposalOf({ action: 'CONTINUE_CARD', candidateId: null, repoId: null, cardId: 'card_01', title: '로그인 세션 처리' }) }),
  ]);
  mount({ ...five, gapFill });
  const create = vi.spyOn(endpoints, 'createCardsFromCandidates').mockResolvedValue({ jobId: 'job_9', cardIds: ['card_90', 'card_91'] });
  expect(await screen.findByRole('button', { name: '제안을 골라 진행하기' })).toBeDisabled();
  expect(screen.getByRole('link', { name: '이 공고로 자소서 쓰기 →' })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('checkbox', { name: '후보로 카드 만들기 — 로그인 API 응답 속도 개선' }));
  fireEvent.click(screen.getByRole('checkbox', { name: '후보로 카드 만들기 — 5.12 결제모듈 6커밋' }));
  fireEvent.click(screen.getByRole('button', { name: '고른 2개 진행하기' }));
  await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
  expect(create).toHaveBeenCalledWith('r_auth', ['c_51', 'c_pay']);
  await screen.findByText('card page card_90');
  // 돌아와서 ✕ → ✓ 를 보이려고 모자랐던 조건을 적어 둔다
  expect(readProceeded(five.id)).toEqual(fiveIds);
});

it('한 레포에서 고른 후보가 서버가 한 번에 받는 8개를 넘으면 나눠 부르고, 같은 후보는 한 번만 넘긴다', () => {
  const ids = Array.from({ length: 9 }, (_, i) => `c_${i}`);
  const plan = planProceed([...ids, 'c_0'].map((candidateId) => proposalOf({ candidateId })));
  expect(plan.createCards).toEqual([
    { repoId: 'r_auth', candidateIds: ids.slice(0, 8) },
    { repoId: 'r_auth', candidateIds: ['c_8'] },
  ]);
});

it('넘기다 일부만 실패하면 성공한 카드 화면으로 가고 실패한 수만 알린다', async () => {
  const gapFill = done([
    condition(fiveIds[0], { result: 'PROPOSED', proposal: proposalOf() }),
    condition(fiveIds[1], { result: 'PROPOSED', proposal: proposalOf({ candidateId: 'c_board', repoId: 'r_board', title: '보드 후보' }) }),
  ]);
  mount({ ...five, gapFill });
  vi.spyOn(endpoints, 'createCardsFromCandidates').mockImplementation(async (repoId) => {
    if (repoId === 'r_board') throw new ApiError('INTERNAL_ERROR', '실패', 500);
    return { jobId: 'job_9', cardIds: ['card_90'] };
  });
  fireEvent.click(await screen.findByRole('checkbox', { name: '후보로 카드 만들기 — 로그인 API 응답 속도 개선' }));
  fireEvent.click(screen.getByRole('checkbox', { name: '후보로 카드 만들기 — 보드 후보' }));
  fireEvent.click(screen.getByRole('button', { name: '고른 2개 진행하기' }));

  await screen.findByText('card page card_90');
  expect(toast).toHaveBeenCalledWith(expect.stringContaining('1개는 넘기지 못했어요'), { tone: 'danger' });
});

it('하나도 넘기지 못하면 공고에 남아 실패를 알리고, 돌아와 견줄 표시를 남기지 않는다', async () => {
  mount({ ...five, gapFill: done([condition(fiveIds[0], { result: 'PROPOSED', proposal: proposalOf() })]) });
  vi.spyOn(endpoints, 'createCardsFromCandidates').mockRejectedValue(new ApiError('INTERNAL_ERROR', '실패', 500));
  fireEvent.click(await screen.findByRole('checkbox', { name: '후보로 카드 만들기 — 로그인 API 응답 속도 개선' }));
  fireEvent.click(screen.getByRole('button', { name: '고른 1개 진행하기' }));

  expect(await screen.findByText('제안을 넘기지 못했어요. 다시 눌러 주세요.')).toBeInTheDocument();
  expect(readProceeded(five.id)).toBeNull();
});

it('진행하기 뒤 돌아오면 묻지 않고 새 카드로 다시 맞춰 보고, ✕ 였다가 ✓ 가 된 조건에 새로 채움을 붙인다', async () => {
  const [docker, k8s] = notMet;
  rememberProceeded(base.id, [docker.id, k8s.id]);
  const after: MatchDetail = { ...base, conditions: base.conditions.map((c) => (c.id === docker.id ? { ...c, status: 'MET' as const, metBy: base.conditions[0].metBy } : c)) };
  mount(unjudged(base));
  const judge = vi.spyOn(matchingApi, 'judge').mockResolvedValue(after);
  await waitFor(() => expect(judge).toHaveBeenCalledWith(base.id));
  expect(judgeButton()).not.toBeInTheDocument();
  await screen.findByText('모자랐던 조건 2개 중 1개를 채웠어요');
  expect(screen.getAllByText('새로 채움')).toHaveLength(1);
  // 돌아온 뒤에는 결과를 차례로 펼치지 않고 바로 다 보인다
  expect(tiles()).toBe(5);
  expect(readProceeded(base.id)).toBeNull();
});

it('진행하기 뒤 돌아왔는데 새 카드가 아직 확정 전이면 다시 맞춰 보지 않고, 확정한 뒤 돌아올 때까지 표시를 남긴다', async () => {
  const [docker, k8s] = notMet;
  rememberProceeded(base.id, [docker.id, k8s.id]);
  mount(base);
  const judge = vi.spyOn(matchingApi, 'judge').mockResolvedValue(base);

  await screen.findByText('새로 만든 카드를 확정하면 다시 맞춰 볼게요');
  expect(judge).not.toHaveBeenCalled();
  expect(readProceeded(base.id)).toEqual([docker.id, k8s.id]);
});

it('목은 맞춰 보기 전엔 판단을 숨기고, 채우기를 시작하면 시간이 지나며 찾음·찾지 않음·못 찾음·시간 초과가 한 번씩 나온 뒤 끝난다', () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-01T00:00:00Z'));
  const id = sampleList.data[3].id;
  const q = new URLSearchParams();
  const read = () => MatchDetail.parse(handle('GET', `/matches/${id}`, q, {}, true).data);
  const before = read();
  expect(before.needsJudgement).toBe(true);
  expect(before.gapFill).toBeNull();
  expect(handle('POST', `/matches/${id}/gap-fill`, q, {}, true).status).toBe(409);
  const judged = MatchDetail.parse(handle('POST', `/matches/${id}/judgement`, q, {}, true).data);
  expect(judged.needsJudgement).toBe(false);
  expect(judged.conditions.filter((c) => c.status === 'NOT_MET')).toHaveLength(4);
  const started = MatchDetail.parse(handle('POST', `/matches/${id}/gap-fill`, q, {}, true).data);
  expect(started.gapFill).toMatchObject({ state: 'RUNNING', pollAfterMs: 1500 });
  expect(started.gapFill!.conditions.every((c) => c.decision === null && c.steps.length === 0 && c.result === null)).toBe(true);
  vi.setSystemTime(new Date('2026-11-01T00:00:05Z'));
  const mid = read();
  expect(mid.gapFill!.state).toBe('RUNNING');
  expect(mid.gapFill!.conditions.map((c) => c.decision)).toEqual(['SEARCH', 'SKIP', 'SEARCH', 'SEARCH']);
  expect(mid.gapFill!.conditions[0].steps.length).toBeGreaterThan(0);
  expect(mid.gapFill!.conditions[0].result).toBeNull();
  vi.setSystemTime(new Date('2026-11-01T00:00:12Z'));
  const end = read();
  expect(end.gapFill).toMatchObject({ state: 'DONE', pollAfterMs: null });
  expect(end.gapFill!.conditions.map((c) => c.result)).toEqual(['PROPOSED', 'SKIPPED', 'NOT_FOUND', 'TIMED_OUT']);
  expect(end.gapFill!.conditions[0].proposal).toMatchObject({ action: 'CREATE_CARD', candidateId: 'c_51', repoId: 'r_auth' });
  // 찾지 않기로 한 조건만 다시 찾으면 그 조건만 다시 돌고 나머지 결론은 그대로다
  const skipped = end.gapFill!.conditions[1].conditionId;
  const again = MatchDetail.parse(handle('POST', `/matches/${id}/gap-fill`, q, { conditionIds: [skipped] }, true).data);
  expect(again.gapFill!.state).toBe('RUNNING');
  expect(again.gapFill!.conditions.map((c) => c.result)).toEqual(['PROPOSED', null, 'NOT_FOUND', 'TIMED_OUT']);
  vi.setSystemTime(new Date('2026-11-01T00:00:17Z'));
  const redone = read();
  expect(redone.gapFill!.conditions[1]).toMatchObject({ decision: 'SKIP', forced: true, result: 'PROPOSED' });
  expect(redone.gapFill!.conditions[1].proposal?.action).toBe('CONTINUE_CARD');
  // 찾지 않기로 한 조건이 아니면 400, 도는 중이면 409
  expect(handle('POST', `/matches/${id}/gap-fill`, q, { conditionIds: [end.gapFill!.conditions[0].conditionId] }, true).status).toBe(400);
  handle('POST', `/matches/${id}/gap-fill`, q, {}, true);
  expect(handle('POST', `/matches/${id}/gap-fill`, q, { conditionIds: [skipped] }, true).status).toBe(409);
});

it('목은 제안대로 만든 카드가 확정되면 다시 맞춰 보기 전으로 돌아가고, 다시 맞춰 보면 그 조건을 그 카드로 채운다', () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-01T00:00:00Z'));
  const id = sampleList.data[3].id;
  const q = new URLSearchParams();
  handle('POST', `/matches/${id}/judgement`, q, {}, true);
  handle('POST', `/matches/${id}/gap-fill`, q, {}, true);
  vi.setSystemTime(new Date('2026-11-01T00:00:12Z'));
  const end = MatchDetail.parse(handle('GET', `/matches/${id}`, q, {}, true).data);
  const target = end.gapFill!.conditions[0];
  expect(end.conditions.find((c) => c.id === target.conditionId)?.status).toBe('NOT_MET');
  const made = handle('POST', '/repos/r_auth/cards', q, { candidateIds: [target.proposal!.candidateId] }, true).data as { cardIds: string[] };
  const card = db.cards.find((c) => c.id === made.cardIds[0])!;
  card.status = 'CONFIRMED';
  card.versions[0].action = '배포 자동화를 만들었다';
  expect(MatchDetail.parse(handle('GET', `/matches/${id}`, q, {}, true).data).needsJudgement).toBe(true);
  const rejudged = MatchDetail.parse(handle('POST', `/matches/${id}/judgement`, q, {}, true).data);
  const filled = rejudged.conditions.find((c) => c.id === target.conditionId)!;
  expect(filled.status).toBe('MET');
  expect(filled.metBy.map((m) => m.cardId)).toEqual([card.id]);
});

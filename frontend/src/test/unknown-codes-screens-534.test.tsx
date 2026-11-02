import { cleanup, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthGate } from '@/app/AuthGate';
import { CardPage } from '@/features/cards/CardPage';
import { CardsListPage } from '@/features/cards/CardsListPage';
import { HomePage } from '@/features/home/HomePage';
import { JobStatusPage } from '@/features/jobs/JobStatusPage';
import { MatchListPage } from '@/features/match/MatchListPage';
import { AnalyzePage } from '@/features/repos/AnalyzePage';
import { ReposPage } from '@/features/repos/ReposPage';
import { reportSkipped } from '@/lib/skippedItems';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';
import sampleList from '../../docs/examples/matching/list.json';

/**
 * #534 — 서버가 화면이 모르는 값을 보내도 화면이 멈추지 않는다.
 * 응답은 실제 fetch → api() → zod 를 그대로 지나 화면에 닿는다. 모르는 값 하나만 목 응답에 섞는다.
 */
vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/jobWatcher', () => ({ releaseJobToast: vi.fn(), suppressJobToast: vi.fn(), JobWatcher: () => null }));
beforeEach(() => { resetDb(); reportSkipped('cards', 0); localStorage.clear(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

type Json = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any
const mock = (path: string, method = 'GET', body: Record<string, unknown> = {}): { data: unknown; error: unknown; status: number } => {
  const [p, qs] = path.split('?');
  return handle(method, p, new URLSearchParams(qs ?? ''), body, true) as { data: unknown; error: unknown; status: number };
};
/** 경로별로 응답을 바꿔 끼운다. 나머지는 목 서버 그대로 */
function serve(overrides: Record<string, unknown>) {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    const path = url.pathname.replace(/^\/api/, '');
    if (path in overrides) return new Response(JSON.stringify({ data: overrides[path], error: null }), { status: 200 });
    const r = handle(init?.method ?? 'GET', path, url.searchParams, init?.body ? JSON.parse(String(init.body)) : {}, true);
    return new Response(JSON.stringify({ data: r.data, error: r.error }), { status: r.status });
  });
}
function mount(element: React.ReactElement, path: string, route = path.split('?')[0]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}>
    <Routes><Route path={route} element={element} /><Route path="*" element={<p>다른 화면</p>} /></Routes>
  </MemoryRouter></QueryClientProvider>);
}
const job = (extra: Json = {}): Json => ({
  jobId: 'job_x', type: 'ANALYZE', state: 'FAILED', partial: false, steps: [], errorCode: 'GITHUB_UNAVAILABLE', retryable: false, retryAfterSec: null,
  startedAt: '2026-10-30T00:00:00Z', updatedAt: '2026-10-30T00:00:00Z', finishedAt: '2026-10-30T00:00:08Z', result: { repoId: 'r_auth' }, pollAfterMs: 2000, ...extra,
});
const cards = () => mock('/cards').data as Json[];
const alerts = () => screen.queryAllByRole('alert');

describe('정리 실패 화면 — 서버가 모르는 오류 코드를 보내도 일반 오류 문구로 뜬다', () => {
  it('아는 코드는 그 코드의 문구와 연결 버튼을 보인다 (비교 기준)', async () => {
    serve({ '/jobs/job_x': job() });
    mount(<AnalyzePage />, '/repos/r_auth/run?job=job_x', '/repos/:repoId/run');
    expect(await screen.findByRole('heading', { name: 'GitHub에서 답이 오지 않았어요' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 연결' })).toBeInTheDocument();
  });

  it('모르는 코드는 일반 오류 문구이고, 아는 코드에만 붙는 연결 버튼은 붙지 않는다', async () => {
    serve({ '/jobs/job_x': job({ errorCode: 'GITHUB_QUOTA_EXCEEDED' }) });
    mount(<AnalyzePage />, '/repos/r_auth/run?job=job_x', '/repos/:repoId/run');
    expect(await screen.findByRole('heading', { name: /서버가 작업을 끝내지 못했어요|서버에서 처리하지 못했어요/ })).toBeInTheDocument();
    expect(screen.getByText(/(작업을 처리하지 못했어요|이번 요청은 끝내지 못했어요)\. 다른 레포를 고르거나 직접 작성해 주세요\./)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '다시 연결' })).not.toBeInTheDocument();
    // 화면이 멈추지 않고 할 수 있는 일이 그대로 있다
    expect(screen.getByText('다른 레포 고르기')).toBeInTheDocument();
    expect(screen.getByText(/^(직접 쓰기|GitHub 없이 시작)$/)).toBeInTheDocument();
    // "확인하지 못했어요" 오류 화면으로 떨어지지 않았다
    expect(screen.queryByText('응답을 확인하지 못했어요')).not.toBeInTheDocument();
  });

  it('재시도 가능 여부는 코드를 모르더라도 서버가 준 retryable 을 따른다', async () => {
    serve({ '/jobs/job_x': job({ errorCode: 'GITHUB_QUOTA_EXCEEDED', retryable: true }) });
    mount(<AnalyzePage />, '/repos/r_auth/run?job=job_x', '/repos/:repoId/run');
    expect(await screen.findByRole('button', { name: '다시 시도' })).toBeEnabled();
  });

  it('부분 결과에 모르는 코드가 붙어도 읽은 범위의 후보로 가는 길이 남는다', async () => {
    serve({ '/jobs/job_x': job({ state: 'SUCCEEDED', partial: true, errorCode: 'GITHUB_QUOTA_EXCEEDED' }) });
    mount(<AnalyzePage />, '/repos/r_auth/run?job=job_x', '/repos/:repoId/run');
    expect(await screen.findByRole('button', { name: '읽은 범위의 후보 보기' })).toBeInTheDocument();
    expect(screen.getByText(/서버가 작업을 끝내지 못했어요|서버에서 처리하지 못했어요/)).toBeInTheDocument();
  });

  it('작업 상태 화면도 같다 — 일반 오류 문구가 뜨고 연결 버튼은 없다', async () => {
    serve({ '/jobs/job_x': job({ errorCode: 'GITHUB_QUOTA_EXCEEDED' }) });
    mount(<JobStatusPage />, '/jobs/job_x', '/jobs/:jobId');
    expect(await screen.findByText(/서버가 작업을 끝내지 못했어요|서버에서 처리하지 못했어요/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'GitHub 다시 연결' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '홈으로' })).toBeInTheDocument();
  });

  it('남긴 칸(작업 상태)을 모르면 정리가 끝난 것처럼 그리지 않고 "응답을 확인하지 못했어요"로 떨어진다', async () => {
    serve({ '/jobs/job_x': job({ state: 'PAUSED' }) });
    mount(<AnalyzePage />, '/repos/r_auth/run?job=job_x', '/repos/:repoId/run');
    expect(await screen.findByRole('heading', { name: /(작업|정리|진행) 상태를 확인하지 못했어요/ })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '응답을 확인하지 못했어요' })).toBeInTheDocument();
    expect(screen.getByText(/조회 실패는 정리 완료를 뜻하지 않아요|정리가 끝났는지 아직 알 수 없어요/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '레포 목록' })).toBeInTheDocument();
  });
});

describe('카드 목록 — 카드 한 장에 모르는 값이 섞여도 나머지는 보인다', () => {
  // 카드 목록의 기본 보기는 다른 PR 이 바꾼다(폴더 보기). 이 시험은 카드 한 장 한 장이 그대로 보이는 '최근순'으로 고정한다.
  beforeEach(() => { localStorage.setItem('gitory.cards.view', JSON.stringify('recent')); });
  const withUnknownKind = (index = 1) => cards().map((c, i) => (i === index ? { ...c, kind: 'HYBRID' } : c));

  it('그 장만 빼고 보여 주고, 몇 장을 뺐는지 알린다', async () => {
    const list = withUnknownKind();
    serve({ '/cards': list });
    mount(<CardsListPage />, '/cards');
    expect(await screen.findByText('카드 1장을 표시하지 못했어요')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(`경험 카드 ${list.length - 1}`);
    for (const [i, c] of list.entries()) {
      if (i === 1) expect(screen.queryByText(c.title)).not.toBeInTheDocument();
      else expect(screen.getAllByText(c.title).length).toBeGreaterThan(0);
    }
    expect(screen.getByRole('status')).toHaveTextContent('나머지 카드는 그대로 볼 수 있어요');
    // 오류 화면이나 빈 상태로 떨어지지 않는다
    expect(alerts()).toHaveLength(0);
    expect(screen.queryByText('아직 카드가 없어요')).not.toBeInTheDocument();
  });

  it('모르는 카드 상태도 같다 — 확정으로 읽지 않고 그 장만 뺀다', async () => {
    const list = cards().map((c, i) => (i === 0 ? { ...c, status: 'ARCHIVED' } : c));
    serve({ '/cards': list });
    mount(<CardsListPage />, '/cards');
    expect(await screen.findByText('카드 1장을 표시하지 못했어요')).toBeInTheDocument();
    expect(screen.queryByText(list[0].title)).not.toBeInTheDocument();
  });

  it('모르는 값이 없으면 안내가 없다', async () => {
    serve({});
    mount(<CardsListPage />, '/cards');
    expect(await screen.findByText(cards()[0].title)).toBeInTheDocument();
    expect(screen.queryByText(/표시하지 못했어요/)).not.toBeInTheDocument();
  });

  it('모르는 태그는 그 태그만 빠지고 카드는 그대로 보인다', async () => {
    const list = cards().map((c, i) => (i === 0 ? { ...c, techs: ['JAVA', 'COBOL'] } : c));
    serve({ '/cards': list });
    mount(<CardsListPage />, '/cards');
    expect(await screen.findByText(list[0].title)).toBeInTheDocument();
    expect(screen.queryByText(/표시하지 못했어요/)).not.toBeInTheDocument();
    expect(screen.queryByText('COBOL')).not.toBeInTheDocument();
  });

  it('한 장도 읽을 수 없으면 "카드가 없다"고 하지 않고 오류 화면으로 떨어진다', async () => {
    serve({ '/cards': cards().map((c) => ({ ...c, kind: 'HYBRID' })) });
    mount(<CardsListPage />, '/cards');
    expect(await screen.findByRole('heading', { name: '응답을 확인하지 못했어요' })).toBeInTheDocument();
    expect(screen.queryByText('아직 카드가 없어요')).not.toBeInTheDocument();
    expect(screen.queryByText(/장을 표시하지 못했어요/)).not.toBeInTheDocument();
  });

  it('홈도 같은 안내를 보이고, 읽은 카드는 그대로 둔다', async () => {
    serve({ '/cards': withUnknownKind(0) });
    mount(<HomePage />, '/');
    expect(await screen.findByText('카드 1장을 표시하지 못했어요')).toBeInTheDocument();
    expect(screen.queryByText(cards()[0].title)).not.toBeInTheDocument();
    expect(screen.getAllByText(cards()[2].title).length).toBeGreaterThan(0);
  });
});

describe('그 밖의 화면 — 모르는 값 하나로 앱이 막히지 않는다', () => {
  it('내 정보의 요금제를 몰라도 로그인 뒤 화면이 열린다', async () => {
    serve({ '/me': { ...(mock('/me').data as Json), plan: 'PRO' } });
    mount(<AuthGate><p>본문</p></AuthGate>, '/');
    expect(await screen.findByText('본문')).toBeInTheDocument();
    expect(alerts()).toHaveLength(0);
  });

  it('공고의 분야·경력 구분을 몰라도 목록이 열리고 기타·경력 표시 없음으로 보인다', async () => {
    const list = sampleList.data.map((x, i) => (i === 0 ? { ...x, category: 'LEGAL', experience: { level: 'ANY', minYears: null } } : x));
    serve({ '/matches': list });
    mount(<MatchListPage />, '/match');
    const card = (await screen.findByRole('heading', { name: list[0].role })).closest('article') as HTMLElement;
    expect(within(card).getByText('기타')).toBeInTheDocument();
    expect(within(card).getByText('경력 표시 없음')).toBeInTheDocument();
    // 아는 값의 공고는 그대로다
    const other = (await screen.findByRole('heading', { name: list[1].role })).closest('article') as HTMLElement;
    expect(within(other).getByText('백엔드')).toBeInTheDocument();
    expect(within(other).getByText('신입 가능')).toBeInTheDocument();
  });

  it('레포의 기여 구분을 몰라도 레포 목록이 열린다', async () => {
    const repos = (mock('/repos').data as Json[]).map((r, i) => (i === 0 ? { ...r, contribution: { ...r.contribution, level: 'TRIVIAL' } } : r));
    serve({ '/repos': repos });
    mount(<ReposPage />, '/repos');
    expect((await screen.findAllByText(new RegExp(repos[0].name))).length).toBeGreaterThan(0);
    expect(screen.queryByText('응답을 확인하지 못했어요')).not.toBeInTheDocument();
  });

  it('카드 초안 실패 이유를 몰라도 카드가 열리고 일반 안내가 뜬다', async () => {
    const card = { ...(mock('/cards/card_01').data as Json), draftFailure: 'AI_QUOTA_EXCEEDED' };
    serve({ '/cards/card_01': card });
    mount(<CardPage />, '/cards/card_01', '/cards/:cardId');
    expect(await screen.findByText('AI 초안을 만들지 못했어요')).toBeInTheDocument();
    expect(screen.getByText(/초안을 만드는 중에 문제가 났어요/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'GitHub 다시 연결' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '초안 다시 만들기' })).toBeInTheDocument();
  });

  it('남긴 칸(근거 종류)을 모르면 가짜 근거를 그리지 않고 오류 화면으로 떨어진다', async () => {
    const card = mock('/cards/card_01').data as Json;
    card.evidence[0].type = 'WIKI';
    serve({ '/cards/card_01': card });
    mount(<CardPage />, '/cards/card_01', '/cards/:cardId');
    expect(await screen.findByRole('heading', { name: '응답을 확인하지 못했어요' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '경험 카드 목록' })).toBeInTheDocument();
    expect(screen.queryByText(/커밋/)).not.toBeInTheDocument();
  });
});

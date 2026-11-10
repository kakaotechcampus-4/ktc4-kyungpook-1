import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { CandidateBoard, Job, RepoDetail } from '@/api/schemas';
import { JobLiveness } from '@/components/JobLiveness';
import { CandidateBoardPage } from '@/features/candidates/CandidateBoardPage';
import { AnalyzePage } from '@/features/repos/AnalyzePage';
import { elapsed } from '@/lib/format';
import { jobActivity } from '@/lib/jobView';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/jobWatcher', () => ({ releaseJobToast: vi.fn(), suppressJobToast: vi.fn() }));

const req = (method: string, path: string) => handle(method, path, new URLSearchParams(), {}, true);
const NOW = Date.parse('2026-10-18T09:00:00Z');
const job = (overrides: Record<string, unknown> = {}) => Job.parse({
  jobId: 'job', type: 'ANALYZE', state: 'RUNNING', partial: false,
  steps: [{ key: 'COMMITS', state: 'RUNNING', done: 12, total: 33 }, { key: 'PR_REVIEW', state: 'QUEUED', done: 0, total: null },
    { key: 'COMPRESS', state: 'QUEUED', done: 0, total: null }, { key: 'REASON', state: 'QUEUED', done: 0, total: null }],
  errorCode: null, retryable: null, retryAfterSec: null, result: null,
  startedAt: new Date(NOW - 134_000).toISOString(), updatedAt: new Date(NOW - 8_000).toISOString(), finishedAt: null, pollAfterMs: 2000, ...overrides,
});

beforeEach(() => { resetDb(); vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] }); vi.setSystemTime(NOW); });
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); });

describe('경과 시간 표시 (멘토 피드백 — 진행 중인지 멈춘 건지 모르겠다)', () => {
  it('초·분·시간으로 읽히고 음수·소수는 0 이상 정수로 맞춘다', () => {
    expect([0, 8, 59, 60, 134, 3599, 3600, 3900, 7200].map(elapsed)).toEqual(['0초', '8초', '59초', '1분', '2분 14초', '59분 59초', '1시간', '1시간 5분', '2시간']);
    expect(elapsed(-5)).toBe('0초');
    expect(elapsed(8.9)).toBe('8초');
  });

  it('활동 값은 시계가 어긋나도 음수가 되지 않는다', () => {
    expect(jobActivity({ startedAt: new Date(NOW - 134_000).toISOString() }, NOW - 3_000, NOW)).toEqual({ elapsedSec: 134, sinceCheckSec: 3 });
    expect(jobActivity({ startedAt: new Date(NOW + 60_000).toISOString() }, NOW + 5_000, NOW)).toEqual({ elapsedSec: 0, sinceCheckSec: 0 });
  });

  it('진행 중이면 시작한 지 얼마인지와 마지막 확인 시각을 보이고, 1초마다 흐른다', () => {
    render(<JobLiveness job={job()} checkedAt={NOW - 3_000} />);
    expect(screen.getByText('2분 14초째 진행 중이에요')).toBeInTheDocument();
    expect(screen.getByText('마지막 확인 3초 전')).toBeInTheDocument();
    act(() => { vi.advanceTimersByTime(1000); });
    expect(screen.getByText('2분 15초째 진행 중이에요')).toBeInTheDocument();
    expect(screen.getByText('마지막 확인 4초 전')).toBeInTheDocument();
  });

  it('대기열에 있을 때는 그렇게 말하고, 끝난 작업에는 아무것도 그리지 않는다', () => {
    const { rerender, container } = render(<JobLiveness job={job({ state: 'QUEUED' })} checkedAt={NOW} />);
    expect(screen.getByText('대기열에서 2분 14초째 기다리는 중이에요')).toBeInTheDocument();
    rerender(<JobLiveness job={job({ state: 'SUCCEEDED', finishedAt: new Date(NOW).toISOString() })} checkedAt={NOW} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('흐르는 글을 낭독 영역으로 만들지 않는다 (매초 읽지 않게)', () => {
    render(<JobLiveness job={job()} checkedAt={NOW} />);
    expect(screen.getByText('2분 14초째 진행 중이에요').closest('[aria-live], [role="status"], [role="alert"]')).toBeNull();
  });
});

function mountAnalyze(data: Job, repoId = 'r_auth') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(keys.repo(repoId), RepoDetail.parse(req('GET', `/repos/${repoId}`).data));
  client.setQueryData(keys.job('job'), data);
  vi.spyOn(endpoints, 'job').mockResolvedValue(data);
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[`/repos/${repoId}/run?job=job`]}><Routes>
    <Route path="/repos/:repoId/run" element={<AnalyzePage />} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return client;
}

describe('분석 진행 화면', () => {
  it('진행 중에는 경과 시간·마지막 확인이 보이고, 지금 읽는 단계만 상자로 펼쳐 큰 숫자를 보인다', () => {
    mountAnalyze(job());
    expect(screen.getByText(/째 진행 중이에요/)).toBeInTheDocument();
    expect(screen.getByText(/마지막 확인/)).toBeInTheDocument();
    expect(screen.queryByRole('progressbar')).toBeNull();                       // 막대 대신 점과 선(#789)
    const now = screen.getByText('커밋 읽기').closest('li') as HTMLElement;
    expect(now).toHaveClass('tl__item--now');                                    // 지금 읽는 단계에만
    expect(screen.getByText('PR과 리뷰 읽기').closest('li')).not.toHaveClass('tl__item--now');
    expect(within(now).getByText((_, el) => el?.classList.contains('tl__count') === true && el.textContent === '12 / 33개')).toBeInTheDocument(); // 서버가 준 숫자는 그대로 — 지어낸 분모가 아니다
  });

  it('총량을 모르는 단계는 분모를 지어내지 않는다', () => {
    mountAnalyze(job({ steps: [{ key: 'COMMITS', state: 'DONE', done: 33, total: 33 }, { key: 'PR_REVIEW', state: 'RUNNING', done: 5, total: null },
      { key: 'COMPRESS', state: 'QUEUED', done: 0, total: null }, { key: 'REASON', state: 'QUEUED', done: 0, total: null }] }));
    const now = screen.getByText('PR과 리뷰 읽기').closest('li') as HTMLElement;
    expect(now.querySelector('.tl__count')).toHaveTextContent('5건 읽음');
    expect(now.textContent).not.toMatch(/5 \/ /);
  });
});

describe('실패한 분석 화면 — 다시 시도할 수 없으면 그 항목을 두지 않는다 (멘토 피드백)', () => {
  const failed = (extra: Record<string, unknown>) => job({ state: 'FAILED', partial: false, finishedAt: new Date(NOW - 5_000).toISOString(), errorCode: 'INTERNAL_ERROR', ...extra });

  it('서버가 재시도를 열지 않으면 "다시 시도" 줄도 "시도할 수 없어요" 문구도 없고 할 수 있는 일만 남는다', () => {
    for (const retryable of [false, null]) {
      cleanup();
      mountAnalyze(failed({ retryable }));
      expect(screen.queryByText('다시 시도')).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument();
      expect(screen.queryByText(/시도할 수 없어요/)).not.toBeInTheDocument();
      expect(screen.getByText('이렇게 해 볼 수 있어요')).toBeInTheDocument();
      expect(screen.getByRole('link', { name: '레포 목록으로' })).toHaveAttribute('href', '/repos');     // 다른 레포 고르기
      expect(screen.getByRole('link', { name: '직접 작성하기' })).toHaveAttribute('href', '/cards/new'); // GitHub 없이 시작
    }
  });

  it('서버가 재시도를 열었으면 누를 수 있는 "다시 시도"가 있다', () => {
    mountAnalyze(failed({ retryable: true }));
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeEnabled();
  });

  it('요청 한도로 기다려야 하면 남은 시간이 적힌 비활성 버튼을 보인다 (곧 눌러도 된다는 뜻이니 남긴다)', () => {
    mountAnalyze(failed({ errorCode: 'GITHUB_RATE_LIMITED', retryable: true, retryAfterSec: 720, finishedAt: new Date(NOW - 1_000).toISOString() }));
    expect(screen.getByText(/뒤에 눌러 주세요/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^\d+분$/ })).toBeDisabled();
  });
});

describe('후보 보드 — 카드로 만든 후보 (멘토 피드백: "사용됨 보이기" 버튼이 이해되지 않는다)', () => {
  function mountBoard(mutate?: (board: CandidateBoard) => CandidateBoard, path = '/repos/r_auth/candidates') {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    const board = CandidateBoard.parse(req('GET', '/repos/r_auth/candidates').data);
    client.setQueryData(keys.candidates('r_auth'), mutate ? mutate(board) : board);
    client.setQueryData(keys.repo('r_auth'), RepoDetail.parse(req('GET', '/repos/r_auth').data));
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes>
      <Route path="/repos/:repoId/candidates" element={<CandidateBoardPage />} />
    </Routes></MemoryRouter></QueryClientProvider>);
    return board;
  }

  it('상태에 따라 글자가 뒤바뀌는 "사용됨 보이기/숨기기" 버튼이 없다', () => {
    mountBoard();
    expect(screen.queryByRole('button', { name: /사용됨 (보이기|숨기기)/ })).not.toBeInTheDocument();
  });

  it('이미 카드로 만든 후보는 고르는 타일에 없고, 아래 "이미 카드로 만든 일" 묶음에 한 줄씩 카드 보기 링크와 함께 있다', () => {
    const board = mountBoard();
    const used = board.candidates.filter((c) => c.status === 'USED');
    expect(used.length).toBeGreaterThan(0);
    const group = screen.getByRole('region', { name: /이미 카드로 만든 일/ });
    for (const candidate of used) {
      expect(screen.queryByRole('button', { name: new RegExp(candidate.title) })).not.toBeInTheDocument();
      const row = within(group).getByText(candidate.title).closest('li') as HTMLElement;
      expect(within(row).getByRole('link', { name: '카드 보기' })).toHaveAttribute('href', `/cards/${candidate.usedByCardId}`);
    }
  });

  it('첫 묶음 머리의 수는 고를 수 있는 후보만 센다 — 카드로 만든 후보와 제외한 후보는 빼고 "5개, 전체 N개"', () => {
    const board = mountBoard((b) => ({ ...b, candidates: b.candidates.map((c, i) => i === 0 && c.status === 'NEW' ? { ...c, status: 'EXCLUDED' as const } : c) }));
    const open = board.candidates.filter((c) => c.status === 'NEW').length - 1;
    const group = screen.getByRole('region', { name: /먼저 카드로 만들어 볼 경험/ });
    expect(within(group).getByText(`5개, 전체 ${open}개`)).toBeInTheDocument();
  });

  it('추천도 낮은 후보는 나머지 쪽에 있고, 타일 안에 "추천도 낮음"이 글자로만 붙는다', () => {
    const board = mountBoard();
    const weak = board.candidates.find((c) => c.status === 'NEW' && c.weak)!;
    expect(screen.queryByText(weak.title)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /^나머지 \d+개 더 보기$/ }));
    const tile = screen.getByText(weak.title).closest('.cand-tile') as HTMLElement;
    expect(within(tile).getByText('추천도 낮음')).toHaveClass('cand-tile__weak');
  });

  it('쓸 수 있는 후보가 하나도 없고 전부 카드로 만들었으면 그렇게 말한다', () => {
    mountBoard((board) => ({ ...board, candidates: board.candidates.map((c) => ({ ...c, status: 'USED' as const, usedByCardId: 'card_03' })) }));
    expect(screen.getByText('남은 후보가 없어요')).toBeInTheDocument();
    expect(screen.getByText(/후보를 모두 카드로 만들었어요/)).toBeInTheDocument();
    expect(screen.getByRole('region', { name: /이미 카드로 만든 일/ })).toBeInTheDocument();
  });

  it('후보마다 있던 제외 버튼·검색창·추천 후보 선택은 없고, 예전에 제외한 후보가 있을 때만 "제외 N개 복원"이 보인다', () => {
    mountBoard();
    expect(screen.queryByRole('button', { name: '제외' })).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '추천 후보 선택' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^제외 \d+개 복원$/ })).not.toBeInTheDocument();
    cleanup();
    mountBoard((b) => ({ ...b, candidates: b.candidates.map((c, i) => i < 2 && c.status === 'NEW' ? { ...c, status: 'EXCLUDED' as const } : c) }));
    expect(screen.getByRole('button', { name: '제외 2개 복원' })).toBeInTheDocument();
  });
});

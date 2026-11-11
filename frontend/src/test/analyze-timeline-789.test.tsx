import { cleanup, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { Job, RepoDetail } from '@/api/schemas';
import { AnalyzePage } from '@/features/repos/AnalyzePage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/jobWatcher', () => ({ releaseJobToast: vi.fn(), suppressJobToast: vi.fn() }));

const req = (method: string, path: string) => handle(method, path, new URLSearchParams(), {}, true);
const NOW = Date.now();
const STEPS = [
  { key: 'PR_REVIEW', state: 'DONE', done: 31, total: 31 },
  { key: 'COMMITS', state: 'RUNNING', done: 48, total: 214 },
  { key: 'COMPRESS', state: 'QUEUED', done: 0, total: null },
  { key: 'REASON', state: 'QUEUED', done: 0, total: null },
];
const job = (over: Record<string, unknown> = {}) => Job.parse({
  jobId: 'job', type: 'ANALYZE', state: 'RUNNING', partial: false, steps: STEPS, errorCode: null, retryable: null, retryAfterSec: null, result: null,
  startedAt: new Date(NOW - 72_000).toISOString(), updatedAt: new Date(NOW - 2_000).toISOString(), finishedAt: null, pollAfterMs: 2000, ...over,
});
const commit = (seq: number, title: string, excluded: string | null = null) => ({ seq, step: 'COMMITS', kind: 'COMMIT', title, sha: `sha${seq}`, files: 3, excluded });
const pr = (seq: number, number: number, title: string) => ({ seq, step: 'PR_REVIEW', kind: 'PULL_REQUEST', number, title, reviews: 2 });

function mount(data: Job, repoId = 'r_auth') {
  vi.spyOn(endpoints, 'job').mockResolvedValue(data);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(keys.repo(repoId), RepoDetail.parse(req('GET', `/repos/${repoId}`).data));
  client.setQueryData(keys.job('job'), data);
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[`/repos/${repoId}/run?job=job`]}><Routes>
    <Route path="/repos/:repoId/run" element={<AnalyzePage />} />
  </Routes></MemoryRouter></QueryClientProvider>);
}
const item = (label: string) => screen.getByText(label).closest('li') as HTMLElement;

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe('정리 진행 화면 — 타임라인 (#789)', () => {
  it('지금 읽는 단계만 상자로 펼쳐져 큰 숫자를 보이고, 끝난 단계는 작은 숫자 한 줄로 접힌다', () => {
    mount(job());
    expect(document.querySelectorAll('.tl__item')).toHaveLength(4);
    expect(document.querySelectorAll('.tl__item--now')).toHaveLength(1);
    const now = item('커밋 읽기');
    expect(now).toHaveClass('tl__item--now');
    expect(now.querySelector('.tl__count')).toHaveTextContent('48 / 214개');
    expect(within(now).getByText('커밋 메시지와 바뀐 파일을 하나씩 읽고 있어요')).toBeInTheDocument();
    const done = item('PR과 리뷰 읽기');
    expect(done).toHaveClass('tl__item--done');
    expect(done.querySelector('.tl__meta')).toHaveTextContent('31건');
    expect(done.querySelector('.tl__count')).toBeNull();
    expect(item('후보로 추리기')).toHaveClass('tl__item--wait');
    expect(item('후보로 추리기').querySelector('.tl__meta, .tl__count')).toBeNull();
  });

  it('방금 읽은 줄은 지금 단계 안에만 최근 셋이 흐르고, 뺀 커밋은 줄을 긋는다', () => {
    mount(job({ activity: [pr(1, 12, '세션 만료 처리'), commit(2, '첫 커밋'), commit(3, '둘째 커밋'), commit(4, "Merge branch 'develop'", 'MERGE_COMMIT'), commit(5, '넷째 커밋')] }));
    const stream = item('커밋 읽기').querySelector('.tl__stream') as HTMLElement;
    const lines = Array.from(stream.querySelectorAll('.sline'));
    expect(lines.map((l) => l.querySelector('.sline__text')!.textContent)).toEqual(['둘째 커밋', "Merge branch 'develop'", '넷째 커밋']);
    expect(lines[1]).toHaveClass('sline--skip');
    expect(within(stream).getByText('머지 커밋이라 뺐어요')).toBeInTheDocument();
    expect(lines[0]).toHaveClass('sline--old');
    expect(lines[2]).not.toHaveClass('sline--old');
    expect(item('PR과 리뷰 읽기').querySelector('.tl__stream')).toBeNull();   // 끝난 단계의 읽기 줄은 숫자로 접힌다
    expect(screen.queryByText('첫 커밋')).not.toBeInTheDocument();
  });

  it('묶음·고른 이유 같은 판단은 그 단계가 끝난 뒤에도 메모로 남는다', async () => {
    mount(job({
      steps: [{ key: 'PR_REVIEW', state: 'DONE', done: 31, total: 31 }, { key: 'COMMITS', state: 'DONE', done: 214, total: 214 },
        { key: 'COMPRESS', state: 'DONE', done: 12, total: 12 }, { key: 'REASON', state: 'RUNNING', done: 1, total: 4 }],
      activity: [{ seq: 1, step: 'COMPRESS', kind: 'GROUPED', units: 12, experiences: 4 }],
    }));
    const note = await within(item('후보로 추리기')).findByText('작업 12개를 경험 4개로 묶었어요', {}, { timeout: 3000 });
    expect(note.closest('li')).toHaveClass('tl-note');
    expect(item('후보로 추리기')).toHaveClass('tl__item--done');
  });

  it('진행 막대·단계 배지·반짝이 아이콘이 없다', () => {
    mount(job());
    expect(screen.queryByRole('progressbar')).toBeNull();
    expect(document.querySelector('.tl .badge')).toBeNull();
    expect(document.querySelector('.lucide-sparkles')).toBeNull();
    expect(screen.queryByText(/\/ 4 단계/)).not.toBeInTheDocument();
  });

  it('대기 중이면 모든 점이 비어 있고 대기열에서 기다린다고 말한다', () => {
    mount(job({ state: 'QUEUED', steps: STEPS.map((s) => ({ ...s, state: 'QUEUED', done: 0, total: null })) }));
    expect(document.querySelectorAll('.tl__item--wait')).toHaveLength(4);
    expect(screen.getByText(/대기열에서 .*째 기다리는 중이에요/)).toBeInTheDocument();
    expect(screen.getByText('앞 작업이 끝나면 바로 읽기 시작해요')).toBeInTheDocument();
  });

  it('실패하면 같은 타임라인에서 읽던 단계가 멈춘 점이 되고 뒤 단계는 시작하지 못했다고 하며, 할 수 있는 일은 그대로다', () => {
    mount(job({ state: 'FAILED', errorCode: 'GITHUB_UNAVAILABLE', retryable: true, finishedAt: new Date(NOW - 1_000).toISOString() }));
    expect(screen.getByRole('heading', { level: 1, name: 'GitHub에서 답이 오지 않았어요' })).toBeInTheDocument();
    expect(screen.getByText(/1분 1[0-9]초 진행/)).toBeInTheDocument();
    const halted = item('커밋 읽기');
    expect(halted).toHaveClass('tl__item--halt');
    expect(halted.querySelector('.tl__meta')).toHaveTextContent('48 / 214개');
    expect(within(halted).getByText('여기서 멈췄어요')).toBeInTheDocument();
    expect(within(item('후보로 추리기')).getByText('시작하지 못했어요')).toBeInTheDocument();
    expect(document.querySelectorAll('.tl__item--now')).toHaveLength(0);
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '다시 연결' })).toBeInTheDocument();
  });

  it('일부만 정리했으면 상한에 걸린 단계가 호박색 점으로 어디까지 읽었는지 보이고, 같은 길로 카드까지 이어 간다', () => {
    mount(job({
      state: 'SUCCEEDED', partial: true, errorCode: 'COLLECTION_CAPPED', retryable: false, finishedAt: new Date(NOW - 1_000).toISOString(),
      steps: [{ key: 'PR_REVIEW', state: 'DONE', done: 58, total: 58 }, { key: 'COMMITS', state: 'DONE', done: 1000, total: 2310 },
        { key: 'COMPRESS', state: 'DONE', done: 9, total: 9 }, { key: 'REASON', state: 'DONE', done: 9, total: 9 }],
    }));
    expect(screen.getByRole('heading', { level: 1, name: '읽은 데까지 정리했어요' })).toBeInTheDocument();
    const capped = item('커밋 읽기');
    expect(capped).toHaveClass('tl__item--cap');
    expect(capped.querySelector('.tl__meta')).toHaveTextContent('1000 / 2310개');
    expect(item('PR과 리뷰 읽기')).toHaveClass('tl__item--done');
    expect(screen.getByRole('link', { name: '후보 직접 고르기' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '읽은 범위의 후보 보기' })).not.toBeInTheDocument();
    expect(screen.getByText('한 번에 읽는 상한까지만 읽었어요')).toBeInTheDocument();
  });
});

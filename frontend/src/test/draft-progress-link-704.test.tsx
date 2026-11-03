import { cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { useLocation } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import type { Job } from '@/api/schemas';
import { ActiveJobsList } from '@/features/jobs/ActiveJobsList';
import { jobResultLink } from '@/lib/jobLinks';
import { mountApp, page } from './profileHarness';

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const active = { jobId: 'job_9', state: 'RUNNING' as const, type: 'DRAFT' as const, userRepositoryId: 'r_auth', repoName: 'auth-service', startedAt: '2026-11-02T11:00:00Z', pollAfterMs: 60_000 };
const draftJob = (cardIds: string[] | null): Job => ({
  jobId: 'job_9', type: 'DRAFT', state: 'RUNNING', partial: false, steps: [], errorCode: null, retryable: null, retryAfterSec: null,
  startedAt: '2026-11-02T11:00:00Z', updatedAt: '2026-11-02T11:00:05Z', finishedAt: null,
  result: cardIds ? { repoId: 'r_auth', cardIds } : null, pollAfterMs: 60_000,
} as Job);
const CardProbe = () => { const location = useLocation(); return <p>{`card ${location.pathname} ${JSON.stringify(location.state)}`}</p>; };
const routes = () => [page(<ActiveJobsList />, '/'), page(<CardProbe />, '/cards/:cardId')];
const link = () => screen.getByRole('link', { name: '진행 상황 보기' });

describe('#704 카드 초안 작업의 진행 상황 보기', () => {
  it('작업이 만든 카드 id 를 받으면 첫 카드의 초안을 쓰는 화면으로 가고, 앞뒤 카드 순서를 함께 넘긴다', async () => {
    vi.spyOn(endpoints, 'activeJobs').mockResolvedValue({ jobs: [active] });
    vi.spyOn(endpoints, 'job').mockResolvedValue(draftJob(['card_9', 'card_8']));
    mountApp(routes(), '/');

    await waitFor(() => expect(link()).toHaveAttribute('href', '/cards/card_9'));
    fireEvent.click(link());
    expect(await screen.findByText('card /cards/card_9 {"cardOrder":["card_9","card_8"]}')).toBeInTheDocument();
  });

  it('카드 id 를 아직 받지 못했으면 그 레포의 카드 폴더로 간다', async () => {
    vi.spyOn(endpoints, 'activeJobs').mockResolvedValue({ jobs: [active] });
    vi.spyOn(endpoints, 'job').mockResolvedValue(draftJob(null));
    mountApp(routes(), '/');

    await screen.findByRole('link', { name: '진행 상황 보기' });
    await waitFor(() => expect(endpoints.job).toHaveBeenCalled());
    expect(link()).toHaveAttribute('href', '/cards/repo/r_auth');
  });

  it('끝난 카드 초안 작업의 결과는 여러 장이면 그 레포의 카드 폴더, 한 장이면 그 카드다', () => {
    const done = (cardIds: string[]) => ({ ...draftJob(cardIds), state: 'SUCCEEDED' as const });
    expect(jobResultLink(done(['card_9', 'card_8']))).toBe('/cards/repo/r_auth');
    expect(jobResultLink(done(['card_9']))).toBe('/cards/card_9');
    expect(jobResultLink({ ...done(['card_9', 'card_8']), result: { cardIds: ['card_9', 'card_8'] } }, null)).toBe('/cards/card_9');
  });
});

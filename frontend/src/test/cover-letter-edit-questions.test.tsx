import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CoverLetter, coverLetterKeys } from '@/api/coverLetters';
import { keys } from '@/api/keys';
import { CardSummary } from '@/api/schemas';
import { CoverLetterNewPage } from '@/features/coverLetters/CoverLetterNewPage';
import { CoverLetterPage } from '@/features/coverLetters/CoverLetterPage';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

/** '문항 고치기' — 서버에 문항을 고치는 API 가 없어, 지금 자소서의 직무·문항·공고를 채운 새 자소서 화면을 연다 */
const POSTING = { id: '3f6c2a10-6b1d-4c2e-9a51-0d7e8b4f1a01', company: '가온소프트', title: '백엔드 엔지니어 (Java·Spring)' };
const question = (n: number, text: string, charLimit: number) => ({
  id: `q${n}`, seq: n, text, charLimit, theme: null, primaryCard: null, primaryCardDeleted: false,
  supportingCard: null, assignedBy: null, pickReason: null, considered: [], materials: [], asks: [], draft: null, writing: null,
});
const letter = (over: Record<string, unknown> = {}) => CoverLetter.parse({
  id: 'cl_1', stage: 'FAILED', failedStep: 'ASSIGN', errorCode: null, retryable: false, pollAfterMs: null,
  jobRole: '백엔드 개발', memo: null, createdAt: '2026-11-06T00:00:00Z', updatedAt: '2026-11-06T00:00:00Z',
  questions: [question(1, '지원 동기를 적어 주세요', 500), question(2, '협업 경험을 적어 주세요', 1000)],
  steps: { assign: [], draft: [] }, ...over,
});

const sent: { method: string; path: string; body: unknown }[] = [];
const count = (method: string, path: string) => sent.filter((s) => s.method === method && s.path === path).length;
let served = letter();

/** 바탕 자소서(cl_1)만 `served` 로 답하고, 나머지(공고·카드·만들기)는 목 서버가 답한다 */
function stubFetch() {
  sent.length = 0;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (resource: RequestInfo | URL, init?: RequestInit) => {
    const raw = typeof resource === 'string' ? resource : resource instanceof URL ? resource.toString() : resource.url;
    const url = new URL(raw, 'http://localhost');
    const path = url.pathname.replace(/^\/api/, '');
    const method = (init?.method ?? 'GET').toUpperCase();
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : {};
    sent.push({ method, path, body });
    if (method === 'GET' && path === '/cover-letters/cl_1') {
      return new Response(JSON.stringify({ data: served, error: null }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }
    const headers: Record<string, string> = {};
    new Headers(init?.headers).forEach((v, k) => { headers[k.toLowerCase()] = v; });
    const r = handle(method, path, url.searchParams, body, true, headers);
    return new Response(JSON.stringify({ data: r.data, error: r.error }), { status: r.status, headers: { 'Content-Type': 'application/json' } });
  });
}

function mountNew(path = '/cover-letters/new?from=cl_1') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  const cards = handle('GET', '/cards', new URLSearchParams(), {}, true, {}).data as unknown[];
  client.setQueryData(keys.cards, cards.map((c) => CardSummary.parse(c)));
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/cover-letters/new" element={<CoverLetterNewPage />} />
          <Route path="/cover-letters/:coverLetterId" element={<p>자소서 화면</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return client;
}

const jobInput = () => screen.getByPlaceholderText('예: 백엔드 개발');

beforeEach(() => { resetDb(); served = letter(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe('문항 고치기', () => {
  it('자소서 화면에서 문항 고치기를 누르면 그 자소서를 바탕으로 새 자소서 화면을 연다', () => {
    stubFetch();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    client.setQueryData(coverLetterKeys.one('cl_1'), served);
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/cover-letters/cl_1']}>
          <Routes><Route path="/cover-letters/:coverLetterId" element={<CoverLetterPage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    expect(screen.getByRole('link', { name: '문항 고치기' })).toHaveAttribute('href', '/cover-letters/new?from=cl_1');
  });

  it('바탕 자소서의 지원 직무와 문항, 글자 수를 채워 두고 만들기 전까지 아무것도 보내지 않는다', async () => {
    stubFetch();
    mountNew();
    await waitFor(() => expect(jobInput()).toHaveValue('백엔드 개발'));
    expect(screen.getByLabelText('1번 문항')).toHaveValue('지원 동기를 적어 주세요');
    expect(screen.getByLabelText('2번 문항')).toHaveValue('협업 경험을 적어 주세요');
    expect(screen.getByRole('button', { name: /1번 문항 답 500자 이내/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /2번 문항 답 1000자 이내/ })).toBeInTheDocument();
    expect(screen.getByText('고친 문항으로 새 자소서를 만들어요. 지금 자소서는 목록에 그대로 남아요.')).toBeInTheDocument();
    expect(sent.filter((s) => s.method !== 'GET')).toEqual([]);
  });

  it('바탕 자소서에 공고가 있으면 그 공고로 만든다', async () => {
    served = letter({ posting: POSTING });
    stubFetch();
    mountNew();
    expect(await screen.findByRole('region', { name: '지원 공고' })).toHaveTextContent('가온소프트');
    await waitFor(() => expect(jobInput()).toHaveValue('백엔드 개발'));
    fireEvent.click(screen.getByRole('button', { name: '초안 쓰기' }));
    await screen.findByText('자소서 화면');
    const create = sent.find((s) => s.method === 'POST' && s.path === '/cover-letters');
    expect(create?.body).toMatchObject({ postingId: POSTING.id, jobRole: '백엔드 개발' });
  });

  it('채운 뒤 사용자가 고친 값은 다시 읽혀도 덮어쓰지 않는다', async () => {
    stubFetch();
    const client = mountNew();
    await waitFor(() => expect(jobInput()).toHaveValue('백엔드 개발'));
    fireEvent.change(jobInput(), { target: { value: '데이터 엔지니어' } });
    fireEvent.change(screen.getByLabelText('1번 문항'), { target: { value: '고친 문항이에요' } });
    served = letter({ jobRole: '프론트엔드 개발', questions: [question(1, '다시 읽힌 문항', 300)] });
    await act(async () => {
      await client.refetchQueries({ queryKey: coverLetterKeys.one('cl_1') });
      await new Promise((resolve) => setTimeout(resolve, 50)); // 다시 읽은 값이 화면에 닿을 시간
    });
    expect(count('GET', '/cover-letters/cl_1')).toBe(2);
    expect(jobInput()).toHaveValue('데이터 엔지니어');
    expect(screen.getByLabelText('1번 문항')).toHaveValue('고친 문항이에요');
    expect(screen.getByLabelText('2번 문항')).toHaveValue('협업 경험을 적어 주세요');
  });
});

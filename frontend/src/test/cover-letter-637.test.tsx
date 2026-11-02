import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CoverLetter, CoverLetterSummary, coverLetterKeys } from '@/api/coverLetters';
import { keys } from '@/api/keys';
import { CardSummary } from '@/api/schemas';
import { CoverLetterNewPage } from '@/features/coverLetters/CoverLetterNewPage';
import { CoverLetterPage } from '@/features/coverLetters/CoverLetterPage';
import { CoverLettersPage } from '@/features/coverLetters/CoverLettersPage';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

/** Mock 공고 첫 줄(docs/examples/matching/list.json) — 공고 API 의 role 이 자소서 응답의 posting.title 이다 */
const POSTING = { id: '3f6c2a10-6b1d-4c2e-9a51-0d7e8b4f1a01', company: '가온소프트', title: '백엔드 엔지니어 (Java·Spring)' };
const input = { jobRole: '백엔드 개발', memo: null, questions: [{ text: '지원 동기와 입사 후 포부를 적어 주세요', charLimit: null }] };

const req = (method: string, path: string, body: unknown = {}, headers: Record<string, string> = {}) =>
  handle(method, path, new URLSearchParams(), body as Record<string, unknown>, true, headers);

/** fetch 를 목 서버로 보낸다. 공고 읽기를 잠시 멈춰 두거나(hold), 만들기만 404 로 떨어뜨릴(failCreate) 수 있다 */
function stubFetch(gate: { hold?: Promise<void>; failCreate?: boolean } = {}) {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (resource: RequestInfo | URL, init?: RequestInit) => {
    const raw = typeof resource === 'string' ? resource : resource instanceof URL ? resource.toString() : resource.url;
    const url = new URL(raw, 'http://localhost');
    const path = url.pathname.replace(/^\/api/, '');
    const method = (init?.method ?? 'GET').toUpperCase();
    if (path.startsWith('/matches/') && gate.hold) await gate.hold;
    const headers: Record<string, string> = {};
    new Headers(init?.headers).forEach((v, k) => { headers[k.toLowerCase()] = v; });
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : {};
    const r = method === 'POST' && path === '/cover-letters' && gate.failCreate
      ? { status: 404, data: null, error: { code: 'NOT_FOUND', message: '공고를 찾을 수 없습니다.' } }
      : handle(method, path, url.searchParams, body, true, headers);
    return new Response(JSON.stringify({ data: r.data, error: r.error }), { status: r.status, headers: { 'Content-Type': 'application/json' } });
  });
}

function mount(path: string, seed?: (client: QueryClient) => void) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(keys.cards, (req('GET', '/cards').data as unknown[]).map((c) => CardSummary.parse(c)));
  seed?.(client);
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/cover-letters" element={<CoverLettersPage />} />
          <Route path="/cover-letters/new" element={<CoverLetterNewPage />} />
          <Route path="/cover-letters/:coverLetterId" element={<CoverLetterPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return client;
}

const jobInput = () => screen.getByPlaceholderText('예: 백엔드 개발');
const fillQuestion = () => fireEvent.change(screen.getByLabelText('1번 문항'), { target: { value: input.questions[0].text } });

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe('① 공고에서 시작한 입력 화면', () => {
  it('공고를 읽어 지원 공고 블록과 직무 칸을 채우고, 만들기에 공고 id 를 함께 보낸다', async () => {
    stubFetch();
    mount(`/cover-letters/new?postingId=${POSTING.id}`);
    await waitFor(() => expect(jobInput()).toHaveValue(POSTING.title));
    expect(screen.getByRole('region', { name: '지원 공고' })).toHaveTextContent('가온소프트');
    expect(screen.getByRole('link', { name: /공고 보기/ })).toHaveAttribute('href', `/match/${POSTING.id}`);
    fillQuestion();
    fireEvent.click(screen.getByRole('button', { name: '카드 고르기 시작' }));
    await waitFor(() => expect(db.coverLetters).toHaveLength(1));
    expect(db.coverLetters[0].posting).toEqual(POSTING);
  });

  it('없는 공고면 폼 대신 못 찾음 화면과 두 갈래 링크를 보인다', async () => {
    stubFetch();
    mount('/cover-letters/new?postingId=nope');
    await screen.findByText('공고를 찾을 수 없어요');
    expect(screen.getByRole('link', { name: '공고 목록으로' })).toHaveAttribute('href', '/match');
    expect(screen.getByRole('link', { name: '공고 없이 새 자소서 쓰기' })).toHaveAttribute('href', '/cover-letters/new');
    expect(screen.queryByLabelText('1번 문항')).not.toBeInTheDocument();
  });

  it('만들 때 서버가 공고를 못 찾아도(404) 같은 화면으로 바꾼다', async () => {
    stubFetch({ failCreate: true });
    mount(`/cover-letters/new?postingId=${POSTING.id}`);
    await waitFor(() => expect(jobInput()).toHaveValue(POSTING.title));
    fillQuestion();
    fireEvent.click(screen.getByRole('button', { name: '카드 고르기 시작' }));
    await screen.findByText('공고를 찾을 수 없어요');
    expect(db.coverLetters).toHaveLength(0);
    // 같은 화면으로 돌아오므로 적어 둔 문항은 남고, 공고 때문에 난 실패 안내는 사라진다
    fireEvent.click(screen.getByRole('link', { name: '공고 없이 새 자소서 쓰기' }));
    expect(screen.getByLabelText('1번 문항')).toHaveValue(input.questions[0].text);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('공고가 오기 전에 직무를 먼저 적었으면 공고 제목으로 덮지 않는다', async () => {
    let release!: () => void;
    stubFetch({ hold: new Promise<void>((r) => { release = r; }) });
    mount(`/cover-letters/new?postingId=${POSTING.id}`);
    fireEvent.change(jobInput(), { target: { value: '데이터 엔지니어' } });
    release();
    await screen.findByText('가온소프트');
    expect(jobInput()).toHaveValue('데이터 엔지니어');
  });
});

describe('자소서 화면과 목록', () => {
  const summary = (over: Record<string, unknown>) => CoverLetterSummary.parse({
    id: 'a', stage: 'DRAFTED', jobRole: '백엔드 개발', posting: null, questionCount: 1, firstQuestion: '지원 동기',
    createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z', ...over,
  });

  it('목록은 직무가 공고 제목 그대로면 회사만, 다르면 회사 · 제목, 공고가 없으면 더 보이지 않는다', () => {
    mount('/cover-letters', (c) => c.setQueryData(coverLetterKeys.all, [
      summary({ id: 'a', jobRole: POSTING.title, posting: POSTING }),
      summary({ id: 'b', jobRole: '서버 개발', posting: POSTING }),
      summary({ id: 'c', jobRole: '데이터 엔지니어' }),
    ]));
    const rows = screen.getAllByRole('listitem');
    expect(within(rows[0]).getByText('가온소프트')).toBeInTheDocument();
    expect(within(rows[1]).getByText('가온소프트 · 백엔드 엔지니어 (Java·Spring)')).toBeInTheDocument();
    expect(within(rows[2]).queryByText(/가온소프트/)).not.toBeInTheDocument();
  });

  it('자소서 화면은 단계 제목 위에 공고 줄과 공고 보기 링크를 두고, 공고 없는 자소서에는 두지 않는다', () => {
    const base = { id: 'cl_1', stage: 'ASSIGNED', jobRole: '백엔드 개발', createdAt: 't', updatedAt: 't', questions: [] };
    mount('/cover-letters/cl_1', (c) => c.setQueryData(coverLetterKeys.one('cl_1'), CoverLetter.parse({ ...base, posting: POSTING })));
    expect(screen.getByText('지원 공고')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '공고 보기' })).toHaveAttribute('href', `/match/${POSTING.id}`);
    cleanup();
    mount('/cover-letters/cl_1', (c) => c.setQueryData(coverLetterKeys.one('cl_1'), CoverLetter.parse(base)));
    expect(screen.queryByText('지원 공고')).not.toBeInTheDocument();
  });
});

describe('계약 읽기와 Mock 서버', () => {
  it('posting 칸이 없는 옛 응답은 null 로 읽는다', () => {
    expect(CoverLetter.parse({ id: 'x', stage: 'ASSIGNING', jobRole: 'a', createdAt: 't', updatedAt: 't', questions: [] }).posting).toBeNull();
    expect(CoverLetterSummary.parse({ id: 'x', stage: 'DRAFTED', jobRole: 'a', questionCount: 1, createdAt: 't', updatedAt: 't' }).posting).toBeNull();
  });

  it('Mock 은 없는 공고 id 면 404 NOT_FOUND, 있으면 조회·목록 응답에 posting 을 싣는다', () => {
    expect(req('POST', '/cover-letters', { ...input, postingId: 'nope' })).toMatchObject({ status: 404, error: { code: 'NOT_FOUND' } });
    const created = CoverLetter.parse(req('POST', '/cover-letters', { ...input, postingId: POSTING.id }).data);
    expect(created.posting).toEqual(POSTING);
    expect(CoverLetterSummary.parse((req('GET', '/cover-letters').data as unknown[])[0]).posting).toEqual(POSTING);
    expect(CoverLetter.parse(req('POST', '/cover-letters', input).data).posting).toBeNull();
  });
});

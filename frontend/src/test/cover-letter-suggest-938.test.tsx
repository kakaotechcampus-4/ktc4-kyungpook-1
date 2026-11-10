import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CoverLetter, coverLetterKeys } from '@/api/coverLetters';
import { keys } from '@/api/keys';
import { CardSummary } from '@/api/schemas';
import { CoverLetterPage } from '@/features/coverLetters/CoverLetterPage';
import { handle } from '@/mock/router';
import { db, resetDb, settleCard } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

/** #938 쓸거리 제안 — 중심 카드가 없는 문항을 열면 카드·레포 기록 제안이 대화 위에 뜨고, 고르면 그 문항만 다시 쓴다 */
const req = (method: string, path: string, body: unknown = {}) =>
  handle(method, path, new URLSearchParams(), body as Record<string, unknown>, true, {});
let shift = 0;
const realNow = Date.now;
const advance = (sec: number) => { shift += sec * 1000; Date.now = () => realNow() + shift; };
const SEEN = { ignore: 'script, style, .sr-only' };
const SLOW = { timeout: 6000 };
const REWRITTEN_PART = '의견이 갈린 일을 끝까지 맞춰 가며';

/** 문항 하나, 중심 카드 없이 초안까지 끝난 자소서 */
function draftedWithoutCard() {
  const created = CoverLetter.parse(req('POST', '/cover-letters', { jobRole: '백엔드 개발', memo: null, questions: [{ text: '의견 차이를 조율해 해결한 경험을 적어 주세요', charLimit: null }] }).data);
  advance(7);
  req('PUT', `/cover-letters/${created.id}/questions/${created.questions[0].id}/cards`, { primaryCardId: null, supportingCardId: null });
  req('POST', `/cover-letters/${created.id}/asks`);
  advance(5);
  req('POST', `/cover-letters/${created.id}/drafts`);
  advance(30);
  return CoverLetter.parse(req('GET', `/cover-letters/${created.id}`).data);
}

function stubFetch(log: string[]) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (resource: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(typeof resource === 'string' ? resource : resource instanceof URL ? resource.toString() : resource.url, 'http://localhost');
    const path = url.pathname.replace(/^\/api/, '');
    const method = (init?.method ?? 'GET').toUpperCase();
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : {};
    log.push(`${method} ${path}`);
    const r = handle(method, path, url.searchParams, body, true, {});
    return new Response(JSON.stringify({ data: r.data, error: r.error }), { status: r.status, headers: { 'Content-Type': 'application/json' } });
  });
}

function Where() {
  const loc = useLocation();
  return <output data-testid="where">{loc.pathname + loc.search}{loc.state ? ` ${JSON.stringify(loc.state)}` : ''}</output>;
}
function mount(l: CoverLetter, path = `/cover-letters/${l.id}`) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(coverLetterKeys.one(l.id), l);
  client.setQueryData(keys.cards, (req('GET', '/cards').data as unknown[]).map((c) => CardSummary.parse(c)));
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Where />
        <Routes>
          <Route path="/cover-letters/:coverLetterId" element={<CoverLetterPage />} />
          <Route path="/cards/:cardId" element={<p>카드 화면</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
const panel = () => screen.getByRole('complementary', { name: '다듬기 패널' });
async function openChat() {
  fireEvent.click(screen.getByRole('tab', { name: '대화' }));
  fireEvent.click(await within(panel()).findByRole('button', { name: 'Q1 얘기하기' }));
  await within(panel()).findByText('이 문항에 쓸 만한 것을 찾았어요', SEEN);
}
const rewrites = (log: string[]) => log.filter((x) => x.startsWith('POST ') && x.endsWith('/rewrite'));
const draftText = () => (screen.getByLabelText('1번 문항 초안') as HTMLTextAreaElement).value;

beforeEach(() => { resetDb(); shift = 0; Date.now = realNow; });
afterEach(() => { cleanup(); Date.now = realNow; vi.restoreAllMocks(); });

describe('쓸거리 제안', () => {
  it('F1 중심 카드가 없는 문항을 열면 제안이 대화 위에 뜨고, 이걸로 쓰기는 그 문항만 다시 쓰고 접는다', { timeout: 15000 }, async () => {
    const l = draftedWithoutCard();
    const log: string[] = [];
    stubFetch(log);
    mount(l);
    await openChat();
    const box = within(panel()).getByRole('region', { name: '쓸거리 제안' });
    expect(within(box).getAllByRole('listitem')).toHaveLength(3);
    expect(within(box).getByText('내 카드')).toBeInTheDocument();
    expect(within(box).getAllByText('레포 기록')).toHaveLength(2);
    expect(box.compareDocumentPosition(panel().querySelector('.chat-thread')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    fireEvent.click(within(box).getByRole('button', { name: '이걸로 쓰기' }));
    await within(panel()).findByRole('button', { name: '제안 3개 · 다시 보기' }, SLOW);
    expect(rewrites(log)).toHaveLength(1);
    expect(draftText()).toContain(REWRITTEN_PART);
    expect(within(panel()).getByText(/카드로 다시 썼어요/, SEEN)).toBeInTheDocument();
  });

  it('F2 카드 만들어 쓰기는 카드를 만들고 작업이 끝나길 기다린 뒤, 확정된 카드로 다시 쓴다', { timeout: 15000 }, async () => {
    const l = draftedWithoutCard();
    const log: string[] = [];
    stubFetch(log);
    mount(l);
    await openChat();
    fireEvent.click(within(panel()).getAllByRole('button', { name: '카드 만들어 쓰기' })[0]);
    await within(panel()).findByText('카드를 만드는 중이에요', SEEN);
    expect(log.some((x) => /^POST \/repos\/.+\/cards$/.test(x))).toBe(true);
    // 목 서버의 초안은 결과 칸이 '확인 필요'로 남아 스스로 확정되지 않는다 — 실서버가 확정한 카드처럼 확인 필요를 비우고 확정한다
    advance(120);
    const made = db.cards.at(-1)!;
    req('GET', `/jobs/${made.generation.jobId}`);
    made.lowConfidenceFields = [];
    settleCard(made);
    await within(panel()).findByRole('button', { name: '제안 3개 · 다시 보기' }, SLOW);
    expect(rewrites(log)).toHaveLength(1);
    expect(draftText()).toContain(REWRITTEN_PART);
  });

  it('F3 카드가 스스로 확정되지 않으면 다시 쓰지 않고 카드를 확인하러 가는 길을 돌아올 주소와 함께 준다', { timeout: 15000 }, async () => {
    const l = draftedWithoutCard();
    const log: string[] = [];
    stubFetch(log);
    mount(l);
    await openChat();
    fireEvent.click(within(panel()).getAllByRole('button', { name: '카드 만들어 쓰기' })[1]);
    await within(panel()).findByText('카드를 만드는 중이에요', SEEN);
    advance(120);
    const go = await within(panel()).findByRole('button', { name: '카드를 확인하러 가기' }, SLOW);
    expect(rewrites(log)).toHaveLength(0);
    fireEvent.click(go);
    const where = (await screen.findByText('카드 화면')) && screen.getByTestId('where').textContent!;
    const cardId = db.cards.at(-1)!.id;
    expect(where).toContain(`/cards/${cardId}`);
    expect(where).toContain(`/cover-letters/${l.id}?useCard=${cardId}&question=${l.questions[0].id}`);
  });

  it('F4 없어요를 누르면 한 줄로 접히고 다시 보기로 펼친다', { timeout: 15000 }, async () => {
    const l = draftedWithoutCard();
    const log: string[] = [];
    stubFetch(log);
    mount(l);
    await openChat();
    fireEvent.click(within(panel()).getByRole('button', { name: '없어요, 직접 말할게요' }));
    expect(within(panel()).queryByText('이 문항에 쓸 만한 것을 찾았어요')).toBeNull();
    fireEvent.click(within(panel()).getByRole('button', { name: '제안 3개 · 다시 보기' }));
    expect(within(panel()).getByText('이 문항에 쓸 만한 것을 찾았어요')).toBeInTheDocument();
    expect(rewrites(log)).toHaveLength(0);
  });

  it('F5 카드를 확인하고 돌아온 주소(?useCard)는 그 문항을 한 번만 다시 쓰고 주소의 표시를 지운다', { timeout: 15000 }, async () => {
    const l = draftedWithoutCard();
    const log: string[] = [];
    stubFetch(log);
    const cardId = db.cards.find((c) => c.status === 'CONFIRMED')!.id;
    mount(l, `/cover-letters/${l.id}?useCard=${cardId}&question=${l.questions[0].id}`);
    await waitFor(() => expect(draftText()).toContain(REWRITTEN_PART), SLOW);
    expect(rewrites(log)).toHaveLength(1);
    expect(screen.getByTestId('where').textContent).toBe(`/cover-letters/${l.id}`);
  });
});

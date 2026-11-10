import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CoverLetter, coverLetterKeys } from '@/api/coverLetters';
import { keys } from '@/api/keys';
import { CardSummary } from '@/api/schemas';
import { CoverLetterPage } from '@/features/coverLetters/CoverLetterPage';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';
import { objectParticle } from '@/lib/notices';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

/** 알림에서 온 새 카드 안내 — 주소에 ?card=&question= 이 있으면 배너만 보이고, 눌러야 그 카드로 다시 쓴다 */
const req = (method: string, path: string, body: unknown = {}) =>
  handle(method, path, new URLSearchParams(), body as Record<string, unknown>, true, {});
let shift = 0;
const realNow = Date.now;
const advance = (sec: number) => { shift += sec * 1000; Date.now = () => realNow() + shift; };

function drafted() {
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
  return <output data-testid="where">{loc.pathname + loc.search}</output>;
}
function mount(l: CoverLetter, path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(coverLetterKeys.one(l.id), l);
  client.setQueryData(keys.cards, (req('GET', '/cards').data as unknown[]).map((c) => CardSummary.parse(c)));
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Where />
        <Routes><Route path="/cover-letters/:coverLetterId" element={<CoverLetterPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
const rewrites = (log: string[]) => log.filter((x) => x.startsWith('POST ') && x.endsWith('/rewrite'));

beforeEach(() => { resetDb(); shift = 0; });
afterEach(() => { cleanup(); vi.restoreAllMocks(); Date.now = realNow; });

describe('자소서 새 카드 안내', () => {
  it('B1 주소에 card 와 question 이 있으면 카드 이름과 문항 앞 20자를 담은 안내가 뜨고, 다시 쓰지는 않는다', { timeout: 15000 }, async () => {
    const l = drafted();
    const log: string[] = [];
    stubFetch(log);
    const card = db.cards.find((c) => c.status === 'CONFIRMED')!;
    mount(l, `/cover-letters/${l.id}?card=${card.id}&question=${l.questions[0].id}`);
    const text = l.questions[0].text;
    expect(await screen.findByText(`새 카드 '${card.title}'${objectParticle(card.title)} ${text.slice(0, 20)}… 문항에 쓸 수 있어요`)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '이 카드로 다시 쓰기' })).toBeInTheDocument();
    expect(rewrites(log)).toHaveLength(0);
  });

  it('B2 이 카드로 다시 쓰기를 누르면 그 문항을 그 카드로 한 번 다시 쓴다', { timeout: 15000 }, async () => {
    const l = drafted();
    const log: string[] = [];
    stubFetch(log);
    const card = db.cards.find((c) => c.status === 'CONFIRMED')!;
    mount(l, `/cover-letters/${l.id}?card=${card.id}&question=${l.questions[0].id}`);
    fireEvent.click(await screen.findByRole('button', { name: '이 카드로 다시 쓰기' }));
    await waitFor(() => expect(rewrites(log)).toHaveLength(1), { timeout: 6000 });
    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe(`/cover-letters/${l.id}`), { timeout: 6000 });
  });

  it('B3 닫기를 누르면 주소의 표시만 지우고 다시 쓰지 않는다', { timeout: 15000 }, async () => {
    const l = drafted();
    const log: string[] = [];
    stubFetch(log);
    const card = db.cards.find((c) => c.status === 'CONFIRMED')!;
    mount(l, `/cover-letters/${l.id}?card=${card.id}&question=${l.questions[0].id}`);
    fireEvent.click(await screen.findByRole('button', { name: '닫기' }));
    expect(screen.getByTestId('where').textContent).toBe(`/cover-letters/${l.id}`);
    expect(screen.queryByRole('button', { name: '이 카드로 다시 쓰기' })).toBeNull();
    expect(rewrites(log)).toHaveLength(0);
  });

  it('B4 주소에 문항이 맞지 않으면 안내를 그리지 않는다', { timeout: 15000 }, async () => {
    const l = drafted();
    stubFetch([]);
    mount(l, `/cover-letters/${l.id}?card=card_x&question=q_none`);
    await screen.findByLabelText('1번 문항 초안');
    expect(screen.queryByRole('button', { name: '이 카드로 다시 쓰기' })).toBeNull();
  });
});

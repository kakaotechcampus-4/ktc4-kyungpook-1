import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CoverLetter, coverLetterKeys } from '@/api/coverLetters';
import { keys } from '@/api/keys';
import { CardSummary } from '@/api/schemas';
import { CoverLetterPage } from '@/features/coverLetters/CoverLetterPage';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

/**
 * #727 초안 옆 패널 — 진단과 첨삭(첨삭 받기 → 결과 → 적용하기), 짐작 확인 탭, 알림 줄, 860px 아래 막대.
 * 목 서버가 문항 하나를 결정적으로 첨삭한다(긴 문장 하나를 둘로 · 결과 문장 하나 보탬 · 아쉬운 점 2 · 잘한 점 1).
 */
const req = (method: string, path: string, body: unknown = {}) =>
  handle(method, path, new URLSearchParams(), body as Record<string, unknown>, true, {});
let shift = 0;
const realNow = Date.now;
const advance = (sec: number) => { shift += sec * 1000; Date.now = () => realNow() + shift; };

/** 목으로 만들기 → 카드 고르기 → 질문 → 초안까지 끝낸 문항 둘짜리 자소서 */
function draftedFromMock() {
  const created = CoverLetter.parse(req('POST', '/cover-letters', { jobRole: '백엔드 개발', memo: '결제 서비스의 오류를 줄이는 일을 직접 해 보고 싶어 지원했어요', questions: [
    { text: '팀으로 함께 성과를 낸 경험을 적어 주세요', charLimit: null },
    { text: '어려운 문제를 해결한 경험을 적어 주세요', charLimit: null },
  ] }).data);
  advance(7);
  req('POST', `/cover-letters/${created.id}/asks`);
  advance(5);
  req('POST', `/cover-letters/${created.id}/drafts`);
  advance(30);
  return CoverLetter.parse(req('GET', `/cover-letters/${created.id}`).data);
}

/** fetch 를 목 서버로 보내고 보낸 요청을 남긴다. hold 를 주면 첨삭 요청은 그 약속이 풀릴 때까지 기다린다 */
function stubFetch(log: string[], hold?: Promise<void>) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (resource: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(typeof resource === 'string' ? resource : resource instanceof URL ? resource.toString() : resource.url, 'http://localhost');
    const path = url.pathname.replace(/^\/api/, '');
    const method = (init?.method ?? 'GET').toUpperCase();
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : {};
    log.push(`${method} ${path}`);
    if (hold && method === 'POST' && path.endsWith('/polish')) await hold;
    const r = handle(method, path, url.searchParams, body, true, {});
    return new Response(JSON.stringify({ data: r.data, error: r.error }), { status: r.status, headers: { 'Content-Type': 'application/json' } });
  });
}

function mount(l: CoverLetter) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData(coverLetterKeys.one(l.id), l);
  client.setQueryData(keys.cards, (req('GET', '/cards').data as unknown[]).map((c) => CardSummary.parse(c)));
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/cover-letters/${l.id}`]}>
        <Routes><Route path="/cover-letters/:coverLetterId" element={<CoverLetterPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
const area = (n: number) => screen.getByLabelText(`${n}번 문항 초안`) as HTMLTextAreaElement;
const panel = () => screen.getByRole('complementary', { name: '다듬기 패널' });
const polishPath = (l: CoverLetter, i = 0) => `/cover-letters/${l.id}/questions/${l.questions[i].id}/polish`;
const mockQuestion = (i = 0) => db.coverLetters[0].questions[i];

beforeEach(() => { resetDb(); shift = 0; Date.now = realNow; });
afterEach(() => { cleanup(); Date.now = realNow; vi.restoreAllMocks(); vi.useRealTimers(); delete (window as { matchMedia?: unknown }).matchMedia; });

describe('응답 읽기', () => {
  it('첨삭 칸이 없는 옛 응답도 읽히고(polish null · 적용 0), 모르는 분류의 포인트는 그 포인트만 버린다', () => {
    const l = draftedFromMock();
    const raw = req('GET', `/cover-letters/${l.id}`).data as { questions: Record<string, unknown>[] };
    for (const q of raw.questions) { delete q.polish; delete q.polishAppliedCount; }
    expect(CoverLetter.parse(raw).questions[0]).toMatchObject({ polish: null, polishAppliedCount: 0 });

    req('POST', polishPath(l));
    const withPolish = req('GET', `/cover-letters/${l.id}`).data as { questions: { polish: { points: Record<string, unknown>[] } }[] };
    withPolish.questions[0].polish.points[0].category = 'NEW_KIND';
    const parsed = CoverLetter.parse(withPolish).questions[0].polish!;
    expect(parsed.points.map((p) => p.seq)).toEqual([2, 3]);
  });
});

describe('목 서버', () => {
  it('첨삭은 아쉬운 점 2 · 잘한 점 1 을 주고, 포인트의 문장 번호는 고친 글 안이며, 짐작 표시는 바뀌지 않은 문장에 그대로 옮긴다', () => {
    const l = draftedFromMock();
    const before = mockQuestion().draft;
    const p = CoverLetter.parse(req('POST', polishPath(l)).data).questions[0].polish!;
    expect(p.points.map((x) => x.kind)).toEqual(['WEAK', 'WEAK', 'GOOD']);
    const seqs = new Set(p.draft.sentences.map((s) => s.seq));
    for (const x of p.points) for (const s of x.sentenceSeqs) expect(seqs.has(s)).toBe(true);
    expect(p.draft.sentences.find((s) => s.seq === p.points[1].sentenceSeqs[0])!.text).toBe('그 결과 같은 문제가 다시 생기지 않았습니다.');
    const inferredBefore = before.sentences.find((s: { seq: number }) => s.seq === before.marks.find((m: { code: string }) => m.code === 'INFERRED').sentenceSeq).text;
    const inferredAfter = p.draft.marks.find((m) => m.code === 'INFERRED')!;
    expect(p.draft.sentences.find((s) => s.seq === inferredAfter.sentenceSeq)!.text).toBe(inferredBefore);
    expect(mockQuestion().draft.text).toBe(before.text);
  });

  it('적용은 지금 글·문장·표시를 첨삭 글로 바꾸고 AI 원본은 두며 적용 횟수를 센다. 첨삭 결과가 없으면 409 다', () => {
    const l = draftedFromMock();
    const path = polishPath(l);
    expect(req('POST', `${path}/apply`)).toMatchObject({ status: 409, error: { code: 'INVALID_STATE' } });
    const aiText = mockQuestion().draft.aiText;
    const polished = CoverLetter.parse(req('POST', path).data).questions[0].polish!.draft.text;
    const q = CoverLetter.parse(req('POST', `${path}/apply`).data).questions[0];
    expect(q).toMatchObject({ polish: null, polishAppliedCount: 1, draft: { text: polished, aiText, edited: false } });
    expect(req('DELETE', path).status).toBe(200);
  });
});

describe('진단과 첨삭 탭', () => {
  it('첨삭 받기는 POST …/polish 를 부르고, 기다리는 동안 살펴보는 중 → 고치는 중, 끝나면 종합 평가 · 포인트 · 고친 글을 보인다', async () => {
    const log: string[] = [];
    let release!: () => void;
    stubFetch(log, new Promise<void>((r) => { release = r; }));
    const l = draftedFromMock();
    mount(l);
    const before = area(1).value;
    fireEvent.click(within(panel()).getByRole('button', { name: '첨삭 받기' }));
    expect(await within(panel()).findByText('살펴보는 중')).toBeInTheDocument();
    expect(within(panel()).getByText('Q1 첨삭 중')).toBeInTheDocument();
    await waitFor(() => expect(document.querySelector('.clp-steps li.is-now')?.textContent).toContain('고치는 중'), { timeout: 2000 });
    act(() => release());
    expect(await within(panel()).findByText('종합 평가')).toBeInTheDocument();
    const p = CoverLetter.parse(req('GET', `/cover-letters/${l.id}`).data).questions[0].polish!;
    expect(within(panel()).getByText(p.summary)).toBeInTheDocument();
    expect(within(panel()).getByRole('button', { name: '아쉬운 점 2' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(panel()).getByRole('button', { name: '잘한 점 1' })).toBeInTheDocument();
    expect(within(panel()).getByLabelText('고친 글').textContent).toContain('그 결과 같은 문제가 다시 생기지 않았습니다.');
    expect(log).toContain(`POST ${polishPath(l)}`);
    expect(area(1).value).toBe(before);
  });

  it('포인트를 누르면 고친 글에서 그 포인트의 문장 번호에 해당하는 문장만 칠한다', async () => {
    stubFetch([]);
    const l = draftedFromMock();
    mount(l);
    fireEvent.click(within(panel()).getByRole('button', { name: '첨삭 받기' }));
    await within(panel()).findByText('종합 평가');
    const p = CoverLetter.parse(req('GET', `/cover-letters/${l.id}`).data).questions[0].polish!;
    const second = p.points[1];
    fireEvent.click(within(panel()).getByRole('button', { name: new RegExp(second.title) }));
    const lit = [...document.querySelectorAll<HTMLElement>('.clp-rs--lit')];
    expect(lit.map((el) => Number(el.dataset.rs))).toEqual(second.sentenceSeqs);
    expect(lit.map((el) => el.textContent)).toEqual(second.sentenceSeqs.map((s) => p.draft.sentences.find((x) => x.seq === s)!.text));
    fireEvent.click(within(panel()).getByRole('button', { name: '잘한 점 1' }));
    expect(document.querySelectorAll('.clp-rs--lit')).toHaveLength(0);
  });

  it('적용하기는 POST …/polish/apply 를 부르고 종이의 글이 첨삭 글로 바뀌며, 2판 · 원본 보기에는 AI 원본이 그대로 있다', async () => {
    const log: string[] = [];
    stubFetch(log);
    const l = draftedFromMock();
    const aiText = l.questions[0].draft!.aiText;
    mount(l);
    fireEvent.click(within(panel()).getByRole('button', { name: '첨삭 받기' }));
    await within(panel()).findByText('종합 평가');
    const polished = mockQuestion().polish.draft.text;
    fireEvent.click(within(panel()).getByRole('button', { name: '적용하기' }));
    expect(await within(panel()).findByText('적용했어요')).toBeInTheDocument();
    expect(log).toContain(`POST ${polishPath(l)}/apply`);
    expect(area(1).value).toBe(polished);
    expect(screen.getByText('2판')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '원본 보기' }));
    expect(document.querySelector('.cl-original p')?.textContent).toBe(aiText);
    // 적용한 글은 이미 서버 글이라 칸을 벗어나도 다시 저장하지 않는다
    fireEvent.blur(area(1));
    expect(log.some((x) => x.startsWith('PATCH '))).toBe(false);
  });

  it('다시 받기는 첨삭을 한 번 더 부른다', async () => {
    const log: string[] = [];
    stubFetch(log);
    const l = draftedFromMock();
    mount(l);
    fireEvent.click(within(panel()).getByRole('button', { name: '첨삭 받기' }));
    await within(panel()).findByText('종합 평가');
    fireEvent.click(within(panel()).getByRole('button', { name: '다시 받기' }));
    await waitFor(() => expect(log.filter((x) => x === `POST ${polishPath(l)}`)).toHaveLength(2));
    expect(await within(panel()).findByText('종합 평가')).toBeInTheDocument();
  });

  it('저장하지 않은 고친 글이 있으면 먼저 저장(PATCH)하고 첨삭을 부른다', async () => {
    const log: string[] = [];
    stubFetch(log);
    const l = draftedFromMock();
    mount(l);
    fireEvent.change(area(1), { target: { value: '아직 저장하지 않은 글입니다.' } });
    fireEvent.click(within(panel()).getByRole('button', { name: '첨삭 받기' }));
    await within(panel()).findByText('종합 평가');
    expect(log.filter((x) => !x.startsWith('GET '))).toEqual([`PATCH /cover-letters/${l.id}/questions/${l.questions[0].id}`, `POST ${polishPath(l)}`]);
    expect(mockQuestion().polish.draft.text).toContain('아직 저장하지 않은 글입니다.');
  });

  it('첨삭 전에는 살펴볼 곳에 확인 필요 표시의 이유를 쉬운 말로 보이고(짐작 문장은 빼고), 줄을 누르면 종이의 그 문장을 칠한다', () => {
    const l = draftedFromMock();
    const q = l.questions[0];
    const first = q.draft!.sentences[0].text;
    mount({ ...l, questions: [{ ...q, draft: { ...q.draft!, marks: [...q.draft!.marks, { sentenceSeq: 1, code: 'UNSOURCED_NUMBER', value: '458개' }] } }, l.questions[1]] });
    const flags = within(panel()).getByRole('region', { name: '살펴볼 곳' });
    expect(within(flags).getAllByRole('button').map((b) => b.textContent)).toEqual(["'458개' — 카드에 없는 숫자예요"]);
    expect(document.querySelector('.pedit__mirror mark.phl--focus')).toBeNull();
    fireEvent.click(within(flags).getByRole('button', { name: "'458개' — 카드에 없는 숫자예요" }));
    expect(document.querySelector('[data-question="' + q.id + '"] .pedit__mirror mark.phl--focus')?.textContent).toBe(first);
    expect(within(panel()).getAllByRole('button').filter((b) => b.className.includes('btn--primary')).map((b) => b.textContent)).toEqual(['첨삭 받기']);
  });

  it('머리줄은 지금 보고 있는 문항을 따라간다 — 2번 글상자에 들어가면 Q2에 적용', () => {
    mount(draftedFromMock());
    expect(within(panel()).getByText('Q1에 적용')).toBeInTheDocument();
    fireEvent.focus(area(2));
    fireEvent.focusIn(area(2));
    expect(within(panel()).getByText('Q2에 적용')).toBeInTheDocument();
  });
});

describe('짐작 확인 탭 · 알림 줄 · 모바일', () => {
  it('짐작 확인 탭은 확인하지 않은 짐작 문장만 보이고, 탭에 그 개수가 붙는다', () => {
    const l = draftedFromMock();
    const q = l.questions[0];
    const marks = q.draft!.marks.filter((m) => m.code === 'INFERRED');
    expect(marks).toHaveLength(1);
    mount(l);
    const tab = screen.getByRole('tab', { name: /짐작 확인/ });
    expect(tab.textContent).toBe('짐작 확인1');
    fireEvent.click(tab);
    expect(tab).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tabpanel')).toHaveAttribute('aria-labelledby', tab.id);
    const rows = document.querySelectorAll('.clp-inf');
    expect(rows).toHaveLength(1);
    expect(rows[0].textContent).toContain(q.draft!.sentences.find((s) => s.seq === marks[0].sentenceSeq)!.text);
  });

  it('알림 줄은 다른 문항과 겹친다는 표시(REPEATED)가 남아 있을 때만 나오고, 닫으면 사라진다', () => {
    const l = draftedFromMock();
    mount(l);
    expect(screen.queryByRole('region', { name: '살펴볼 것' })).toBeNull();
    cleanup();
    const q2 = l.questions[1];
    const repeated = { ...l, questions: [l.questions[0], { ...q2, draft: { ...q2.draft!, marks: [...q2.draft!.marks, { sentenceSeq: 1, code: 'REPEATED', value: null }] } }] };
    mount(repeated);
    const notices = screen.getByRole('region', { name: '살펴볼 것' });
    expect(within(notices).getByText('Q1, Q2에 같은 이야기가 있어요')).toBeInTheDocument();
    expect(within(notices).getByRole('button', { name: '→ Q2 보기' })).toBeInTheDocument();
    fireEvent.click(within(notices).getByRole('button', { name: '이 알림 닫기' }));
    expect(screen.queryByRole('region', { name: '살펴볼 것' })).toBeNull();
  });

  it('860px 아래에서는 탭 대신 아래 막대 세 칸이 있고, 누르면 시트로 열리며 Esc 로 닫힌다', () => {
    window.matchMedia = vi.fn().mockImplementation((q: string) => ({ matches: q.includes('859.98px'), media: q, addEventListener: vi.fn(), removeEventListener: vi.fn() })) as unknown as typeof window.matchMedia;
    mount(draftedFromMock());
    expect(screen.queryByRole('tablist')).toBeNull();
    const bar = screen.getByRole('navigation', { name: '다듬기 메뉴' });
    expect(within(bar).getAllByRole('button').map((b) => b.textContent)).toEqual(['채우기', '진단과 첨삭', '짐작 확인1']);
    fireEvent.click(within(bar).getByRole('button', { name: '진단과 첨삭' }));
    const sheet = screen.getByRole('complementary', { name: '진단과 첨삭' });
    expect(within(sheet).getByRole('button', { name: '첨삭 받기' })).toBeInTheDocument();
    expect(within(bar).getByRole('button', { name: '진단과 첨삭' })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('complementary', { name: '진단과 첨삭' })).toBeNull();
  });
});

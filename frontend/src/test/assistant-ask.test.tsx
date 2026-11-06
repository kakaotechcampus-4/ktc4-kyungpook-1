import { StrictMode } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, Outlet, RouterProvider, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AssistantItem, AssistantRun } from '@/api/assistant';
import { AskPage } from '@/features/assistant/AskPage';
import { stepLine } from '@/features/assistant/askCopy';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';
import { connectMockServer } from './profileHarness';

/**
 * 홈 한 줄 명령 — 화면은 실행 한 건을 다시 읽어 대화로 그리고, 목 서버가 대본대로 줄을 쌓는다.
 * 시간은 읽는 순간의 경과로 정해져서 Date.now 를 밀어 진행시킨다.
 */
const req = (method: string, path: string, body: unknown = {}) =>
  handle(method, path, new URLSearchParams(), body as Record<string, unknown>, true, {});
let shift = 0;
const realNow = Date.now;
const advance = (sec: number) => { shift += sec * 1000; Date.now = () => realNow() + shift; };

const begin = (text: string) => AssistantRun.parse(req('POST', '/assistant/runs', { text }).data);
const reply = (id: string, text: string) => AssistantRun.parse(req('POST', `/assistant/runs/${id}/messages`, { text }).data);
function read(id: string) {
  const data = req('GET', `/assistant/runs/${id}`).data as { items: unknown[] };
  const run = AssistantRun.parse(data);
  expect(run.items).toHaveLength(data.items.length); // 계약에 맞지 않아 조용히 버려진 줄이 없다
  return run;
}
/** 줄의 종류, 도구, 끝났는지를 글 한 줄로 — 하는 중인 걸음은 끝에 true 가 없다 */
const shape = (id: string) => read(id).items.map((i) => [i.kind, i.tool, i.ok].filter((x) => x !== null).join(':'));
const settled = (run: AssistantRun) => { advance(10); return read(run.id); };

function Where() {
  const { pathname, search } = useLocation();
  return <output data-testid="where">{pathname + search}</output>;
}
const routes = () => [{
  element: <><Where /><Outlet /></>,
  children: [
    { path: '/', element: <p>홈 화면</p> },
    { path: '/ask', element: <AskPage /> },
    { path: '/ask/:runId', element: <AskPage /> },
  ],
}];
/** 실제 앱(main.tsx)처럼 StrictMode 안에서 연다 — 효과가 두 번 도는 개발 모드에서도 같아야 한다 */
function mount(path: string, server = connectMockServer()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const router = createMemoryRouter(routes(), { initialEntries: [path] });
  render(<StrictMode><QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider></StrictMode>);
  return { client, server };
}
const where = () => screen.getByTestId('where').textContent;
const composer = () => screen.getByLabelText('할 일이나 답') as HTMLTextAreaElement;
const settle = () => act(async () => { await new Promise((r) => setTimeout(r, 40)); });
/** 소리로 읽는 칸(sr-only)은 화면 글과 같은 말을 담으므로 화면 글만 찾는다 */
const SEEN = { ignore: 'script, style, .sr-only' };
const posts = (server: ReturnType<typeof connectMockServer>) => server.calls.filter((c) => c.method === 'POST');
const json = (data: unknown) => new Response(JSON.stringify({ data, error: null }), { status: 200 });
const rawRun = (id: string, state: string, items: unknown[]) =>
  ({ id, state, stepsUsed: 0, stepLimit: 6, pollAfterMs: null, errorCode: null, items, createdAt: '2026-11-05T09:00:00Z', updatedAt: '2026-11-05T09:00:05Z' });

beforeEach(() => { resetDb(); shift = 0; Date.now = realNow; vi.stubEnv('VITE_ASSISTANT_ENABLED', 'true'); });
afterEach(() => { cleanup(); Date.now = realNow; vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.restoreAllMocks(); });

describe('홈 한 줄 명령 화면', () => {
  it('홈에서 적은 말로 들어오면 그 말로 실행을 한 번만 만들고 실행 주소로 바꾼다', async () => {
    const goal = '토스 백엔드 공고에 맞춰 자소서 써 줘';
    const { server } = mount(`/ask?q=${encodeURIComponent(goal)}`);
    await waitFor(() => expect(where()).toMatch(/^\/ask\/run_\d+$/));
    expect(posts(server)).toEqual([{ method: 'POST', path: '/assistant/runs', body: { text: goal } }]);
    expect(db.assistantRuns).toHaveLength(1);
    expect(await screen.findByText(goal, SEEN)).toBeInTheDocument();
  });

  it('도는 동안에는 입력을 잠그고 끝나면 연다', async () => {
    const running = begin('spring 공고 보여 줘');
    const { client } = mount(`/ask/${running.id}`);
    await waitFor(() => expect(composer()).toBeDisabled());
    expect(screen.getByRole('button', { name: '보내기' })).toBeDisabled();
    advance(10);
    await act(async () => { await client.invalidateQueries(); }); // 폴링이 한 번 더 도는 것과 같다
    await waitFor(() => expect(composer()).toBeEnabled());
    expect(read(running.id).state).toBe('DONE');
  });

  it('걸음은 하는 중, 끝남, 실패를 문장으로 구분해 보인다', () => {
    const step = (raw: object) => AssistantItem.parse({ seq: 1, kind: 'STEP', ...raw });
    /** 같은 걸음을 하는 중 → 끝남 → 실패로 읽은 세 문장 */
    const lines = (tool: string, input: object, done: object, error: string) => [
      stepLine(step({ tool, ...input })), stepLine(step({ tool, ok: true, ...input, ...done })), stepLine(step({ tool, ok: false, error, ...input })),
    ];
    const posting = { postingId: 'p1', company: '나래랩스', title: '서버 개발자' };
    const all = [
      lines('FIND_POSTINGS', { find: { company: '가온소프트', keywords: [] } }, { totalFound: 3 }, 'NOT_FOUND'),
      lines('MATCH_POSTING', { posting }, { match: { requiredMet: 2, requiredTotal: 4 } }, 'RATE_LIMITED'),
      lines('START_COVER_LETTER', {}, { questionCount: 2 }, 'NO_CONFIRMED_CARDS'),
      lines('START_REPO_ANALYSIS', { repoName: 'auth-service' }, {}, 'SOMETHING_NEW'),
    ];
    expect(all).toEqual([
      ['가온소프트 공고를 찾고 있어요', '가온소프트 공고 3건을 찾았어요', '찾을 수 없는 공고나 레포예요'],
      ['나래랩스 공고와 내 카드를 맞춰 보고 있어요', '나래랩스 공고와 내 카드를 맞춰 봤어요. 필수 4개 중 2개가 맞아요', '요청이 많아서 잠시 쉬어야 해요'],
      ['자소서를 준비하고 있어요', '문항 2개로 자소서를 쓰기 시작했어요', '확정한 카드가 없어서 하지 못했어요'],
      ['auth-service 레포를 정리할 준비를 하고 있어요', 'auth-service 레포를 정리하기 시작했어요', '이 일을 끝내지 못했어요'],
    ]);
    expect(stepLine(step({ tool: 'FIND_POSTINGS', ok: true, totalFound: 0 }))).toBe('맞는 공고를 찾지 못했어요');
  });

  it('찾은 공고는 타일로 보이고 그 공고로 가는 링크다', async () => {
    const found = settled(begin('spring 공고 보여 줘'));
    const postings = found.items.find((i) => i.tool === 'FIND_POSTINGS')!.postings;
    expect(postings.length).toBeGreaterThan(1);
    mount(`/ask/${found.id}`);
    const tilesNow = () => screen.queryAllByRole('link').filter((a) => a.getAttribute('href')?.startsWith('/match/'));
    await waitFor(() => expect(tilesNow()).toHaveLength(postings.length));
    const tiles = tilesNow();
    expect(tiles.map((a) => a.getAttribute('href'))).toEqual(postings.map((p) => `/match/${p.postingId}`));
    expect(within(tiles[0]).getByText(postings[0].title)).toBeInTheDocument();
    expect(tiles[0]).toHaveTextContent(postings[0].company);
  });

  it('고르는 물음에는 선택지가 보이고 누르면 그 말을 답으로 보낸다', async () => {
    const waiting = settled(begin('레포 하나 정리해 줘'));
    expect(waiting.state).toBe('WAITING_USER');
    const { server } = mount(`/ask/${waiting.id}`);
    const names = waiting.items.at(-1)!.options;
    expect(names).toHaveLength(3);
    for (const name of names) expect(await screen.findByRole('button', { name })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: names[1] }));
    await waitFor(() => expect(posts(server)).toEqual([{ method: 'POST', path: `/assistant/runs/${waiting.id}/messages`, body: { text: names[1] } }]));
    await waitFor(() => expect(screen.queryByRole('button', { name: names[1] })).toBeNull()); // 이어서 도는 동안에는 고르지 못한다
  });

  it('자소서나 정리를 시작하고 끝나면 결과로 가는 버튼이 보인다', async () => {
    const letter = begin('가온소프트 공고에 맞춰 자소서 써 줘');
    advance(10);
    reply(letter.id, '지원 동기를 적어 주세요\n가장 어려웠던 경험을 적어 주세요');
    advance(10);
    expect(read(letter.id).state).toBe('DONE');
    mount(`/ask/${letter.id}`);
    expect(await screen.findByRole('link', { name: /자소서 열기/ })).toHaveAttribute('href', `/cover-letters/${db.coverLetters[0].id}`);
    cleanup();

    const repo = begin('레포 정리해 줘');
    advance(10);
    reply(repo.id, 'auth-service');
    advance(10);
    const jobId = read(repo.id).items.find((i) => i.tool === 'START_REPO_ANALYSIS')!.jobId;
    expect(jobId).toBeTruthy();
    mount(`/ask/${repo.id}`);
    expect(await screen.findByRole('link', { name: /정리 보기/ })).toHaveAttribute('href', `/repos/r_auth/run?job=${jobId}`);
  });

  it('끝난 뒤에 적으면 새 실행을 만든다', async () => {
    const done = settled(begin('spring 공고 보여 줘'));
    expect(done.state).toBe('DONE');
    const { server } = mount(`/ask/${done.id}`);
    fireEvent.change(await screen.findByLabelText('할 일이나 답'), { target: { value: '레포 하나 정리해 줘' } });
    fireEvent.click(screen.getByRole('button', { name: '보내기' }));
    await waitFor(() => expect(where()).not.toBe(`/ask/${done.id}`));
    expect(where()).toMatch(/^\/ask\/run_\d+$/);
    expect(posts(server)).toEqual([{ method: 'POST', path: '/assistant/runs', body: { text: '레포 하나 정리해 줘' } }]);
    expect(db.assistantRuns).toHaveLength(2);
  });

  it('실패로 끝난 실행은 다시 적어 달라고 알린다', async () => {
    const failed = rawRun('run_x', 'FAILED', [{ seq: 1, kind: 'USER', text: '자소서 써 줘' }]);
    mount('/ask/run_x', connectMockServer((call) => (call.path === '/assistant/runs/run_x' ? json(failed) : undefined)));
    expect(await screen.findByRole('alert')).toHaveTextContent('지금은 답을 받지 못했어요. 할 일을 다시 적어 주세요.');
    expect(composer()).toBeEnabled();
  });

  it('도구 걸음이 끝나면 다른 화면이 새로 읽도록 이 실행 밖의 캐시를 낡은 것으로 표시한다', async () => {
    const running = begin('spring 공고 보여 줘');
    const { client } = mount(`/ask/${running.id}`);
    await waitFor(() => expect(composer()).toBeDisabled());
    client.setQueryData(['cover-letters'], []);
    expect(client.getQueryState(['cover-letters'])?.isInvalidated).toBe(false);
    advance(10);
    await act(async () => { await client.invalidateQueries({ queryKey: ['assistant'] }); }); // 폴링이 한 번 더 도는 것과 같다
    await waitFor(() => expect(client.getQueryState(['cover-letters'])?.isInvalidated).toBe(true));
    expect(client.getQueryState(['assistant', 'runs', running.id])?.isInvalidated).toBe(false);
  });

  it('처음 화면의 예시를 누르면 입력 칸에 그 문장이 들어가고, 보내기 전에는 실행을 만들지 않는다', async () => {
    const example = '토스 공고에 맞춰 자소서 써 줘';
    const { server } = mount('/ask');
    fireEvent.click(await screen.findByRole('button', { name: new RegExp(example) }));
    expect(composer()).toHaveValue(example);
    expect(composer()).toHaveFocus();
    await settle();
    expect(posts(server)).toEqual([]);
    fireEvent.click(screen.getByRole('button', { name: '보내기' }));
    await waitFor(() => expect(where()).toMatch(/^\/ask\/run_\d+$/));
    expect(posts(server)).toEqual([{ method: 'POST', path: '/assistant/runs', body: { text: example } }]);
  });

  it('스위치가 꺼져 있으면 화면을 열지 않고 홈으로 보낸다', async () => {
    vi.stubEnv('VITE_ASSISTANT_ENABLED', 'false');
    const asked = mount(`/ask?q=${encodeURIComponent('자소서 써 줘')}`);
    await waitFor(() => expect(where()).toBe('/'));
    expect(screen.getByText('홈 화면')).toBeInTheDocument();
    await settle();
    expect(asked.server.calls).toEqual([]);
    cleanup();

    const opened = mount('/ask/run_1');
    await waitFor(() => expect(where()).toBe('/'));
    await settle();
    expect(opened.server.calls).toEqual([]);
  });

  it('모르는 종류의 줄이 섞여 있어도 나머지 대화는 보인다', async () => {
    const mixed = rawRun('run_y', 'DONE', [
      { seq: 1, kind: 'USER', text: '자소서 써 줘' },
      { seq: 2, kind: 'HOLOGRAM', text: '처음 보는 종류의 줄' },
      { seq: 3, kind: 'STEP', tool: 'TELEPORT', ok: true },
      { seq: 4, kind: 'SAY', text: '남은 대화는 그대로 보여요' },
    ]);
    mount('/ask/run_y', connectMockServer((call) => (call.path === '/assistant/runs/run_y' ? json(mixed) : undefined)));
    expect(await screen.findByText('남은 대화는 그대로 보여요', SEEN)).toBeInTheDocument();
    expect(screen.getByText('자소서 써 줘', SEEN)).toBeInTheDocument();
    expect(screen.queryByText('처음 보는 종류의 줄', SEEN)).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

describe('홈 한 줄 명령 목 서버', () => {
  it('목 서버는 자소서를 부탁하면 공고 찾기, 맞춰 보기, 문항 묻기 순서로 가고 문항을 받으면 자소서를 만든다', () => {
    const made = begin('가온소프트 공고에 맞춰 자소서 써 줘');
    expect(made).toMatchObject({ state: 'RUNNING', stepsUsed: 0, stepLimit: 6, pollAfterMs: 1000 });
    expect(shape(made.id)).toEqual(['USER']);

    // 걸음은 하는 중으로 먼저 나타나고 1초쯤 뒤에 결과가 붙는다
    const seen: string[][] = [];
    advance(0.5);
    for (let i = 0; i < 6; i++) { seen.push(shape(made.id)); advance(1); }
    expect(seen).toEqual([
      ['USER'],
      ['USER', 'STEP:FIND_POSTINGS'],
      ['USER', 'STEP:FIND_POSTINGS:true'],
      ['USER', 'STEP:FIND_POSTINGS:true', 'STEP:MATCH_POSTING'],
      ['USER', 'STEP:FIND_POSTINGS:true', 'STEP:MATCH_POSTING:true'],
      ['USER', 'STEP:FIND_POSTINGS:true', 'STEP:MATCH_POSTING:true', 'ASK'],
    ]);

    const waiting = read(made.id);
    expect(waiting).toMatchObject({ state: 'WAITING_USER', stepsUsed: 2, pollAfterMs: null });
    const [, find, match, question] = waiting.items;
    expect(find).toMatchObject({ find: { company: '가온소프트' }, totalFound: 1, postings: [{ company: '가온소프트' }] });
    expect(match).toMatchObject({ posting: { company: '가온소프트' }, match: { requiredMet: expect.any(Number), requiredTotal: expect.any(Number) } });
    expect(question).toMatchObject({ askKind: 'QUESTIONS', text: '자소서를 쓰려면 문항이 필요해요. 지원서에 있는 문항을 그대로 붙여 넣어 주세요.' });

    const answered = reply(made.id, '지원 동기를 적어 주세요\n\n가장 어려웠던 경험을 적어 주세요\n');
    expect(answered.state).toBe('RUNNING');
    expect(answered.items.at(-1)).toMatchObject({ kind: 'USER', text: '지원 동기를 적어 주세요\n\n가장 어려웠던 경험을 적어 주세요' });
    advance(10);
    const done = read(made.id);
    expect(done.state).toBe('DONE');
    expect(shape(made.id).slice(-3)).toEqual(['USER', 'STEP:START_COVER_LETTER:true', 'SAY']);
    expect(db.coverLetters).toHaveLength(1);
    expect(done.items.find((i) => i.tool === 'START_COVER_LETTER')).toMatchObject({ questionCount: 2, coverLetterId: db.coverLetters[0].id });
    expect(db.coverLetters[0].questions.map((q: { text: string }) => q.text)).toEqual(['지원 동기를 적어 주세요', '가장 어려웠던 경험을 적어 주세요']);
    expect(db.coverLetters[0].posting).toMatchObject({ company: '가온소프트' });
    expect(done.items.at(-1)).toMatchObject({ kind: 'SAY', text: '자소서를 쓰기 시작했어요. 열어서 초안이 나오는 걸 볼 수 있어요.' });
  });

  it('목 서버는 물음을 기다리지 않는 실행에 말을 더하면 409 를 준다', () => {
    const running = begin('spring 공고 보여 줘');
    const say = () => req('POST', `/assistant/runs/${running.id}/messages`, { text: '이어서 해 줘' });
    expect(say()).toMatchObject({ status: 409, error: { code: 'INVALID_STATE' } });
    advance(10);
    expect(read(running.id).state).toBe('DONE');
    expect(say()).toMatchObject({ status: 409, error: { code: 'INVALID_STATE' } });
    expect(shape(running.id)).toEqual(['USER', 'STEP:FIND_POSTINGS:true', 'SAY']); // 말이 더해지지 않았다
  });
});

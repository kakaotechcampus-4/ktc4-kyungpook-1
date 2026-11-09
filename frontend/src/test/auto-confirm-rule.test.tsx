import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CardPage } from '@/features/cards/CardPage';
import { InterviewPage } from '@/features/cards/InterviewPage';
import { NewCardPage } from '@/features/cards/NewCardPage';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';
import { connectMockServer } from './profileHarness';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));

type Any = Record<string, any>;
const req = (method: string, path: string, body: Any = {}) => handle(method, path, new URLSearchParams(), body, true, {});
const detail = (id: string) => req('GET', `/cards/${id}`).data as Any;
const manual = () => req('POST', '/cards/manual/draft', { title: '직접 작성', period: '', repoId: null }).data as Any;
const save = (id: string, body: Any) => req('PATCH', `/cards/${id}/draft`, body);
const body = (over: Any) => ({ situation: null, task: null, action: null, result: null, ...over });

beforeEach(resetDb);
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); });

describe('G1 목 서버의 자동 확정은 서버와 같다', () => {
  it('상황과 행동이 차 있고 확인 필요 칸이 없으면 과제·결과가 비어 있어도 확정하고, 상황이나 행동을 비우면 작성 중으로 돌아간다', () => {
    const c = manual();
    save(c.id, body({ situation: '상황' }));
    expect(detail(c.id).status).toBe('DRAFT'); // 행동이 비어 있다

    save(c.id, body({ situation: '상황', action: '행동' }));
    expect(detail(c.id)).toMatchObject({ status: 'CONFIRMED', version: { task: null, result: null } });
    expect(detail(c.id).confirmedAt).not.toBeNull();

    save(c.id, body({ situation: '상황' }));
    expect(detail(c.id)).toMatchObject({ status: 'DRAFT', confirmedAt: null });
  });

  it('확인 필요 칸이 있으면 상황과 행동이 차 있어도 확정하지 않는다', () => {
    const c = detail('card_01'); // 결과 칸에 확인 필요 표시가 있다
    expect(c.lowConfidenceFields.map((f: Any) => f.field)).toEqual(['R']);
    save(c.id, { situation: '고친 상황', task: c.version.task, action: c.version.action, result: c.version.result });
    expect(detail(c.id).status).toBe('DRAFT');
  });

  it('시간 초과로 칸이 빈 카드는 상황과 행동이 차 있어도 확정하지 않는다', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-06T00:00:00Z'));
    const weak = (req('GET', '/repos/r_auth/candidates').data as Any).candidates.find((x: Any) => x.status === 'NEW' && x.weak);
    const made = req('POST', '/repos/r_auth/cards', { candidateIds: [weak.id] }).data as Any;
    vi.setSystemTime(new Date('2026-11-06T00:02:00Z'));
    req('GET', `/jobs/${made.jobId}`);
    const card = detail(made.cardIds[0]);
    expect(card.generation.partial).toBe(true);
    expect(card.version.situation).toBeTruthy();
    expect(card.version.action).toBeTruthy();
    expect(card.status).toBe('DRAFT');
  });
});

describe('G2 목 서버는 확정된 카드에서도 되묻기를 받는다', () => {
  it('질문·답·건너뛰기가 409 없이 되고 카드 상태는 확정 그대로다', () => {
    const c = manual();
    save(c.id, body({ situation: '상황', action: '행동' }));

    const asked = req('POST', `/cards/${c.id}/interview`, { field: 'T' });
    expect(asked.status).toBe(200);
    const answered = req('POST', `/cards/${c.id}/interview/1/answer`, { kind: 'TEXT', text: '맡은 일', source: 'USER_STATED' });
    expect(answered).toMatchObject({ status: 200, data: { status: 'CONFIRMED', version: { task: '맡은 일', source: 'INTERVIEW' } } });

    expect(req('POST', `/cards/${c.id}/interview`, { field: 'R' }).status).toBe(200);
    expect(req('POST', `/cards/${c.id}/interview/2/answer`, { kind: 'SKIP' })).toMatchObject({ status: 200, data: { status: 'CONFIRMED' } });
  });
});

function mount(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  const router = createMemoryRouter([
    { path: '/cards/:cardId', element: <CardPage /> },
    { path: '/cards/:cardId/interview', element: <InterviewPage /> },
  ], { initialEntries: [path] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  return router;
}
const confirmedWith = (over: Any) => {
  const c = manual();
  save(c.id, body({ situation: '상황', action: '행동', ...over }));
  expect(detail(c.id).status).toBe('CONFIRMED');
  return c.id as string;
};

describe('G3 확정된 카드의 빈 과제·결과 안내', () => {
  it('빈 칸 이름을 말하고, 질문 받기를 누르면 그 칸의 되묻기로 간다', async () => {
    connectMockServer();
    const id = confirmedWith({ task: '맡은 일' });
    const router = mount(`/cards/${id}`);

    expect(await screen.findByText('결과 칸이 비어 있어요. 한두 가지만 알려 주면 채울 수 있어요')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '질문 받기' }));
    await waitFor(() => expect(router.state.location.pathname).toBe(`/cards/${id}/interview`));
    expect(router.state.location.search).toBe('?field=R');
  });

  it('두 칸이 다 비면 한 번에 말하고 첫 빈 칸부터 묻는다', async () => {
    connectMockServer();
    const id = confirmedWith({});
    const router = mount(`/cards/${id}`);

    expect(await screen.findByText('과제, 결과 칸이 비어 있어요. 한두 가지만 알려 주면 채울 수 있어요')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '질문 받기' }));
    await waitFor(() => expect(router.state.location.search).toBe('?field=T'));
  });

  it('빈 칸이 없으면 안내도 버튼도 없다', async () => {
    connectMockServer();
    const id = confirmedWith({ task: '맡은 일', result: '달라진 점' });
    mount(`/cards/${id}`);

    await screen.findByRole('heading', { level: 1, name: '직접 작성' });
    expect(screen.queryByText(/칸이 비어 있어요/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '질문 받기' })).not.toBeInTheDocument();
  });
});

describe('G4 확정된 카드에서 되묻기 화면이 그대로 열린다', () => {
  it('열자마자 정한 칸의 질문이 나오고 답하면 새 버전이 쌓인 채 확정 그대로다', async () => {
    connectMockServer();
    const id = confirmedWith({});
    mount(`/cards/${id}/interview?field=T`);

    expect(await screen.findByRole('heading', { name: /^Q1\. \S/ })).toBeInTheDocument();
    fireEvent.change(screen.getByRole('textbox', { name: '답변' }), { target: { value: '로그인 실패 원인을 알리는 일이었다' } });
    fireEvent.click(screen.getByRole('button', { name: '답 보내기' }));

    await waitFor(() => expect(detail(id).version.task).toBe('로그인 실패 원인을 알리는 일이었다'));
    expect(detail(id).status).toBe('CONFIRMED');
    expect(db.cards.find((c) => c.id === id)!.versions.at(-1)!.source).toBe('INTERVIEW');
    expect(screen.queryByText('카드를 확정했어요')).not.toBeInTheDocument(); // 이 화면에서 확정한 것이 아니다
  });
});

describe('N1 직접 쓰기의 다 썼어요', () => {
  it('이미 자동으로 확정된 카드여도 남은 글까지 저장하고 오류 없이 카드 화면으로 간다', async () => {
    connectMockServer();
    const router = createMemoryRouter([
      { path: '/cards/new', element: <NewCardPage /> },
      { path: '/cards/:cardId', element: <p>카드 화면</p> },
    ], { initialEntries: ['/cards/new?write=1'] });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);

    fireEvent.change(screen.getByLabelText('카드 제목'), { target: { value: '직접 쓴 카드' } });
    fireEvent.change(screen.getByLabelText('상황'), { target: { value: '배포 담당이 없었다' } });
    fireEvent.change(screen.getByLabelText('행동'), { target: { value: '파이프라인을 직접 만들었다' } });
    fireEvent.click(screen.getByRole('button', { name: '작성 시작' }));
    await screen.findByRole('button', { name: '다 썼어요' });
    await waitFor(() => expect(db.cards.at(-1)!.status).toBe('CONFIRMED')); // 저장하는 순간 서버가 확정했다

    fireEvent.change(screen.getByLabelText('과제'), { target: { value: '배포를 자동으로 해야 했다' } });
    fireEvent.click(screen.getByRole('button', { name: '다 썼어요' }));

    expect(await screen.findByText('카드 화면')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(`/cards/${db.cards.at(-1)!.id}`);
    expect(router.state.location.search).toBe('');
    expect(detail(db.cards.at(-1)!.id)).toMatchObject({ status: 'CONFIRMED', version: { task: '배포를 자동으로 해야 했다' } });
  });
});

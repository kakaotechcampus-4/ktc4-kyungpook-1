import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { coverLetterApi } from '@/api/coverLetter';
import { endpoints } from '@/api/endpoints';
import { matchingApi } from '@/api/matching';
import { CoverLetterDraftPage } from '@/features/coverLetter/CoverLetterDraftPage';
import { CoverLetterSetupPage } from '@/features/coverLetter/CoverLetterSetupPage';
import { MatchDetailPage } from '@/features/match/MatchDetailPage';
import { CONFIG } from '@/lib/config';
import { SLOT_MARK, charCount } from '@/lib/coverLetter';
import { toast } from '@/lib/toast';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';
import sampleList from '../../docs/examples/matching/list.json';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/exportCard', () => ({ copyText: vi.fn(async () => true) }));
vi.mock('@/lib/toast', async (importOriginal) => ({ ...(await importOriginal<typeof import('@/lib/toast')>()), toast: vi.fn() }));

// 계약 예시 순서: 가온소프트(A) · 나래랩스(B) · 다온페이(B, 필수 없음) · 라온모빌리티(C) · 마루데이터(D)
const [GAON, , , , MARU] = sampleList.data.map((x) => x.id);
const call = (method: string, path: string, body: Record<string, unknown> = {}) => handle(method, path, new URLSearchParams(), body, true);
/** 화면이 부르는 API 함수를 목 라우터에 연결한다 (실제 fetch 없이). */
function wire() {
  const data = (method: string, path: string, body?: Record<string, unknown>) => {
    const res = call(method, path, body);
    if (res.status >= 400) throw new ApiError(res.error!.code, res.error!.message, res.status);
    return res.data as never;
  };
  vi.spyOn(endpoints, 'cards').mockImplementation(async () => data('GET', '/cards'));
  vi.spyOn(matchingApi, 'list').mockImplementation(async () => data('GET', '/matches'));
  vi.spyOn(matchingApi, 'detail').mockImplementation(async (id) => data('GET', `/matches/${id}`));
  vi.spyOn(coverLetterApi, 'list').mockImplementation(async () => data('GET', '/cover-letters'));
  vi.spyOn(coverLetterApi, 'detail').mockImplementation(async (id) => data('GET', `/cover-letters/${id}`));
  vi.spyOn(coverLetterApi, 'create').mockImplementation(async (body) => data('POST', '/cover-letters', body));
  vi.spyOn(coverLetterApi, 'save').mockImplementation(async (id, text) => data('PATCH', `/cover-letters/${id}`, { text }));
}
function mount(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  const router = createMemoryRouter([
    { path: '/match/:matchId', element: <MatchDetailPage /> },
    { path: '/cover-letter', element: <CoverLetterSetupPage /> },
    { path: '/cover-letter/:letterId', element: <CoverLetterDraftPage /> },
    { path: '/cards/new', element: <p>new card</p> },
    { path: '/repos', element: <p>repos</p> },
  ], { initialEntries: [path] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  return { router, client };
}

beforeEach(() => { resetDb(); wire(); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers(); vi.unstubAllEnvs(); });

describe('공고 상세에서 자소서로', () => {
  it('공고 상세의 자소서 링크가 그 공고를 가리킨다', async () => {
    mount(`/match/${GAON}`);
    expect(await screen.findByRole('link', { name: '이 공고로 자소서 초안 만들기' })).toHaveAttribute('href', `/cover-letter?match=${GAON}`);
  });
});

describe('자소서 초안 만들기', () => {
  it('지원할 공고를 정하면 그 공고의 필수·우대 조건을 뒷받침하는 카드만 먼저 골라 준다', async () => {
    mount(`/cover-letter?match=${GAON}`);
    await screen.findByText('사용할 카드');
    await waitFor(() => expect(screen.getByRole('checkbox', { name: '토큰 만료 자동 재발급 사용' })).toBeChecked());
    expect(screen.getByRole('checkbox', { name: '팀원과 토큰 저장 위치로 갈린 경험 사용' })).not.toBeChecked();
    expect(screen.getAllByText('이 공고 조건 근거')).toHaveLength(1);
    expect(screen.getByText('카드 1장으로 만들어요')).toBeInTheDocument();
  });

  it('근거 카드가 없는 공고는 알려 주고 아무 카드도 고르지 않는다', async () => {
    mount(`/cover-letter?match=${MARU}`);
    expect(await screen.findByText('이 공고의 조건을 뒷받침하는 확정 카드가 아직 없어요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '초안 만들기' })).toBeDisabled();
  });

  it('공고 없이 열면 확정 카드를 모두 고르고, 확정하지 않은 카드는 고를 수 없다', async () => {
    mount('/cover-letter');
    await screen.findByText('사용할 카드');
    expect(screen.queryByRole('checkbox', { name: '로그인 세션 처리 사용' })).not.toBeInTheDocument(); // 초안 카드
    const boxes = screen.getAllByRole('checkbox');
    expect(boxes.every((box) => box.getAttribute('aria-checked') === 'true')).toBe(true);
    for (const box of boxes) fireEvent.click(box);
    expect(screen.getByRole('button', { name: '초안 만들기' })).toBeDisabled();
  });

  it('초안을 만들면 초안 화면으로 이동하고, 두 번 눌러도 한 번만 만든다', async () => {
    const { router } = mount(`/cover-letter?match=${GAON}`);
    await waitFor(() => expect(screen.getByRole('checkbox', { name: '토큰 만료 자동 재발급 사용' })).toBeChecked());
    const make = screen.getByRole('button', { name: '초안 만들기' });
    fireEvent.click(make); fireEvent.click(make);
    await waitFor(() => expect(router.state.location.pathname).toMatch(/^\/cover-letter\/cl_/));
    expect(db.coverLetters).toHaveLength(1);
    expect(coverLetterApi.create).toHaveBeenCalledTimes(1);
    expect(coverLetterApi.create).toHaveBeenCalledWith({ matchId: GAON, question: 'MOTIVATION', cardIds: ['card_03'], charLimit: null });
  });

  it('다시 만들기가 이어 준 글자 수 제한(?limit)을 고른 채로 열고, 그 값으로 만든다', async () => {
    const { router } = mount(`/cover-letter?match=${GAON}&limit=700`);
    await waitFor(() => expect(screen.getByRole('checkbox', { name: '토큰 만료 자동 재발급 사용' })).toBeChecked());
    expect(screen.getByRole('combobox', { name: '글자 수 제한' })).toHaveTextContent('700자');
    fireEvent.click(screen.getByRole('button', { name: '초안 만들기' }));
    await waitFor(() => expect(router.state.location.pathname).toMatch(/^\/cover-letter\/cl_/));
    expect(coverLetterApi.create).toHaveBeenCalledWith(expect.objectContaining({ charLimit: 700 }));
  });

  it('선택지에 없는 ?limit 값은 무시하고 제한 없음으로 연다', async () => {
    for (const bad of ['abc', '0', '123456', '']) {
      cleanup();
      mount(`/cover-letter?limit=${bad}`);
      await screen.findByText('사용할 카드');
      expect(screen.getByRole('combobox', { name: '글자 수 제한' })).toHaveTextContent('제한 없음');
    }
  });

  it('지원 동기 문항에서만 "직접 쓸 빈 칸으로 남긴다"고 알려 준다', async () => {
    mount('/cover-letter');
    await screen.findByText('사용할 카드');
    expect(screen.getByText(/AI가 대신 쓰지 않고, 직접 쓸 빈 칸으로 남겨요/)).toBeInTheDocument();
    cleanup();
    mount('/cover-letter?question=GROWTH');
    await screen.findByText('사용할 카드');
    expect(screen.queryByText(/직접 쓸 빈 칸으로 남겨요/)).not.toBeInTheDocument();
  });

  it('목록에 없는 공고로 들어오면 지원 공고 없음으로 본다', async () => {
    mount('/cover-letter?match=no-such-posting');
    await screen.findByText('사용할 카드');
    expect(screen.getByRole('combobox', { name: '지원할 공고' })).toHaveTextContent('지원 공고 없음');
  });

  it('공고 목록을 못 받아도 일반 자소서는 만들 수 있다', async () => {
    vi.spyOn(matchingApi, 'list').mockRejectedValue(new ApiError('AI_UNAVAILABLE', '잠시 실패', 503));
    const { router } = mount(`/cover-letter?match=${GAON}`);
    await screen.findByText('사용할 카드');
    expect(screen.getByRole('combobox', { name: '지원할 공고' })).toHaveTextContent('지원 공고 없음');
    fireEvent.click(screen.getByRole('button', { name: '초안 만들기' }));
    await waitFor(() => expect(router.state.location.pathname).toMatch(/^\/cover-letter\/cl_/));
    expect(coverLetterApi.create).toHaveBeenCalledWith(expect.objectContaining({ matchId: null }));
  });

  it('확정한 카드가 없으면 초안을 만들지 않고 안내한다', async () => {
    db.cards.forEach((card) => { card.status = 'DRAFT'; });
    mount('/cover-letter');
    expect(await screen.findByText('확정한 카드가 있어야 해요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '초안 만들기' })).toBeDisabled();
  });
});

describe('자소서 초안 화면', () => {
  const makeLetter = () => call('POST', '/cover-letters', { matchId: GAON, question: 'MOTIVATION', cardIds: ['card_03', 'card_04'] }).data as { id: string };

  it('문장마다 출처를 구분해 보여주고, 근거가 없어 뺀 공고 조건을 알려준다', async () => {
    const { id } = makeLetter();
    mount(`/cover-letter/${id}`);
    await screen.findByText('AI 초안');
    expect(screen.getAllByText('AI가 이은 문장')).toHaveLength(2);
    expect(screen.getAllByText('확정한 카드 문장')).toHaveLength(2);
    expect(screen.getByText('직접 쓸 칸 · 지원 동기')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /내 경험 · 토큰 만료 자동 재발급/ })).toHaveAttribute('href', '/cards/card_03');
    expect(screen.getByText('근거가 없어 초안에 넣지 않은 공고 조건 1개')).toBeInTheDocument();
    expect(screen.getByText(/Kubernetes 운영 경험/)).toBeInTheDocument();
  });

  it('지원 동기 칸은 비어 있다고 알리고, 직접 쓰러 가면 편집 화면으로 간다', async () => {
    const { id } = makeLetter();
    const { router } = mount(`/cover-letter/${id}`);
    expect(await screen.findByText('지원 동기 칸이 아직 비어 있어요')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '직접 쓰러 가기' }));
    await waitFor(() => expect(router.state.location.search).toBe('?mode=edit'));
    expect(await screen.findByLabelText('자소서 초안')).toHaveValue(db.coverLetters[0].text);
  });

  it('다른 문항에는 직접 쓸 칸이 없다', async () => {
    const { id } = call('POST', '/cover-letters', { matchId: null, question: 'GROWTH', cardIds: ['card_03'] }).data as { id: string };
    mount(`/cover-letter/${id}`);
    await screen.findByText('AI 초안');
    expect(screen.queryByText('지원 동기 칸이 아직 비어 있어요')).not.toBeInTheDocument();
    expect(screen.queryByText('직접 쓸 칸 · 지원 동기')).not.toBeInTheDocument();
  });

  it('복사는 되지만 칸이 비어 있으면 그 사실을 같이 알려 준다', async () => {
    const { id } = makeLetter();
    mount(`/cover-letter/${id}`);
    fireEvent.click(await screen.findByRole('button', { name: '복사' }));
    await waitFor(() => expect(toast).toHaveBeenCalledWith('복사했어요. 지원 동기 칸은 아직 비어 있어요', { tone: 'success' }));
  });

  it('다시 만들기·다시 만들라는 안내 링크가 공고와 글자 수 제한을 이어 준다', async () => {
    const id = (call('POST', '/cover-letters', { matchId: GAON, question: 'MOTIVATION', cardIds: ['card_04'], charLimit: 700 }).data as { id: string }).id;
    mount(`/cover-letter/${id}`);
    const retry = await screen.findByRole('link', { name: '다시 만들기' });
    expect(retry).toHaveAttribute('href', expect.stringContaining('limit=700'));
    expect(retry).toHaveAttribute('href', expect.stringContaining(`match=${GAON}`));
    expect(screen.getByText('근거 카드는 있지만 이번에 고르지 않은 공고 조건 4개')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '카드를 골라 다시 만들기' })).toHaveAttribute('href', expect.stringContaining('limit=700'));
  });

  it('글자 수 제한을 넘으면 몇 자 넘었는지 알려 주고, 넘지 않으면 조용하다', async () => {
    const over = (call('POST', '/cover-letters', { matchId: null, question: 'GROWTH', cardIds: ['card_03'], charLimit: 100 }).data as { id: string }).id;
    mount(`/cover-letter/${over}`);
    const excess = charCount(db.coverLetters[0].text) - 100;
    expect(excess).toBeGreaterThan(0);
    expect(await screen.findByText(`글자 수 제한을 ${excess.toLocaleString()}자 넘었어요`)).toBeInTheDocument();
    cleanup();
    const fits = (call('POST', '/cover-letters', { matchId: null, question: 'GROWTH', cardIds: ['card_03'], charLimit: 5000 }).data as { id: string }).id;
    mount(`/cover-letter/${fits}`);
    await screen.findByText(/\/ 5,000자/);
    expect(screen.queryByText(/글자 수 제한을/)).not.toBeInTheDocument();
  });

  it('고치는 동안 글자 수와 안내가 지금 쓰는 글 기준으로 바뀐다', async () => {
    const id = (call('POST', '/cover-letters', { matchId: null, question: 'MOTIVATION', cardIds: ['card_03'], charLimit: 100 }).data as { id: string }).id;
    mount(`/cover-letter/${id}?mode=edit`);
    const box = await screen.findByLabelText('자소서 초안');
    expect(screen.getByText('지원 동기 칸이 아직 비어 있어요')).toBeInTheDocument();
    expect((box as HTMLTextAreaElement).value).toContain(SLOT_MARK);
    fireEvent.change(box, { target: { value: '가 나 다' } });
    expect(screen.queryByText('지원 동기 칸이 아직 비어 있어요')).not.toBeInTheDocument();
    expect(screen.queryByText(/글자 수 제한을 .*자 넘었어요/)).not.toBeInTheDocument();
    expect(screen.getByText('5자 / 100자')).toBeInTheDocument();
  });

  it('없는 초안은 찾을 수 없다고 안내한다', async () => {
    mount('/cover-letter/cl_nope');
    expect(await screen.findByText('자소서 초안을 찾을 수 없어요')).toBeInTheDocument();
  });

  it('고치면 자동 저장되고, 저장한 뒤 닫으면 직접 고친 초안으로 보인다', async () => {
    const { id } = makeLetter();
    const { router } = mount(`/cover-letter/${id}?mode=edit`);
    const box = await screen.findByLabelText('자소서 초안');
    vi.useFakeTimers();
    fireEvent.change(box, { target: { value: '직접 고친 첫 문장\n\n직접 고친 둘째 문장' } });
    await act(async () => { await vi.advanceTimersByTimeAsync(CONFIG.DRAFT_AUTOSAVE_MS + 1); });
    vi.useRealTimers();
    expect(coverLetterApi.save).toHaveBeenCalledWith(id, '직접 고친 첫 문장\n\n직접 고친 둘째 문장');
    fireEvent.click(screen.getByRole('button', { name: '저장하고 닫기' }));
    await waitFor(() => expect(router.state.location.search).toBe(''));
    expect(await screen.findByText('직접 고침')).toBeInTheDocument();
    expect(screen.getByText('직접 고친 둘째 문장')).toBeInTheDocument();
  });

  it('저장이 실패하면 입력을 지키고 다시 저장할 수 있다', async () => {
    const { id } = makeLetter();
    vi.spyOn(coverLetterApi, 'save').mockRejectedValueOnce(new Error('offline'))
      .mockImplementation(async (letterId, text) => call('PATCH', `/cover-letters/${letterId}`, { text }).data as never);
    mount(`/cover-letter/${id}?mode=edit`);
    const box = await screen.findByLabelText('자소서 초안');
    fireEvent.change(box, { target: { value: '네트워크가 끊겨도 남아야 하는 글' } });
    fireEvent.click(screen.getByRole('button', { name: '저장하고 닫기' }));
    await screen.findByRole('button', { name: '다시 저장하고 닫기' });
    expect(box).toHaveValue('네트워크가 끊겨도 남아야 하는 글');
    fireEvent.click(screen.getByRole('button', { name: '다시 저장하고 닫기' }));
    await waitFor(() => expect(db.coverLetters[0].text).toBe('네트워크가 끊겨도 남아야 하는 글'));
  });

  it('쓴 카드가 바뀌면 다시 만들라고 알린다', async () => {
    const { id } = makeLetter();
    db.cards.find((card) => card.id === 'card_03')!.maskRules = [{ from: '토큰', to: '○○' }]; // 만든 뒤에 가리기를 걸었다
    mount(`/cover-letter/${id}`);
    expect(await screen.findByText('초안을 만든 뒤 사용한 카드가 바뀌었어요')).toBeInTheDocument();
  });
});

describe('서버 구현 전(실서버 모드) 방어', () => {
  it('API 함수는 요청을 보내지 않고 FEATURE_UNAVAILABLE 로 거절한다', async () => {
    vi.restoreAllMocks();
    vi.stubEnv('VITE_API_MOCK', 'false');
    vi.stubEnv('VITE_MATCH_ENABLED', 'false');
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    for (const run of [() => coverLetterApi.list(), () => coverLetterApi.detail('cl_1'), () => coverLetterApi.create({ matchId: null, question: 'GROWTH', cardIds: ['card_03'], charLimit: null }), () => coverLetterApi.save('cl_1', 'x')]) {
      await expect(run()).rejects.toMatchObject({ code: 'FEATURE_UNAVAILABLE' });
    }
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('두 화면 모두 준비 중 안내를 보여준다', async () => {
    vi.stubEnv('VITE_API_MOCK', 'false');
    vi.stubEnv('VITE_MATCH_ENABLED', 'false');
    for (const path of ['/cover-letter', '/cover-letter/cl_1']) {
      cleanup();
      mount(path);
      expect(await screen.findByText('자소서 초안을 준비 중이에요')).toBeInTheDocument();
    }
  });
});

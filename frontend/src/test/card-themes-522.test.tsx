import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { useCard } from '@/api/queries';
import { Card, CardMetadata, CardMetadataPatch, CardSummary, type CardTheme } from '@/api/schemas';
import { CardThemes } from '@/components/cards/CardThemes';
import { cacheCardMetadata } from '@/lib/cacheCardMetadata';
import { toggleTheme } from '@/lib/cardThemes';
import { LATE_THEME_READS_MS } from '@/lib/useThemeSelection';
import { track } from '@/lib/track';
import { handle } from '@/mock/router';
import { MOCK_THEME_AI_DELAY_MS, resetDb } from '@/mock/store';

/** 이슈 #522 — 경험 카드 상세에서 문항 유형(themes)을 보이고 고친다. 계약은 #475 3절, 서버는 BE #564 */
vi.mock('@/lib/track', () => ({ track: vi.fn() }));

const call = (method: string, path: string, body: Record<string, unknown> = {}) => handle(method, path, new URLSearchParams(), body, true);
const readCard = (id: string) => Card.parse(call('GET', `/cards/${id}`).data);
const CONFIRMED = 'card_04'; // 확정된 경험 카드, 유형 2개(갈등 해결, 협업과 소통)
const DRAFT_EXPERIENCE = 'card_02'; // 작성 중 경험 카드, 유형 없음
const TECH = 'card_03'; // 확정된 기술 카드

/** 목 서버를 그대로 쓰되 저장 요청의 도착 순서와 실패를 시험이 정한다 */
const gate = { hold: null as Promise<void> | null, fail: 0, sent: [] as CardTheme[][], active: 0, maxActive: 0 };
function useMockServer() {
  vi.spyOn(endpoints, 'card').mockImplementation(async (id) => readCard(id));
  vi.spyOn(endpoints, 'saveMetadata').mockImplementation(async (id, body) => {
    gate.sent.push([...(body.themes ?? [])]);
    gate.active += 1; gate.maxActive = Math.max(gate.maxActive, gate.active);
    try {
      if (gate.hold) await gate.hold;
      if (gate.fail > 0) { gate.fail -= 1; throw new ApiError('INTERNAL_ERROR', 'offline', 503); }
      const res = call('PATCH', `/cards/${id}/metadata`, body as Record<string, unknown>);
      if (res.status !== 200) throw new ApiError(res.error!.code, res.error!.message, res.status);
      return CardMetadata.parse(res.data);
    } finally { gate.active -= 1; }
  });
}

function Page({ id }: { id: string }) {
  const { card } = useCard(id);
  return card.data ? <CardThemes card={card.data} /> : null;
}
function mount(id: string, client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })) {
  render(<QueryClientProvider client={client}><Page id={id} /></QueryClientProvider>);
  return client;
}
const option = (name: string) => screen.getByRole('checkbox', { name: new RegExp(name) });
const chips = () => within(screen.getByRole('list', { name: '자소서 문항 유형' })).getAllByRole('listitem').map((li) => li.textContent);
const openEditor = async () => { fireEvent.click(await screen.findByRole('button', { name: /^자소서 문항 유형 (수정|고르기)$/ })); return screen.getByRole('group', { name: '자소서 문항 유형' }); };

beforeEach(() => { resetDb(); vi.mocked(track).mockClear(); gate.hold = null; gate.fail = 0; gate.sent = []; gate.active = 0; gate.maxActive = 0; useMockServer(); });
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllEnvs(); vi.restoreAllMocks(); });

describe('계약: 응답과 요청', () => {
  it('reads themes, drops unknown codes, and keeps "no field" different from an empty list', () => {
    const base = readCard(CONFIRMED);
    const { themes: _gone, ...withoutThemes } = base;
    void _gone;
    expect(Card.parse({ ...base, themes: ['CONFLICT', 'GROWTH'] }).themes).toEqual(['CONFLICT', 'GROWTH']);
    expect(Card.parse({ ...base, themes: ['CONFLICT', 'NEW_FROM_SERVER'] }).themes).toEqual(['CONFLICT']);
    expect(Card.parse({ ...base, themes: [] }).themes).toEqual([]);
    expect(Card.parse(withoutThemes).themes).toBeUndefined(); // 옛 서버: 비어 있음이 아니라 모름
    expect(Card.parse({ ...base, themes: 'CONFLICT' }).themes).toBeUndefined(); // 모양이 틀리면 칸이 없는 것
  });

  it('reads themes on the list item too, so the card list cache carries them', () => {
    const [summary] = (call('GET', '/cards').data as unknown[]).filter((item) => (item as { id: string }).id === CONFIRMED);
    expect(CardSummary.parse(summary).themes).toEqual(['CONFLICT', 'COLLABORATION']);
  });

  it('accepts a themes-only patch, an empty list to clear, and rejects 3, duplicates, unknown codes and nothing at all', () => {
    expect(CardMetadataPatch.safeParse({ themes: ['CONFLICT'] }).success).toBe(true);
    expect(CardMetadataPatch.safeParse({ themes: [] }).success).toBe(true);
    expect(CardMetadataPatch.safeParse({ themes: ['CONFLICT', 'GROWTH', 'FAILURE'] }).success).toBe(false);
    expect(CardMetadataPatch.safeParse({ themes: ['GROWTH', 'GROWTH'] }).success).toBe(false);
    expect(CardMetadataPatch.safeParse({ themes: ['MOTIVATION'] }).success).toBe(false); // 지원 동기는 카드 유형이 아니다
    expect(CardMetadataPatch.safeParse({}).success).toBe(false);
  });

  it('sends only themes over HTTP and accepts a server answer without themes (old server)', async () => {
    vi.restoreAllMocks();
    vi.stubEnv('VITE_API_MOCK', 'false'); vi.stubEnv('VITE_CARD_METADATA_ENABLED', 'true');
    const answer = { cardId: 'card', title: '제목', period: null, updatedAt: '2026-10-31T00:00:00Z' };
    const fetcher = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ data: answer, error: null }), { status: 200 }));
    await expect(endpoints.saveMetadata('card', { themes: ['CONFLICT', 'GROWTH'] })).resolves.toEqual(answer);
    const [url, init] = fetcher.mock.calls[0];
    expect(String(url)).toContain('/cards/card/metadata');
    expect(init!.method).toBe('PATCH');
    expect(JSON.parse(init!.body as string)).toEqual({ themes: ['CONFLICT', 'GROWTH'] });
  });

  it('applies acknowledged themes to the card and the list, and leaves them alone when the server omits them', async () => {
    const client = new QueryClient();
    client.setQueryData(keys.card('card_04'), { ...readCard(CONFIRMED) });
    client.setQueryData(keys.cards, [{ id: 'card_04', themes: ['CONFLICT'], title: '옛 제목', period: null, updatedAt: 'a' }]);
    await cacheCardMetadata(client, { cardId: 'card_04', title: '제목', period: null, themes: ['GROWTH'], updatedAt: '2026-10-31T00:00:00Z' });
    expect(client.getQueryData<Card>(keys.card('card_04'))!.themes).toEqual(['GROWTH']);
    expect((client.getQueryData<CardSummary[]>(keys.cards)![0]).themes).toEqual(['GROWTH']);
    await cacheCardMetadata(client, { cardId: 'card_04', title: '새 제목', period: null, updatedAt: '2026-10-31T00:00:01Z' });
    expect(client.getQueryData<Card>(keys.card('card_04'))!.themes).toEqual(['GROWTH']);
    expect((client.getQueryData<CardSummary[]>(keys.cards)![0]).themes).toEqual(['GROWTH']);
  });

  it('keeps the first pick first and refuses a third', () => {
    expect(toggleTheme([], 'GROWTH')).toEqual(['GROWTH']);
    expect(toggleTheme(['GROWTH'], 'CONFLICT')).toEqual(['GROWTH', 'CONFLICT']);
    expect(toggleTheme(['GROWTH', 'CONFLICT'], 'FAILURE')).toEqual(['GROWTH', 'CONFLICT']);
    expect(toggleTheme(['GROWTH', 'CONFLICT'], 'GROWTH')).toEqual(['CONFLICT']);
  });
});

describe('목: 유형 고치기', () => {
  it('lets a confirmed card change themes without reopening it, and does not touch the update time', () => {
    const before = readCard(CONFIRMED);
    const listed = () => (call('GET', '/cards').data as { id: string; updatedAt: string; themes: string[] }[]).find((item) => item.id === CONFIRMED)!;
    const stamp = listed().updatedAt;
    const res = call('PATCH', `/cards/${CONFIRMED}/metadata`, { themes: ['GROWTH'] });
    expect(res.status).toBe(200);
    expect(res.data).toMatchObject({ cardId: CONFIRMED, themes: ['GROWTH'], updatedAt: stamp });
    const after = readCard(CONFIRMED);
    expect(after.status).toBe('CONFIRMED');
    expect(after.themes).toEqual(['GROWTH']);
    expect(after.version).toEqual(before.version);
    expect(listed().themes).toEqual(['GROWTH']);
    expect(listed().updatedAt).toBe(stamp);
  });

  it('still asks to reopen before changing the title of a confirmed card', () => {
    expect(call('PATCH', `/cards/${CONFIRMED}/metadata`, { title: '새 제목', themes: ['GROWTH'] }).status).toBe(409);
    expect(readCard(CONFIRMED).themes).toEqual(['CONFLICT', 'COLLABORATION']);
  });

  it.each([
    ['세 개', { themes: ['CONFLICT', 'GROWTH', 'FAILURE'] }],
    ['같은 값 두 번', { themes: ['GROWTH', 'GROWTH'] }],
    ['모르는 값', { themes: ['MOTIVATION'] }],
  ])('rejects %s with 400', (_name, body) => {
    expect(call('PATCH', `/cards/${CONFIRMED}/metadata`, body).status).toBe(400);
  });

  it('clears with an empty list, and refuses to put themes on a skill card', () => {
    expect(call('PATCH', `/cards/${CONFIRMED}/metadata`, { themes: [] }).data).toMatchObject({ themes: [] });
    expect(readCard(CONFIRMED).themes).toEqual([]);
    expect(call('PATCH', `/cards/${TECH}/metadata`, { themes: ['GROWTH'] }).status).toBe(400);
    expect(readCard(TECH).themes).toEqual([]); // 기술 카드는 늘 []
  });

  it('fills themes a few seconds after confirming, and never overwrites what the user chose', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-31T09:00:00Z'));
    call('PATCH', `/cards/${DRAFT_EXPERIENCE}/draft`, { situation: '팀원과 의견이 갈렸다', task: null, action: '합의 기준을 만들었다', result: null });
    expect(call('POST', `/cards/${DRAFT_EXPERIENCE}/confirm`, { edited: false, maskedFields: [] }).status).toBe(200);
    expect(readCard(DRAFT_EXPERIENCE).themes).toEqual([]); // 확정 응답 직후에는 아직 비어 있다
    vi.setSystemTime(new Date(Date.now() + MOCK_THEME_AI_DELAY_MS + 1));
    expect(readCard(DRAFT_EXPERIENCE).themes?.length).toBeGreaterThan(0); // 다시 읽으면 채워져 있다

    // 사용자가 먼저 고른 카드는 AI 가 덮어쓰지 않는다
    resetDb();
    call('PATCH', `/cards/${DRAFT_EXPERIENCE}/draft`, { situation: '팀원과 의견이 갈렸다', task: null, action: '합의 기준을 만들었다', result: null });
    call('POST', `/cards/${DRAFT_EXPERIENCE}/confirm`, { edited: false, maskedFields: [] });
    call('PATCH', `/cards/${DRAFT_EXPERIENCE}/metadata`, { themes: ['LEADERSHIP'] });
    vi.setSystemTime(new Date(Date.now() + MOCK_THEME_AI_DELAY_MS + 1));
    expect(readCard(DRAFT_EXPERIENCE).themes).toEqual(['LEADERSHIP']);
  });
});

describe('카드 상세: 보이기', () => {
  it('shows the picked themes of a confirmed card with their display names', async () => {
    mount(CONFIRMED);
    await screen.findByText('자소서 문항 유형');
    expect(chips()).toEqual(['갈등 해결', '협업과 소통']);
    expect(screen.getByRole('button', { name: '자소서 문항 유형 수정' })).toBeInTheDocument();
  });

  it('says nothing is picked yet and offers to pick, for a card with an empty list', async () => {
    mount(DRAFT_EXPERIENCE);
    expect(await screen.findByText('정해지지 않았어요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '자소서 문항 유형 고르기' })).toBeInTheDocument();
  });

  it('hides the whole area when the server sent no themes at all (old server)', async () => {
    vi.spyOn(endpoints, 'card').mockImplementation(async (id) => { const { themes: _t, ...rest } = readCard(id); void _t; return Card.parse(rest); });
    const client = mount(CONFIRMED);
    await waitFor(() => expect(client.getQueryData<Card>(keys.card(CONFIRMED))).toBeDefined());
    expect(client.getQueryData<Card>(keys.card(CONFIRMED))!.themes).toBeUndefined();
    expect(screen.queryByText('자소서 문항 유형')).not.toBeInTheDocument();
    expect(screen.queryByText('정해지지 않았어요')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /자소서 문항 유형/ })).not.toBeInTheDocument();
  });

  it('shows nothing for a skill card, whose themes are always empty', async () => {
    const client = mount(TECH);
    await waitFor(() => expect(client.getQueryData<Card>(keys.card(TECH))).toBeDefined());
    expect(screen.queryByText('자소서 문항 유형')).not.toBeInTheDocument();
  });

  it('is read-only where metadata cannot be saved, and hidden when there is nothing to show', async () => {
    vi.stubEnv('VITE_API_MOCK', 'false'); vi.stubEnv('VITE_CARD_METADATA_ENABLED', 'false');
    mount(CONFIRMED);
    await screen.findByText('자소서 문항 유형');
    expect(chips()).toEqual(['갈등 해결', '협업과 소통']);
    expect(screen.queryByRole('button', { name: /자소서 문항 유형/ })).not.toBeInTheDocument();
    cleanup();
    const empty = mount(DRAFT_EXPERIENCE);
    await waitFor(() => expect(empty.getQueryData<Card>(keys.card(DRAFT_EXPERIENCE))).toBeDefined());
    expect(screen.queryByText('자소서 문항 유형')).not.toBeInTheDocument();
  });
});

describe('카드 상세: 고치기', () => {
  it('edits a confirmed card in place: no reopen, one themes-only request in the order picked, then "저장됨"', async () => {
    const reopen = vi.spyOn(endpoints, 'reopen');
    const client = mount(CONFIRMED);
    const group = await openEditor();
    expect(within(group).getAllByRole('checkbox')).toHaveLength(7);
    expect(option('갈등 해결')).toBeChecked(); expect(option('협업과 소통')).toBeChecked();
    fireEvent.click(option('갈등 해결')); // 해제
    await screen.findByText('저장됨');
    fireEvent.click(option('성장과 배움')); // 추가
    await waitFor(() => expect(gate.sent).toEqual([['COLLABORATION'], ['COLLABORATION', 'GROWTH']]));
    await screen.findByText('저장됨');
    expect(reopen).not.toHaveBeenCalled();
    expect(readCard(CONFIRMED).status).toBe('CONFIRMED');
    expect(client.getQueryData<Card>(keys.card(CONFIRMED))!.themes).toEqual(['COLLABORATION', 'GROWTH']);
    fireEvent.click(screen.getByRole('button', { name: '완료' }));
    expect(chips()).toEqual(['협업과 소통', '성장과 배움']);
  });

  it('refreshes the list cache so other screens do not keep old themes', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    vi.spyOn(endpoints, 'cards').mockImplementation(async () => (call('GET', '/cards').data as unknown[]).map((item) => CardSummary.parse(item)));
    client.setQueryData(keys.cards, await endpoints.cards());
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    mount(CONFIRMED, client);
    await openEditor();
    fireEvent.click(option('문제 해결')); // 이미 2개라 막혀 있다
    expect(gate.sent).toEqual([]);
    fireEvent.click(option('갈등 해결'));
    await screen.findByText('저장됨');
    expect(invalidate).toHaveBeenCalledWith({ queryKey: keys.cards });
    expect(client.getQueryData<CardSummary[]>(keys.cards)!.find((item) => item.id === CONFIRMED)!.themes).toEqual(['COLLABORATION']);
  });

  it('blocks a third theme with a disabled box and a sentence, and frees it after one is cleared', async () => {
    mount(CONFIRMED);
    await openEditor();
    expect(option('문제 해결')).toBeDisabled();
    expect(screen.getByText(/2개를 골랐어요\. 다른 유형을 고르려면 하나를 해제해 주세요\./)).toBeInTheDocument();
    fireEvent.click(option('협업과 소통'));
    await waitFor(() => expect(option('문제 해결')).toBeEnabled());
    expect(screen.getByText(/최대 2개까지 고를 수 있어요\./)).toBeInTheDocument();
    expect(screen.getByText(/직접 고르면 AI 가 다시 바꾸지 않아요/)).toBeInTheDocument();
  });

  it('keeps the pick and offers a retry when saving fails, then sends the same pick again', async () => {
    gate.fail = 1;
    mount(DRAFT_EXPERIENCE);
    await openEditor();
    fireEvent.click(option('리더십'));
    await screen.findByText('저장이 안 됐어요. 작성한 내용은 이 화면에 남아 있어요.');
    expect(option('리더십')).toBeChecked(); // 낙관적으로 고른 값이 그대로 남는다
    expect(screen.queryByText('저장됨')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '다시 저장' }));
    await screen.findByText('저장됨');
    expect(gate.sent).toEqual([['LEADERSHIP'], ['LEADERSHIP']]);
    expect(readCard(DRAFT_EXPERIENCE).themes).toEqual(['LEADERSHIP']);
    expect(screen.queryByRole('button', { name: '다시 저장' })).not.toBeInTheDocument();
  });

  it('keeps showing a failed pick after the editor is closed, with the retry beside it', async () => {
    gate.fail = 1;
    mount(DRAFT_EXPERIENCE);
    await openEditor();
    fireEvent.click(option('도전과 목표 달성'));
    await screen.findByRole('button', { name: '다시 저장' });
    fireEvent.click(screen.getByRole('button', { name: '완료' }));
    expect(chips()).toEqual(['도전과 목표 달성']);
    fireEvent.click(screen.getByRole('button', { name: '다시 저장' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: '다시 저장' })).not.toBeInTheDocument());
    expect(readCard(DRAFT_EXPERIENCE).themes).toEqual(['CHALLENGE']);
  });

  it('shows the pick at once, sends one request at a time, and lets the last pick win', async () => {
    let release!: () => void;
    gate.hold = new Promise<void>((resolve) => { release = resolve; });
    mount(DRAFT_EXPERIENCE);
    await openEditor();
    fireEvent.click(option('리더십'));
    expect(option('리더십')).toBeChecked(); // 서버 응답 전에 이미 보인다
    await waitFor(() => expect(gate.sent).toEqual([['LEADERSHIP']])); // 첫 요청이 서버에 가 있는 채로 붙잡는다
    fireEvent.click(option('실패와 극복'));
    fireEvent.click(option('리더십')); // 저장 중에 다시 해제
    expect(option('실패와 극복')).toBeChecked(); expect(option('리더십')).not.toBeChecked();
    expect(screen.getByText('저장 중…')).toBeInTheDocument();
    await act(async () => { await Promise.resolve(); });
    expect(gate.sent).toEqual([['LEADERSHIP']]); // 앞 요청이 끝나기 전에는 다음 요청을 보내지 않는다
    expect(gate.active).toBe(1);
    await act(async () => { gate.hold = null; release(); });
    await screen.findByText('저장됨');
    expect(gate.maxActive).toBe(1);
    expect(gate.sent).toEqual([['LEADERSHIP'], ['FAILURE']]); // 중간 선택은 건너뛰고 가장 나중 선택만 이어서 보낸다
    expect(readCard(DRAFT_EXPERIENCE).themes).toEqual(['FAILURE']);
    expect(option('실패와 극복')).toBeChecked();
  });

  it('sends nothing when a failed pick is switched back to what the server already has', async () => {
    gate.fail = 1;
    mount(DRAFT_EXPERIENCE);
    await openEditor();
    fireEvent.click(option('리더십'));
    await screen.findByRole('button', { name: '다시 저장' });
    fireEvent.click(option('리더십')); // 서버 값(없음)으로 되돌림
    await waitFor(() => expect(screen.queryByRole('button', { name: '다시 저장' })).not.toBeInTheDocument());
    expect(gate.sent).toEqual([['LEADERSHIP']]);
    expect(screen.getByText('고친 곳 없음')).toBeInTheDocument();
  });

  it('clears every theme with an empty list, which also stops the AI from filling them later', async () => {
    mount(CONFIRMED);
    await openEditor();
    fireEvent.click(option('갈등 해결'));
    await screen.findByText('저장됨');
    fireEvent.click(option('협업과 소통'));
    await waitFor(() => expect(gate.sent.at(-1)).toEqual([]));
    await screen.findByText('저장됨');
    fireEvent.click(screen.getByRole('button', { name: '완료' }));
    expect(await screen.findByText('정해지지 않았어요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '자소서 문항 유형 고르기' })).toBeInTheDocument();
  });

  it('labels the group for assistive tech and gives every box its own name and description', async () => {
    mount(DRAFT_EXPERIENCE);
    const group = await openEditor();
    expect(group.tagName).toBe('FIELDSET');
    expect(group).toHaveAccessibleDescription(/최대 2개까지 고를 수 있어요/);
    for (const name of ['협업과 소통', '갈등 해결', '문제 해결', '리더십', '도전과 목표 달성', '실패와 극복', '성장과 배움']) {
      expect(within(group).getByRole('checkbox', { name: new RegExp(name) })).toBeInTheDocument();
    }
    expect(within(group).getByRole('checkbox', { name: /팀으로 함께 성과를 낸 경험/ })).toBeInTheDocument();
  });

  it('reports only the count of picks to analytics, never which themes', async () => {
    mount(DRAFT_EXPERIENCE);
    await openEditor();
    fireEvent.click(option('리더십'));
    await screen.findByText('저장됨');
    expect(track).toHaveBeenCalledWith('card_themes_edited', { cardId: DRAFT_EXPERIENCE, count: 1 });
    expect(JSON.stringify(vi.mocked(track).mock.calls)).not.toMatch(/LEADERSHIP|리더십/);
  });
});

describe('확정 직후 늦게 채워지는 유형', () => {
  const justConfirmed = (id: string) => ({ ...readCard(id), status: 'CONFIRMED' as const, confirmedAt: new Date().toISOString(), themes: [] });

  it('re-reads a just confirmed experience card a few times, then gives up with the plain empty message', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const card = vi.spyOn(endpoints, 'card').mockImplementation(async (id) => justConfirmed(id));
    mount(DRAFT_EXPERIENCE);
    expect(await screen.findByText('AI 가 유형을 고르는 중일 수 있어요')).toBeInTheDocument();
    expect(card).toHaveBeenCalledTimes(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(LATE_THEME_READS_MS[0] + 10); });
    await waitFor(() => expect(card).toHaveBeenCalledTimes(2));
    await act(async () => { await vi.advanceTimersByTimeAsync(LATE_THEME_READS_MS[2] + 10); });
    await waitFor(() => expect(card).toHaveBeenCalledTimes(4));
    expect(await screen.findByText('정해지지 않았어요')).toBeInTheDocument();
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
    expect(card).toHaveBeenCalledTimes(4); // 더 읽지 않는다
  });

  it('shows the AI picks as soon as a re-read brings them, and stops reading', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let themes: CardTheme[] = [];
    const card = vi.spyOn(endpoints, 'card').mockImplementation(async (id) => ({ ...justConfirmed(id), themes }));
    mount(DRAFT_EXPERIENCE);
    await screen.findByText('AI 가 유형을 고르는 중일 수 있어요');
    themes = ['PROBLEM_SOLVING', 'GROWTH'];
    await act(async () => { await vi.advanceTimersByTimeAsync(LATE_THEME_READS_MS[0] + 10); });
    await waitFor(() => expect(chips()).toEqual(['문제 해결', '성장과 배움']));
    const reads = card.mock.calls.length;
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
    expect(card).toHaveBeenCalledTimes(reads);
  });

  it('does not keep re-reading a card that was confirmed long ago or is still a draft', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const card = vi.spyOn(endpoints, 'card').mockImplementation(async (id) => ({ ...justConfirmed(id), confirmedAt: '2026-10-01T00:00:00Z' }));
    mount(DRAFT_EXPERIENCE);
    expect(await screen.findByText('정해지지 않았어요')).toBeInTheDocument();
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
    expect(card).toHaveBeenCalledTimes(1);
  });
});

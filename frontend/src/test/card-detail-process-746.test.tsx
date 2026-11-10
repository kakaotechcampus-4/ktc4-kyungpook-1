import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { Card, DraftStep } from '@/api/schemas';
import { CardPage } from '@/features/cards/CardPage';
import { draftStepSummary } from '@/lib/draftSteps';
import { toast } from '@/lib/toast';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

/** #746 카드 상세 — 확정 카드의 칸 연필, 오른쪽 '만든 과정' 세 줄과 '자세히' 창 */
vi.mock('@/lib/track', () => ({ track: vi.fn() }));
vi.mock('@/lib/jobWatcher', () => ({ releaseJobToast: vi.fn(), suppressJobToast: vi.fn() }));
vi.mock('@/lib/toast', () => ({ toast: vi.fn() }));
beforeEach(resetDb);
afterEach(() => { cleanup(); vi.clearAllMocks(); vi.restoreAllMocks(); });

const load = (id: string, extra: Partial<Card> = {}) => ({ ...Card.parse(handle('GET', `/cards/${id}`, new URLSearchParams(), {}, true).data), ...extra }) as Card;
const step = (extra: Record<string, unknown>) => DraftStep.parse({ round: 0, actor: 'MODEL', kind: 'DRAFT', field: null, code: null, value: null, material: null, reason: null, ...extra });
function mount(card: Card) {
  vi.spyOn(endpoints, 'card').mockResolvedValue(card);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  client.setQueryData(keys.card(card.id), card);
  const router = createMemoryRouter([{ path: '/cards/:cardId', element: <CardPage /> }], { initialEntries: [`/cards/${card.id}`] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
  return router;
}
const rail = () => screen.getByRole('region', { name: '만든 과정' });

describe('#746 확정 카드의 칸 연필', () => {
  it('채운 칸마다 연필이 있고, 누르면 카드를 다시 열어 그 칸의 편집 칸으로 간다', async () => {
    const card = load('card_03', { version: { ...load('card_03').version, result: null } });
    const reopen = vi.spyOn(endpoints, 'reopen').mockResolvedValue({ ...card, status: 'DRAFT', confirmedAt: null });
    const router = mount(card);
    // 채운 칸은 '수정', 비운 채 확정한 칸은 '채우기' 연필이다 — 위 '수정' 버튼 없이 네 칸 모두 칸 연필로 연다
    expect(screen.getAllByRole('button', { name: /^(상황|과제|행동|결과) (수정|채우기)$/ }).map((b) => b.getAttribute('aria-label'))).toEqual(['상황 수정', '과제 수정', '행동 수정', '결과 채우기']);
    fireEvent.click(screen.getByRole('button', { name: '행동 수정' }));
    await waitFor(() => expect(router.state.location.search).toBe('?mode=edit&field=A'));
    expect(reopen).toHaveBeenCalledTimes(1);
    expect(toast).toHaveBeenCalledWith('다시 편집할 수 있어요. 확정했던 버전은 작성 이력에 남아요.');
    await waitFor(() => expect(screen.getByRole('textbox', { name: /행동/ })).toHaveFocus());
  });
});

describe('#746 오른쪽 만든 과정', () => {
  it('세 줄 요약은 초안 → 고쳐 쓰기(횟수) → 검사 결과 순서다', () => {
    expect(draftStepSummary(load('card_01').draftSteps)).toEqual(['GitHub 기록으로 초안을 썼어요', '결과 칸을 다시 썼어요', '과제 칸은 검사에서 문제가 남았어요']);
    expect(draftStepSummary([step({ kind: 'DRAFT' }), step({ kind: 'FLAG', field: 'R' }), step({ round: 1, kind: 'REWRITE', field: 'R' }), step({ round: 1, kind: 'PASS', field: 'R' })]))
      .toEqual(['GitHub 기록으로 초안을 썼어요', '결과 칸을 다시 썼어요', '검사를 통과했어요']);
    expect(draftStepSummary(load('card_03').draftSteps)).toEqual(['GitHub 기록으로 초안을 썼어요', '고쳐 쓴 칸은 없어요', '과제, 결과 칸은 검사에서 문제가 남았어요']);
    expect(draftStepSummary([
      step({ kind: 'DRAFT' }), step({ kind: 'FLAG', field: 'R' }), step({ kind: 'FLAG', field: 'A' }),
      step({ round: 1, kind: 'REWRITE', field: 'R' }), step({ round: 1, kind: 'REWRITE', field: 'A' }), step({ round: 1, kind: 'PASS', field: 'A' }), step({ round: 1, kind: 'FLAG', field: 'R' }),
      step({ round: 2, kind: 'REWRITE', field: 'R' }), step({ round: 2, kind: 'REVERT', field: 'R' }),
    ])).toEqual(['GitHub 기록으로 초안을 썼어요', '행동, 결과 칸을 3번 고쳐 썼어요', '결과 칸은 검사에서 문제가 남았어요']);
    expect(draftStepSummary([])).toEqual([]);
  });

  it('확정 카드 오른쪽에 만든 과정 세 줄이 있고, 받을 수 있는 질문 칸과 아래쪽 단계 목록은 없다', () => {
    mount(load('card_03', { changeCount: 0, version: { ...load('card_03').version, versionNo: 1 } }));
    expect(within(rail()).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['GitHub 기록으로 초안을 썼어요', '고쳐 쓴 칸은 없어요', '과제, 결과 칸은 검사에서 문제가 남았어요']);
    expect(screen.queryByText('이 카드로 받을 수 있는 질문')).not.toBeInTheDocument();
    expect(screen.queryByText(/AI 가 한 일/)).not.toBeInTheDocument();
    expect(screen.queryByText('과제 칸: 비어 있어요')).not.toBeInTheDocument();
  });

  it('작성 중 카드 오른쪽에도 만든 과정이 있다', () => {
    mount(load('card_01'));
    expect(within(rail()).getAllByRole('listitem')).toHaveLength(3);
    expect(screen.queryByText(/AI 가 한 일/)).not.toBeInTheDocument();
  });

  it('자세히 보기를 누르면 만든 과정 창에 단계 전부가 보이고, Esc 로 닫으면 자세히 보기 버튼으로 돌아온다', () => {
    mount(load('card_01'));
    const more = within(rail()).getByRole('button', { name: '자세히 보기' });
    more.focus();
    fireEvent.click(more);
    const dialog = screen.getByRole('dialog', { name: '만든 과정' });
    expect(within(dialog).getAllByRole('listitem')).toHaveLength(8);
    expect(within(dialog).getByText('결과 칸: 다시 검사해서 통과했어요')).toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(more).toHaveFocus();
  });

  it('직접 쓴 카드는 "직접 쓴 카드예요" 한 줄이고 자세히 보기가 없다', () => {
    mount(load('card_04'));
    expect(within(rail()).getByText('직접 쓴 카드예요')).toBeInTheDocument();
    expect(within(rail()).queryByRole('button', { name: '자세히 보기' })).not.toBeInTheDocument();
  });
});

describe('#909 오른쪽 작성 이력과 출처', () => {
  const withChanges = (id: string, changeCount: number, versionNo = changeCount + 1) => load(id, { changeCount, version: { ...load(id).version, versionNo } });
  const historyButton = () => within(rail()).queryByRole('button', { name: '작성 이력' });

  it('한 번도 바뀌지 않은 카드(바뀐 횟수 0)에는 바뀌었어요 줄과 작성 이력 버튼이 없다', () => {
    mount(withChanges('card_03', 0));
    expect(within(rail()).queryByText(/바뀌었어요/)).not.toBeInTheDocument();
    expect(historyButton()).not.toBeInTheDocument();
  });

  it('바뀐 카드는 처음 내용에서 서버가 센 횟수만큼 바뀌었어요 줄과 작성 이력 버튼을 보이고, 누르면 작성 이력 창이 열린다', async () => {
    const router = mount(withChanges('card_03', 3));
    expect(within(rail()).getByText('처음 내용에서 3번 바뀌었어요')).toBeInTheDocument();
    fireEvent.click(historyButton()!);
    await waitFor(() => expect(router.state.location.search).toBe('?versions=1'));
    expect(await screen.findByRole('dialog', { name: '작성 이력' })).toBeInTheDocument();
  });

  it('만든 과정 기록이 없는 카드도 바뀐 적이 있으면 작성 이력 버튼을 보인다', () => {
    mount(withChanges('card_04', 1));
    expect(within(rail()).getByText('처음 내용에서 1번 바뀌었어요')).toBeInTheDocument();
    expect(historyButton()).toBeInTheDocument();
    expect(within(rail()).queryByRole('button', { name: '자세히 보기' })).not.toBeInTheDocument();
  });

  it('확정하며 버전만 늘어난 카드(버전 2, 바뀐 횟수 0)에는 바뀌었어요 줄과 작성 이력 버튼이 없다', () => {
    mount(withChanges('card_03', 0, 2));
    expect(within(rail()).queryByText(/바뀌었어요/)).not.toBeInTheDocument();
    expect(historyButton()).not.toBeInTheDocument();
  });

  it('확정한 카드 화면에는 인쇄 버튼이 없다', () => {
    mount(load('card_03'));
    expect(screen.queryByRole('button', { name: /인쇄/ })).not.toBeInTheDocument();
  });

  it('출처는 0개인 항목을 보이지 않고, 보일 것이 하나도 없으면 개수 줄을 그리지 않는다', () => {
    const base = load('card_03');
    const commits = base.evidence.filter((e) => e.type === 'COMMIT');
    expect(commits.length).toBeGreaterThan(0);
    mount({ ...base, evidence: commits, maskRules: [] });
    const count = () => document.querySelector('.card-source__count');
    expect(count()?.textContent).toBe(`근거 커밋 ${commits.length}개`);
    cleanup();
    mount({ ...base, evidence: [], maskRules: [] });
    expect(count()).toBeNull();
  });
});

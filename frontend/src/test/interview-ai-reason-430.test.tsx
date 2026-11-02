import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import { InterviewTurn as InterviewTurnSchema, type Card, type InterviewTurn, type StarField } from '@/api/schemas';
import { InterviewPage } from '@/features/cards/InterviewPage';
import { askJudgement, cleanAiText } from '@/lib/interviewReason';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';

/** #430 후속: 되묻기의 AI 판단을 카드 문장과 구분되는 모양으로 보이고, 사용자가 고른 칸에는 붙이지 않는다 */
vi.mock('@/lib/track', () => ({ track: vi.fn() }));
let card: Card;
let template: InterviewTurn;
beforeEach(() => {
  resetDb();
  card = handle('GET', '/cards/card_01', new URLSearchParams(), {}, true).data as Card;
  card = { ...card, version: { ...card.version, task: null, result: null } };
  template = handle('POST', '/cards/card_01/interview', new URLSearchParams(), { field: 'T' }, true).data as InterviewTurn;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

// 서버가 AI 가 칸을 골랐을 때 보내는 모양(FOLLOW_UP + reason). 사용자가 칸을 골랐으면 USER_REQUEST + reason null 이다
const aiTurn = (field: StarField, turnNo: number, reason: string | null, over: Partial<InterviewTurn> = {}) =>
  ({ ...template, field, turnNo, askedBy: 'FOLLOW_UP', reason, answer: null, outcome: null, maxTurns: 8, question: `${field} 질문`, ...over }) as InterviewTurn;
const answered = (turn: InterviewTurn) => ({ ...turn, answer: { text: `${turn.field} 답`, source: 'USER_STATED' }, outcome: 'ANSWERED' }) as InterviewTurn;

function mount(turns: InterviewTurn[], opts: { suffix?: string; card?: Card } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  client.setQueryData(keys.card(card.id), opts.card ?? card);
  client.setQueryData(keys.interview(card.id), turns);
  const router = createMemoryRouter([{ path: '/cards/:cardId/interview', element: <InterviewPage /> }], { initialEntries: [`/cards/${card.id}/interview${opts.suffix ?? ''}`] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
}
const livePanel = () => document.querySelector('.iv__panel') as HTMLElement;
const liveRegions = () => livePanel().querySelectorAll('[aria-live]');

describe('askJudgement — 질문 위에 보일 AI 판단', () => {
  const turns = [{ turnNo: 1, field: 'T' as StarField }];
  it('AI 가 고른 질문(FOLLOW_UP)의 이유를 칸 이름과 함께 만든다', () => {
    expect(askJudgement({ turnNo: 2, field: 'R', askedBy: 'FOLLOW_UP', reason: '결과에 수치가 없어요' }, turns))
      .toEqual({ head: '결과 칸', reason: '결과에 수치가 없어요' });
  });
  it('같은 칸을 앞에서 이미 물었으면 다시 묻는 것이라고 말한다', () => {
    expect(askJudgement({ turnNo: 2, field: 'T', askedBy: 'FOLLOW_UP', reason: '앞 답에 목표가 빠졌어요' }, turns)?.head).toBe('과제 칸 다시 묻기');
    // 같은 칸이라도 뒤에 있는 질문은 '앞' 이 아니다
    expect(askJudgement({ turnNo: 1, field: 'T', askedBy: 'FOLLOW_UP', reason: '이유' }, [{ turnNo: 2, field: 'T' as StarField }])?.head).toBe('과제 칸');
  });
  it('이유가 없으면 지어내지 않는다', () => {
    for (const reason of [null, '', '   \n ', undefined, 7 as unknown as string]) {
      expect(askJudgement({ turnNo: 2, field: 'R', askedBy: 'FOLLOW_UP', reason: reason as string | null }, turns)).toBeNull();
    }
  });
  it('사용자가 칸을 고른 질문은 값이 와도 AI 의 판단이라고 하지 않는다', () => {
    expect(askJudgement({ turnNo: 2, field: 'R', askedBy: 'USER_REQUEST', reason: '이유가 와도' }, turns)).toBeNull();
  });
  it('줄바꿈과 겹공백은 한 칸으로 줄인다', () => {
    expect(askJudgement({ turnNo: 2, field: 'R', askedBy: 'FOLLOW_UP', reason: '  앞 답에\n\n  수치가   없어요 ' }, turns)?.reason).toBe('앞 답에 수치가 없어요');
    expect(cleanAiText(' 네 칸이\n모두 찼어요 ')).toBe('네 칸이 모두 찼어요');
    expect(cleanAiText(12)).toBeNull();
  });
});

describe('질문 위의 AI 판단', () => {
  it('AI 가 고른 칸과 이유를 질문 위에 한 줄로 보이고, 카드 문장과 구분되는 이름표를 단다', () => {
    mount([answered(aiTurn('A', 1, null)), aiTurn('R', 2, '앞 답에 결과 수치가 없어요')]);
    const label = screen.getByText('AI 판단');
    const block = label.closest('p')!;
    expect(within(block).getByText('결과 칸')).toBeInTheDocument();
    expect(within(block).getByText('앞 답에 결과 수치가 없어요')).toBeInTheDocument();
    // 질문 제목보다 먼저 나온다
    const question = screen.getByRole('heading', { name: /R 질문/ });
    expect(block.compareDocumentPosition(question) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    // 따옴표로도 AI 의 글임을 말한다(색만으로 구분하지 않는다)
    expect(block.querySelector('q')).toHaveTextContent('앞 답에 결과 수치가 없어요');
  });

  it('AI 가 골랐어도 이유가 null 이면(AI 가 틀 질문을 쓴 경우) AI 판단 문구를 지어내지 않는다', () => {
    mount([aiTurn('R', 1, null)]);
    expect(screen.getByRole('heading', { name: /R 질문/ })).toBeInTheDocument();
    expect(screen.queryByText('AI 판단')).not.toBeInTheDocument();
  });

  it('사용자가 직접 고른 칸이면 이유 글이 와도 AI 판단 문구가 없다', () => {
    mount([aiTurn('T', 1, '서버가 잘못 보낸 이유', { askedBy: 'USER_REQUEST' })], { suffix: '?field=T' });
    expect(screen.getByRole('heading', { name: /T 질문/ })).toBeInTheDocument();
    expect(screen.queryByText('AI 판단')).not.toBeInTheDocument();
    expect(screen.queryByText('서버가 잘못 보낸 이유')).not.toBeInTheDocument();
  });

  it('같은 칸을 다시 물을 때는 다시 묻는 것이라고 말하고, 서버 이유(앞 답이 모자란 점)를 그대로 보인다', () => {
    mount([answered(aiTurn('R', 1, '결과가 비어 있어요')), aiTurn('R', 2, '앞 답에 수치가 빠져 있어요')]);
    const block = screen.getByText('AI 판단').closest('p')!;
    expect(within(block).getByText('결과 칸 다시 묻기')).toBeInTheDocument();
    expect(within(block).getByText('앞 답에 수치가 빠져 있어요')).toBeInTheDocument();
  });

  it('이유는 HTML 이 아니라 글로 보이고, 줄바꿈은 한 줄로 합친다', () => {
    const reason = '<img src=x onerror="alert(1)"> 앞 답에\n\n수치가 없어요 <b>굵게</b>';
    mount([aiTurn('R', 1, reason)]);
    const block = screen.getByText('AI 판단').closest('p')!;
    expect(block.querySelector('img')).toBeNull();
    expect(block.querySelector('b')).toBeNull();
    expect(block.querySelector('q')!.textContent).toBe('<img src=x onerror="alert(1)"> 앞 답에 수치가 없어요 <b>굵게</b>');
  });

  it('아주 긴 이유도 줄이거나 자르지 않고 그대로 두며 줄바꿈 가능한 영역에 넣는다', () => {
    const long = `${'가'.repeat(200)} https://example.com/${'a'.repeat(120)}`;
    mount([aiTurn('R', 1, long)]);
    const text = document.querySelector('.ai-judgement__text')!;
    expect(text.textContent).toBe(long);
    expect(text.closest('.ai-live')).not.toBeNull();
  });

  it('낭독 영역(aria-live polite)은 패널에 한 곳이고, 이유가 그 안에 들어 있다', () => {
    mount([aiTurn('R', 1, '앞 답에 수치가 없어요')]);
    expect(liveRegions()).toHaveLength(1);
    expect(liveRegions()[0]).toHaveAttribute('aria-live', 'polite');
    expect(liveRegions()[0]).toContainElement(screen.getByText('AI 판단'));
    // 글자 수 같은 자주 바뀌는 것은 영역 밖이다
    expect(liveRegions()[0]).not.toContainElement(screen.getByRole('textbox', { name: '답변' }));
  });

  it('이유가 없을 때도 낭독 영역은 비어 있는 채로 남아 있다(나중에 생긴 글이 읽히도록)', () => {
    mount([aiTurn('R', 1, null)]);
    expect(livePanel().querySelector('[aria-live]')).toBeEmptyDOMElement();
  });

  it('다음 질문이 같은 이유를 가져도 새 글로 그려 다시 한 번 읽히게 한다', async () => {
    let turns: InterviewTurn[] = [aiTurn('A', 1, '행동이 비어 있어요')];
    vi.spyOn(endpoints, 'interviewTurns').mockImplementation(async () => turns);
    vi.spyOn(endpoints, 'answerInterview').mockImplementation(async (_id, no) => {
      turns = turns.map((turn) => turn.turnNo === no ? answered(turn) : turn);
      return card;
    });
    vi.spyOn(endpoints, 'askInterview').mockImplementation(async () => {
      const next = aiTurn('R', 2, '행동이 비어 있어요'); // 같은 글
      turns = [...turns, next];
      return next;
    });
    mount(turns);
    const first = screen.getByText('AI 판단').closest('p')!;

    fireEvent.change(screen.getByRole('textbox', { name: '답변' }), { target: { value: 'A 답' } });
    fireEvent.click(screen.getByRole('button', { name: '답변 저장' }));

    await screen.findByRole('heading', { name: /R 질문/ });
    const second = screen.getByText('AI 판단').closest('p')!;
    expect(second).not.toBe(first);
    expect(liveRegions()).toHaveLength(1);
  });
});

describe('AI 가 끝낸 이유', () => {
  const finish = (turns: InterviewTurn[], endReason: string | null) => {
    let list = turns;
    vi.spyOn(endpoints, 'interviewTurns').mockImplementation(async () => list);
    vi.spyOn(endpoints, 'answerInterview').mockImplementation(async (_id, no) => {
      list = list.map((turn) => turn.turnNo === no ? answered(turn) : turn);
      return card;
    });
    vi.spyOn(endpoints, 'askInterview').mockResolvedValue(null);
    // 질문이 null 이면 화면이 카드를 다시 읽는다. 서버가 그만 묻기로 한 이유를 카드에 남긴다
    vi.spyOn(endpoints, 'card').mockResolvedValue({ ...card, interviewEndReason: endReason });
    mount(list);
    fireEvent.change(screen.getByRole('textbox', { name: '답변' }), { target: { value: 'T 답' } });
    fireEvent.click(screen.getByRole('button', { name: '답변 저장' }));
  };

  it('AI 가 끝내면 그만 묻는 이유를 AI 판단으로 보이고, 끝난 알림과 같은 낭독 영역 안에 둔다', async () => {
    finish([aiTurn('T', 1, null, { askedBy: 'USER_REQUEST' })], '네 칸이 모두 채워져서 질문을 마칠게요');
    expect(await screen.findByText('AI 가 더 물을 것이 없다고 판단했어요')).toBeInTheDocument();
    const block = (await screen.findByText('네 칸이 모두 채워져서 질문을 마칠게요')).closest('p')!;
    expect(within(block).getByText('AI 판단')).toBeInTheDocument();
    expect(liveRegions()).toHaveLength(1);
    expect(liveRegions()[0]).toContainElement(screen.getByText('AI 가 더 물을 것이 없다고 판단했어요'));
    expect(liveRegions()[0]).toContainElement(block);
  });

  it('이유가 없으면 지금 문구 그대로이고 AI 판단을 지어내지 않는다', async () => {
    finish([aiTurn('T', 1, null, { askedBy: 'USER_REQUEST' })], null);
    expect(await screen.findByText('AI 가 더 물을 것이 없다고 판단했어요')).toBeInTheDocument();
    expect(screen.getByText(/카드를 확인하고 확정하세요/)).toBeInTheDocument();
    expect(screen.queryByText('AI 판단')).not.toBeInTheDocument();
  });

  it('화면을 다시 열어도 카드에 남은 이유로 AI 가 끝낸 상태를 보인다', () => {
    const turns = [answered(aiTurn('T', 1, null, { askedBy: 'USER_REQUEST' }))];
    const ask = vi.spyOn(endpoints, 'askInterview').mockResolvedValue(null);
    vi.spyOn(endpoints, 'interviewTurns').mockResolvedValue(turns);
    mount(turns, { card: { ...card, interviewEndReason: '네 칸이 모두 채워져서 질문을 마칠게요' } });
    expect(screen.getByText('AI 가 더 물을 것이 없다고 판단했어요')).toBeInTheDocument();
    expect(screen.getByText('네 칸이 모두 채워져서 질문을 마칠게요')).toBeInTheDocument();
    expect(screen.getByText('더 묻고 싶은 칸')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '카드 확인하고 확정하기' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'AI 가 고르게 하기' })).not.toBeInTheDocument();
    expect(ask).not.toHaveBeenCalled();
  });

  it('끝낸 이유가 없는 카드를 다시 열면 지금처럼 이어서 묻기를 보인다', () => {
    const turns = [answered(aiTurn('T', 1, null, { askedBy: 'USER_REQUEST' }))];
    vi.spyOn(endpoints, 'interviewTurns').mockResolvedValue(turns);
    mount(turns, { card: { ...card, interviewEndReason: null } });
    expect(screen.getByText('이어서 물을까요?')).toBeInTheDocument();
    expect(screen.queryByText('AI 가 더 물을 것이 없다고 판단했어요')).not.toBeInTheDocument();
  });

  it('이유가 있어도 질문이 열려 있으면 끝난 알림을 보이지 않는다', () => {
    mount([aiTurn('R', 1, '앞 답에 수치가 없어요')], { card: { ...card, interviewEndReason: '지난 이유' } });
    expect(screen.queryByText('AI 가 더 물을 것이 없다고 판단했어요')).not.toBeInTheDocument();
    expect(screen.queryByText('지난 이유')).not.toBeInTheDocument();
  });

  it('확정된 카드의 확정 안내도 낭독 영역 안에 있고, 끝낸 이유가 있을 때만 AI 판단을 붙인다', () => {
    const turns = [answered(aiTurn('T', 1, null, { askedBy: 'USER_REQUEST' }))];
    vi.spyOn(endpoints, 'interviewTurns').mockResolvedValue(turns);
    const confirmedCard = { ...card, status: 'CONFIRMED', confirmedAt: '2026-11-01T00:00:00Z' } as Card;
    mount(turns, { card: { ...confirmedCard, interviewEndReason: null } });
    expect(liveRegions()[0]).toContainElement(screen.getByText('네 칸이 모두 차서 카드를 확정했어요'));
    expect(screen.queryByText('AI 판단')).not.toBeInTheDocument(); // 답으로 확정된 카드는 AI 가 끝낸 것이 아니다
    cleanup();

    mount(turns, { card: { ...confirmedCard, interviewEndReason: '네 칸이 모두 채워져서 질문을 마칠게요' } });
    expect(liveRegions()[0]).toContainElement(screen.getByText('네 칸이 모두 채워져서 질문을 마칠게요'));
    expect(screen.getByText('AI 판단')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '카드 보기' })).toBeInTheDocument();
    expect(screen.queryByText('AI 가 더 물을 것이 없다고 판단했어요')).not.toBeInTheDocument();
  });
});

describe('목 서버의 되묻기 응답', () => {
  const ask = (cardId: string, field?: StarField) => handle('POST', `/cards/${cardId}/interview`, new URLSearchParams(), field ? { field } : {}, true).data as InterviewTurn | null;
  const answer = (cardId: string, turn: InterviewTurn, text: string) => handle('POST', `/cards/${cardId}/interview/${turn.turnNo}/answer`, new URLSearchParams(), { text, source: 'USER_STATED' }, true);

  it('사용자가 칸을 고르면 USER_REQUEST 와 reason null, AI 가 고르면 FOLLOW_UP 과 이유가 온다', () => {
    const first = ask('card_02', 'A')!;
    expect(first).toMatchObject({ askedBy: 'USER_REQUEST', reason: null });
    answer('card_02', first, '테스트를 먼저 붙이고 고쳤다');
    const next = ask('card_02')!;
    expect(next).toMatchObject({ field: 'R', askedBy: 'FOLLOW_UP' });
    expect(next.reason).toEqual(expect.any(String));
    expect(next.reason!.length).toBeLessThanOrEqual(60);
    expect(InterviewTurnSchema.safeParse(next).success).toBe(true);
  });

  it('결과 답에 숫자가 없으면 AI 가 같은 칸을 한 번만 다시 묻고, 그다음에는 빈 칸으로 넘어간다', () => {
    const r1 = ask('card_02', 'R')!;
    answer('card_02', r1, '오류가 눈에 띄게 줄었다');
    const r2 = ask('card_02')!;
    expect(r2).toMatchObject({ field: 'R', askedBy: 'FOLLOW_UP', options: [] });
    expect(r2.reason).toContain('수치');
    answer('card_02', r2, '여전히 숫자는 모르겠다'); // 같은 칸은 두 번까지
    expect(ask('card_02')).toMatchObject({ field: 'A', askedBy: 'FOLLOW_UP' });
  });

  it('결과 답에 숫자가 있으면 같은 칸을 다시 묻지 않는다', () => {
    answer('card_02', ask('card_02', 'R')!, '오류가 주 12건에서 2건으로 줄었다');
    expect(ask('card_02')).toMatchObject({ field: 'A', askedBy: 'FOLLOW_UP' });
  });
});

describe('카드 문장과 섞이지 않는다', () => {
  it('카드 미리보기에는 AI 판단이 들어가지 않고 질문 패널에만 있다', () => {
    mount([answered(aiTurn('A', 1, null)), aiTurn('R', 2, '앞 답에 결과 수치가 없어요')]);
    const preview = screen.getByLabelText('카드 미리보기');
    expect(within(preview).queryByText('AI 판단')).not.toBeInTheDocument();
    expect(within(preview).queryByText('앞 답에 결과 수치가 없어요')).not.toBeInTheDocument();
    expect(within(livePanel()).getByText('앞 답에 결과 수치가 없어요')).toBeInTheDocument();
  });
});

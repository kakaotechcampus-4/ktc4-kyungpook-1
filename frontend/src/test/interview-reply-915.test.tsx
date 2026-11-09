import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { endpoints } from '@/api/endpoints';
import { keys } from '@/api/keys';
import type { Card, EvidenceType, InterviewReply, InterviewTurn } from '@/api/schemas';
import { InterviewPage } from '@/features/cards/InterviewPage';
import { starFieldSpoken } from '@/lib/labels';
import { handle } from '@/mock/router';
import { resetDb } from '@/mock/store';
import { answeredBy, appliedReply } from './replyResult';

vi.mock('@/lib/track', () => ({ track: vi.fn() }));
let card: Card;
let template: InterviewTurn;
beforeEach(() => {
  resetDb();
  card = handle('GET', '/cards/card_01', new URLSearchParams(), {}, true).data as Card;
  card = { ...card, version: { ...card.version, task: null, action: null, result: null } };
  template = handle('POST', '/cards/card_01/interview', new URLSearchParams(), { field: 'T' }, true).data as InterviewTurn;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const open = (over: Partial<InterviewTurn> = {}): InterviewTurn => ({ ...template, turnNo: 1, field: 'T', maxTurns: 8, answer: null, outcome: null, options: [], ...over });
/** 같은 말을 낭독 영역(눈에 안 보이는 글)에도 싣기 때문에, 화면에 그려진 쪽만 센다 */
const shown = (text: string | RegExp) => screen.getAllByText(text).filter((el) => !el.closest('.ai-live'));
const thread = () => document.querySelector('.iv__thread') as HTMLElement;
const withText = (field: 'task' | 'action' | 'result', text: string): Card => ({ ...card, version: { ...card.version, [field]: text } });

function mount(turns: InterviewTurn[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  client.setQueryData(keys.card(card.id), card);
  client.setQueryData(keys.interview(card.id), turns);
  const router = createMemoryRouter([{ path: '/cards/:cardId/interview', element: <InterviewPage /> }], { initialEntries: [`/cards/${card.id}/interview`] });
  render(<QueryClientProvider client={client}><RouterProvider router={router} /></QueryClientProvider>);
}

/** 서버 흉내 — answer 가 돌려줄 응답을 정하고, 질문 목록은 after 가 만든 목록으로 바뀐다 */
function serve(turns: InterviewTurn[], answer: (turn: InterviewTurn, body: { text: string; source: EvidenceType }) => { reply: InterviewReply; after?: InterviewTurn[] }) {
  let list = turns;
  vi.spyOn(endpoints, 'interviewTurns').mockImplementation(async () => list);
  vi.spyOn(endpoints, 'card').mockImplementation(async () => card);
  const ask = vi.spyOn(endpoints, 'askInterview').mockResolvedValue(null);
  const old = vi.spyOn(endpoints, 'answerInterview').mockImplementation(async (_id, no) => {
    list = list.map((t) => (t.turnNo === no ? { ...t, outcome: 'SKIPPED' } as InterviewTurn : t));
    return card;
  });
  const reply = vi.spyOn(endpoints, 'replyInterview').mockImplementation(async (_id, no, body) => {
    const made = answer(list.find((t) => t.turnNo === no)!, body);
    if (made.after) list = made.after;
    return made.reply;
  });
  return { reply, old, ask };
}

async function send(text: string) {
  fireEvent.change(await screen.findByRole('textbox', { name: '답변' }), { target: { value: text } });
  fireEvent.click(screen.getByRole('button', { name: '답 보내기' }));
}

const SENTENCE = '배포 시간을 줄여야 했다.';
const appliedOnTask = (turn: InterviewTurn, body: { text: string; source: EvidenceType }) => {
  card = withText('task', SENTENCE);
  return { reply: appliedReply(card, { written: [{ field: 'T', text: SENTENCE }] }), after: [answeredBy(turn, body, SENTENCE)] };
};

describe('답을 읽고 카드에 넣는 되묻기', () => {
  it('답을 보내면 답을 읽는 API 를 부르고, 넣은 칸 이름과 문장을 보여 준다', async () => {
    const { reply, old } = serve([open()], appliedOnTask);
    mount([open()]);

    await send('배포 시간을 줄여야 했어요');

    expect(await screen.findByText('과제 칸에 이렇게 넣었어요')).toBeInTheDocument();
    expect(within(thread()).getByText(SENTENCE)).toBeInTheDocument();
    expect(reply).toHaveBeenCalledWith(card.id, 1, { text: '배포 시간을 줄여야 했어요', source: 'USER_STATED' });
    expect(old).not.toHaveBeenCalled();
  });

  it("내가 한 말은 내 말풍선에 그대로 남고, 왼쪽 카드의 그 칸에는 넣은 문장과 '방금 넣었어요' 가 보인다", async () => {
    serve([open()], appliedOnTask);
    mount([open()]);

    await send('배포 시간을 줄여야 했어요');

    await screen.findByText('과제 칸에 이렇게 넣었어요');
    expect(thread().querySelector('.chat-bub--me')).toHaveTextContent('배포 시간을 줄여야 했어요');
    const preview = screen.getByLabelText('카드 미리보기');
    expect(within(preview).getByText(SENTENCE)).toBeInTheDocument();
    expect(within(preview).getByText('방금 넣었어요')).toBeInTheDocument();
  });

  it('두 칸에 넣었으면 두 칸의 문장을 모두 보여 준다', async () => {
    const action = '설정을 고쳤다.', result = '배포가 12분에서 4분으로 줄었다.';
    serve([open({ field: 'A' })], (turn, body) => ({
      reply: appliedReply(card, { written: [{ field: 'A', text: action }, { field: 'R', text: result }] }),
      after: [answeredBy(turn, body, action)],
    }));
    mount([open({ field: 'A' })]);

    await send('설정을 고쳤어요. 그 뒤로 배포가 12분에서 4분으로 줄었어요');

    expect(await screen.findByText('결과 얘기도 있어서 두 칸에 나눠 넣었어요')).toBeInTheDocument();
    expect(within(thread()).getByText(action)).toBeInTheDocument();
    expect(within(thread()).getByText(result)).toBeInTheDocument();
  });

  it("모자란 답이면 '아직 카드에 넣지 않았어요' 를 보이고 꼬리 질문을 열며, 다음 질문을 따로 요청하지 않는다", async () => {
    const follow = open({ turnNo: 2, askedBy: 'FOLLOW_UP', questionType: 'FOLLOWUP', question: '무엇을 어떻게 했는지 한 가지만 더 알려 주세요', reason: '무엇을 어떻게 했는지가 빠져 있어요' });
    const { ask } = serve([open()], (turn, body) => ({
      reply: { ...appliedReply(card), outcome: 'ASK_MORE', written: [], restoreVersionNo: null, nextTurn: follow },
      after: [{ ...turn, answer: { text: body.text, source: body.source, original: null }, outcome: 'INSUFFICIENT' }, follow],
    }));
    mount([open()]);

    await send('설정 고쳤어요');

    expect(await screen.findByRole('heading', { name: /무엇을 어떻게 했는지 한 가지만 더 알려 주세요/ })).toBeInTheDocument();
    expect(shown('아직 카드에 넣지 않았어요')).toHaveLength(1);
    expect(screen.getByText('무엇을 어떻게 했는지가 빠져 있어요')).toBeInTheDocument();
    expect(screen.getByText('다음 답과 합쳐서 한 문장으로 넣을게요')).toBeInTheDocument();
    expect(ask).not.toHaveBeenCalled();
  });

  it("상관없는 답이면 질문은 열린 채로 '이 답은 카드에 넣지 않았어요' 를 보이고, 건너뛰는 버튼 두 개를 둔다", async () => {
    const { old } = serve([open()], () => ({ reply: { ...appliedReply(card), outcome: 'NOT_USED', reason: '뜻을 알 수 없는 글자예요', written: [], restoreVersionNo: null } }));
    mount([open()]);

    await send('하하하하하');

    expect(await screen.findByText('이 답은 카드에 넣지 않았어요.')).toBeInTheDocument();
    expect(screen.getByText('뜻을 알 수 없는 글자예요')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /Q1\./ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '잘 기억나지 않아요' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '이 질문은 건너뛸게요' }));
    await waitFor(() => expect(old).toHaveBeenCalledWith(card.id, 1, { kind: 'SKIP' }));
  });

  it("다듬지 못했으면 그대로 넣을지 묻고, '그대로 넣기' 는 친 글을 기존 저장 API 로 보내며 '다시 시도' 는 같은 글로 다시 부른다", async () => {
    const { reply, old } = serve([open()], () => ({ reply: { ...appliedReply(card), outcome: 'UNPOLISHED', written: [], restoreVersionNo: null } }));
    old.mockImplementation(async () => card);
    mount([open()]);

    await send('배포 시간을 줄여야 했어요');
    await screen.findByRole('button', { name: '그대로 넣기' });
    expect(shown(/지금은 문장으로 다듬지 못했어요/)).toHaveLength(1);

    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(reply).toHaveBeenCalledTimes(2));
    expect(reply).toHaveBeenLastCalledWith(card.id, 1, { text: '배포 시간을 줄여야 했어요', source: 'USER_STATED' });

    fireEvent.click(await screen.findByRole('button', { name: '그대로 넣기' }));
    await waitFor(() => expect(old).toHaveBeenCalledWith(card.id, 1, { text: '배포 시간을 줄여야 했어요', source: 'USER_STATED' }));
  });

  it('고치기를 누르면 글 칸이 열리고, 저장하면 그 칸만 적은 그대로 저장되며, 취소하면 넣은 문장 그대로다', async () => {
    serve([open()], appliedOnTask);
    const save = vi.spyOn(endpoints, 'saveDraft').mockImplementation(async (id, fields) => ({ cardId: id, versionNo: 3, ...fields, savedAt: '2026-11-10T00:00:00Z' }));
    mount([open()]);
    await send('배포 시간을 줄여야 했어요');

    fireEvent.click(await screen.findByRole('button', { name: '고치기' }));
    const area = screen.getByRole('textbox', { name: '과제 칸 문장' });
    expect(area).toHaveValue(SENTENCE);
    fireEvent.change(area, { target: { value: '배포 시간을 줄여 하루 여러 번 올려야 했다' } });
    fireEvent.click(screen.getByRole('button', { name: '취소' }));
    expect(screen.queryByRole('textbox', { name: '과제 칸 문장' })).not.toBeInTheDocument();
    expect(within(thread()).getByText(SENTENCE)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '고치기' }));
    fireEvent.change(screen.getByRole('textbox', { name: '과제 칸 문장' }), { target: { value: '배포 시간을 줄여 하루 여러 번 올려야 했다' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    expect(await screen.findByText('직접 고친 글로 바꿨어요')).toBeInTheDocument();
    expect(save).toHaveBeenCalledWith(card.id, { situation: card.version.situation, task: '배포 시간을 줄여 하루 여러 번 올려야 했다', action: null, result: null });
    expect(within(thread()).getByText('배포 시간을 줄여 하루 여러 번 올려야 했다')).toBeInTheDocument();
  });

  it("되돌리기를 누르면 넣기 전 버전으로 되돌리고 버튼 대신 '넣기 전으로 되돌렸어요' 를 보인다", async () => {
    const before = { ...card };
    serve([open()], appliedOnTask);
    const back = vi.spyOn(endpoints, 'restoreVersion').mockResolvedValue(before);
    mount([open()]);
    await send('배포 시간을 줄여야 했어요');

    fireEvent.click(await screen.findByRole('button', { name: '되돌리기' }));

    await waitFor(() => expect(shown('넣기 전으로 되돌렸어요')).toHaveLength(1));
    expect(back).toHaveBeenCalledWith(card.id, 1);
    expect(screen.queryByRole('button', { name: '되돌리기' })).not.toBeInTheDocument();
  });

  it('모른다는 답이면 그 질문을 넘긴 것으로 보인다', async () => {
    const { ask } = serve([open()], (turn) => ({ reply: { ...appliedReply(card), outcome: 'SKIPPED', written: [], restoreVersionNo: null }, after: [{ ...turn, outcome: 'SKIPPED' }] }));
    mount([open()]);

    await send('잘 기억나지 않아요');

    expect(await screen.findByText('넘긴 질문')).toBeInTheDocument();
    await waitFor(() => expect(ask).toHaveBeenCalledWith(card.id, undefined));
  });

  it("입력칸 예시는 말하듯 적혀 있고, 보기가 있으면 끝에 '잘 기억나지 않아요' 가 붙으며 보기가 없으면 보기 묶음을 그리지 않는다", () => {
    mount([open({ options: ['로그를 보고 고쳤어요'] })]);

    expect(screen.getByRole('textbox', { name: '답변' })).toHaveAttribute('placeholder', starFieldSpoken.T);
    expect(starFieldSpoken.T).toMatch(/요$/);
    const chips = Array.from(thread().querySelectorAll('.chip--option')).map((chip) => chip.textContent);
    expect(chips).toEqual(['로그를 보고 고쳤어요', '잘 기억나지 않아요']);
    fireEvent.click(screen.getByRole('button', { name: '잘 기억나지 않아요' }));
    expect(screen.getByRole('textbox', { name: '답변' })).toHaveValue('잘 기억나지 않아요');

    cleanup();
    mount([open()]);
    expect(screen.queryByText('이 중에서 골라도 돼요')).not.toBeInTheDocument();
  });

  it('질문 근거 묶음을 그리지 않는다', () => {
    mount([{ ...open(), found: ['커밋 abc'], missing: ['계기'] } as InterviewTurn]);

    expect(screen.queryByText('질문 근거 보기')).not.toBeInTheDocument();
    expect(screen.queryByText('코드에서 찾은 것')).not.toBeInTheDocument();
  });

  it("다시 들어왔을 때 다듬기 전 답이 있는 질문은 내 말과 넣은 문장을 따로 보이고, 모자란 답으로 끝난 질문은 '아직 카드에 넣지 않았어요' 를 보인다", () => {
    mount([
      open({ turnNo: 1, field: 'T', question: 'T 질문', answer: { text: SENTENCE, source: 'USER_STATED', original: '배포 시간을 줄여야 했어요' }, outcome: 'ANSWERED' }),
      open({ turnNo: 2, field: 'A', question: 'A 질문', answer: { text: '설정 고쳤어요', source: 'USER_STATED', original: null }, outcome: 'INSUFFICIENT' }),
      open({ turnNo: 3, field: 'A', question: 'A 다시' }),
    ]);

    const bubbles = Array.from(thread().querySelectorAll('.chat-bub--me')).map((b) => b.textContent);
    expect(bubbles).toEqual(['배포 시간을 줄여야 했어요', '설정 고쳤어요']);
    expect(screen.getByText('과제 칸에 이렇게 넣었어요')).toBeInTheDocument();
    expect(within(thread()).getByText(SENTENCE)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '고치기' })).not.toBeInTheDocument();
    expect(screen.queryByText('내 답변, 저장됨')).not.toBeInTheDocument();
    expect(screen.getByText('아직 카드에 넣지 않았어요')).toBeInTheDocument();
  });
});

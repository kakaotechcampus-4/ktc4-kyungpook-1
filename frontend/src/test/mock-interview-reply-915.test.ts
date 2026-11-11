import { beforeEach, describe, expect, it } from 'vitest';
import { handle } from '@/mock/router';
import { db, resetDb } from '@/mock/store';

type Any = Record<string, any>;
const req = (method: string, path: string, body: Any = {}) => handle(method, path, new URLSearchParams(), body, true);
const ask = (field: string) => req('POST', '/cards/card_01/interview', { field }).data as Any;
const reply = (turnNo: number, text: string) => req('POST', `/cards/card_01/interview/${turnNo}/reply`, { text, source: 'USER_STATED' });
const current = () => (req('GET', '/cards/card_01').data as Any).version as Any;

beforeEach(() => {
  resetDb();
  const v = current();
  req('PATCH', '/cards/card_01/draft', { situation: v.situation, task: null, action: null, result: null });
});

describe('목의 답 읽기 API', () => {
  it('목에서 뜻 없는 글자뿐인 답은 카드를 바꾸지 않고 넣지 않음으로 답한다', () => {
    const turn = ask('A');
    const before = current();

    const r = reply(turn.turnNo, '하하하하하').data as Any;

    expect(r).toMatchObject({ outcome: 'NOT_USED', reason: '뜻을 알 수 없는 글자예요', written: [], restoreVersionNo: null });
    expect(current()).toEqual(before);
    expect(db.interview.card_01.find((t) => t.turnNo === turn.turnNo)!.answer).toBeNull();
  });

  it('목에서 짧은 답은 꼬리 질문을 만들고, 꼬리 질문의 답은 앞 답과 합쳐 넣는다', () => {
    const turn = ask('A');

    const first = reply(turn.turnNo, '설정 했어요').data as Any;
    expect(first.outcome).toBe('ASK_MORE');
    expect(first.nextTurn).toMatchObject({ field: 'A', questionType: 'FOLLOWUP' });
    expect(db.interview.card_01.find((t) => t.turnNo === turn.turnNo)).toMatchObject({ outcome: 'INSUFFICIENT', answer: { text: '설정 했어요' } });

    const second = reply(first.nextTurn.turnNo, '바뀐 모듈만 다시 빌드했어요').data as Any;
    expect(second.outcome).toBe('APPLIED');
    expect(second.written[0]).toEqual({ field: 'A', text: '설정 했다. 바뀐 모듈만 다시 빌드했다' });
    expect(second.restoreVersionNo).toEqual(expect.any(Number));
  });

  it('목에서 다른 칸의 내용이 든 답은 비어 있는 그 칸에도 넣는다', () => {
    const turn = ask('A');

    const r = reply(turn.turnNo, '배포 로그를 보고 바뀐 모듈만 다시 빌드했어요. 그 뒤로 배포가 12분에서 4분으로 줄었어요').data as Any;

    expect(r.outcome).toBe('APPLIED');
    expect(r.written.map((w: Any) => w.field)).toEqual(['A', 'R']);
    expect(r.written[0].text).toBe('배포 로그를 보고 바뀐 모듈만 다시 빌드했다');
    expect(r.written[1].text).toBe('그 뒤로 배포가 12분에서 4분으로 줄었다');
    expect(current()).toMatchObject({ action: r.written[0].text, result: r.written[1].text });
  });
});

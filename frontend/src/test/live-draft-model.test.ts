import { describe, expect, it } from 'vitest';
import { DraftLive, DraftStep, type StarField } from '@/api/schemas';
import { DWELL_MS, DraftPlayback, ENDED_MAX_MS, ERASE_MAX_MS, REWRITE_MAX_MS, TYPING_CPS } from '@/lib/liveDraft';

/** #617 카드 초안 펼침 모델 — 시계를 직접 읽지 않으므로 시각(ms)을 넣어 가며 시험한다 */
const T0 = 1_000_000;
const step = (extra: Record<string, unknown> = {}) => DraftStep.parse({ round: 0, actor: 'MODEL', kind: 'DRAFT', field: null, code: null, value: null, material: null, reason: null, ...extra });
const slot = (field: StarField, text: string, extra: { round?: number; done?: boolean } = {}) => ({ field, round: 0, text, done: true, ...extra });
const card = (id: string, state: string, slots: ReturnType<typeof slot>[] = [], steps: ReturnType<typeof step>[] = []) =>
  DraftLive.parse({ cardId: id, title: `카드 ${id}`, state, slots, steps });
/** 글자 수가 눈에 띄는 한글 N 자 */
const ko = (n: number, ch = '가') => ch.repeat(n);
const four = (n: number) => (['S', 'T', 'A', 'R'] as const).map((f) => slot(f, ko(n)));

const player = (drafts: DraftLive[], options: { now?: number; ended?: boolean; instant?: boolean } = {}) => {
  const pb = new DraftPlayback({ instant: options.instant });
  pb.receive(drafts, { ended: options.ended ?? false, now: options.now ?? T0 });
  return pb;
};
const slotOf = (pb: DraftPlayback, field: StarField) => pb.view().current!.slots.find((s) => s.field === field)!;
const typed = (pb: DraftPlayback, field: StarField) => Array.from(slotOf(pb, field).text).length;
const cursors = (pb: DraftPlayback) => pb.view().current!.slots.filter((s) => s.cursor).map((s) => s.field);

describe('#617 일정한 속도로 펼치기', () => {
  it('네 칸이 한꺼번에 와도 초당 40자로, 한 칸씩 차례로 쓴다', () => {
    const pb = player([card('a', 'WRITING', four(40))]);
    expect(typed(pb, 'S')).toBe(0);
    pb.advance(T0 + 500);
    expect([typed(pb, 'S'), typed(pb, 'T')]).toEqual([20, 0]);
    pb.advance(T0 + 1000);
    expect([typed(pb, 'S'), typed(pb, 'T')]).toEqual([40, 0]);
    pb.advance(T0 + 1500);
    expect([typed(pb, 'S'), typed(pb, 'T'), typed(pb, 'A')]).toEqual([40, 20, 0]);
    pb.advance(T0 + 4000);
    expect(['S', 'T', 'A', 'R'].map((f) => typed(pb, f as StarField))).toEqual([40, 40, 40, 40]);
  });

  it('틱을 잘게 나눠도 크게 나눠도 같은 속도다', () => {
    const fine = player([card('a', 'WRITING', four(100))]);
    for (let t = 50; t <= 3000; t += 50) fine.advance(T0 + t);
    const coarse = player([card('a', 'WRITING', four(100))]);
    coarse.advance(T0 + 3000);
    expect(typed(fine, 'S')).toBe(100);
    expect(typed(fine, 'T')).toBe((3000 / 1000) * TYPING_CPS - 100);
    expect(typed(coarse, 'T')).toBe(typed(fine, 'T'));
  });

  it('커서는 지금 쓰는 칸 하나에만 있고, 다 쓴 칸은 완료, 아직 안 쓴 칸은 대기다', () => {
    const pb = player([card('a', 'WRITING', four(40))]);
    pb.advance(T0 + 1500); // 상황 끝, 과제 20자
    expect(cursors(pb)).toEqual(['T']);
    expect(pb.view().current!.slots.map((s) => s.phase)).toEqual(['DONE', 'WRITING', 'WAITING', 'WAITING']);
    pb.advance(T0 + 5000);
    expect(cursors(pb)).toEqual([]);
    expect(pb.view().current!.slots.map((s) => s.phase)).toEqual(['DONE', 'DONE', 'DONE', 'DONE']);
  });

  it('읽는 동안(READING)에는 상황 칸에 커서만 있고 글은 없다', () => {
    const pb = player([card('a', 'READING')]);
    pb.advance(T0 + 4000);
    const view = pb.view().current!;
    expect(view.state).toBe('READING');
    expect(view.slots.map((s) => s.phase)).toEqual(['NEXT', 'WAITING', 'WAITING', 'WAITING']);
    expect(cursors(pb)).toEqual(['S']);
    expect(view.slots.every((s) => s.text === '')).toBe(true);
  });

  it('글이 이어서 오면 처음부터 다시 쓰지 않고 보이던 곳에서 이어 쓴다', () => {
    const pb = player([card('a', 'WRITING', [slot('S', ko(20), { done: false })])]);
    pb.advance(T0 + 400);
    expect(typed(pb, 'S')).toBe(16);
    pb.receive([card('a', 'WRITING', [slot('S', ko(40), { done: true }), slot('T', ko(10))])], { ended: false, now: T0 + 400 });
    expect(typed(pb, 'S')).toBe(16);
    pb.advance(T0 + 1000);
    expect(typed(pb, 'S')).toBe(40);
  });

  it('서버가 아직 끝내지 않은 칸은 보인 글 끝에서 커서만 깜빡이며 다음 글을 기다린다', () => {
    const pb = player([card('a', 'WRITING', [slot('S', ko(10), { done: false })])]);
    pb.advance(T0 + 5000);
    expect(typed(pb, 'S')).toBe(10);
    expect(cursors(pb)).toEqual(['S']);
    expect(slotOf(pb, 'S').phase).toBe('WRITING');
    expect(pb.view().current!.state).toBe('WRITING');
  });

  it('글이 비어 있고 끝난 칸은 시간을 쓰지 않고 비움이 된다', () => {
    const pb = player([card('a', 'DONE', [slot('S', ko(40)), slot('T', ''), slot('A', ko(40)), slot('R', ko(40))])]);
    pb.advance(T0 + 1000);
    expect(slotOf(pb, 'T').phase).toBe('BLANK');
    pb.advance(T0 + 1025);
    expect(typed(pb, 'A')).toBe(1);
    pb.advance(T0 + 3000);
    expect(pb.view().current!.slots.map((s) => s.phase)).toEqual(['DONE', 'BLANK', 'DONE', 'DONE']);
  });

  it('앞 칸을 다 쓰기 전에는 뒤 칸이 미리 비움이나 완료로 보이지 않는다', () => {
    const pb = player([card('a', 'DONE', [slot('S', ko(40)), slot('T', ''), slot('A', ko(40)), slot('R', ko(40))])]);
    pb.advance(T0 + 500);
    expect(pb.view().current!.slots.map((s) => s.phase)).toEqual(['WRITING', 'WAITING', 'WAITING', 'WAITING']);
    expect(pb.view().current!.slots[1].text).toBe('');
  });

  it('서버가 준 글보다 많이 보이지 않는다', () => {
    const pb = player([card('a', 'DONE', four(30))]);
    pb.advance(T0 + 600_000);
    for (const f of ['S', 'T', 'A', 'R'] as const) expect(slotOf(pb, f).text).toBe(ko(30));
  });

  it('이모지와 결합 문자는 반쪽으로 잘리지 않는다', () => {
    const family = '가👨‍👩‍👧나';
    const pb = player([card('a', 'DONE', [slot('S', family)])]);
    pb.advance(T0 + 25);
    expect(slotOf(pb, 'S').text).toBe('가');
    pb.advance(T0 + 50);
    expect(slotOf(pb, 'S').text).toBe('가👨‍👩‍👧');
  });
});

describe('#617 다시 쓰기', () => {
  it('round 가 오르면 지우고 새 글을 쓴다. 지우는 건 빠르다', () => {
    const pb = player([card('a', 'WRITING', [slot('S', ko(30))])]);
    pb.advance(T0 + 1000); // 앞 글 30자 다 보임
    expect(typed(pb, 'S')).toBe(30);
    pb.receive([card('a', 'WRITING', [slot('S', ko(30, '나'), { round: 1 })])], { ended: false, now: T0 + 1000 });
    pb.advance(T0 + 1100);
    expect(typed(pb, 'S')).toBe(14); // 100ms 에 16자 지움(초당 160자)
    expect(slotOf(pb, 'S').phase).toBe('REWRITING');
    expect(slotOf(pb, 'S').text).toBe(ko(14));
    pb.advance(T0 + 1200); // 30/160 = 187ms 에 다 지움
    expect(typed(pb, 'S')).toBeLessThanOrEqual(3);
    pb.advance(T0 + 1000 + 190 + 750);
    expect(slotOf(pb, 'S').text).toBe(ko(30, '나'));
  });

  it('앞 글과 앞부분이 같으면 달라진 곳까지만 지운다', () => {
    const pb = player([card('a', 'WRITING', [slot('S', `${ko(10)}${ko(10, '다')}`)])]);
    pb.advance(T0 + 1000);
    pb.receive([card('a', 'WRITING', [slot('S', `${ko(10)}${ko(10, '라')}`, { round: 1 })])], { ended: false, now: T0 + 1000 });
    pb.advance(T0 + 1000 + 62);
    expect(typed(pb, 'S')).toBeGreaterThanOrEqual(10);
    pb.advance(T0 + 1000 + 63 + 100);
    expect(slotOf(pb, 'S').text.startsWith(ko(10))).toBe(true);
    expect(slotOf(pb, 'S').text.length).toBeLessThanOrEqual(15);
  });

  it('지우는 데는 상한이 있다 — 아주 긴 글도 ERASE_MAX_MS 안에 다 지운다', () => {
    const pb = player([card('a', 'WRITING', [slot('S', ko(900))])]);
    pb.advance(T0 + 60_000);
    expect(typed(pb, 'S')).toBe(900);
    const at = T0 + 60_000;
    pb.receive([card('a', 'WRITING', [slot('S', ko(900, '나'), { round: 1 })])], { ended: false, now: at });
    pb.advance(at + ERASE_MAX_MS);
    expect(typed(pb, 'S')).toBe(0);
  });

  it('지우고 다시 쓰는 전체 시간에도 상한이 있다 — 아주 긴 새 글도 REWRITE_MAX_MS 에는 다 보인다', () => {
    const pb = player([card('a', 'WRITING', [slot('S', ko(300))])]);
    pb.advance(T0 + 60_000);
    const at = T0 + 60_000;
    pb.receive([card('a', 'WRITING', [slot('S', ko(500, '나'), { round: 1 })])], { ended: false, now: at });
    pb.advance(at + REWRITE_MAX_MS - 1);
    expect(typed(pb, 'S')).toBeLessThan(500);
    pb.advance(at + REWRITE_MAX_MS);
    expect(slotOf(pb, 'S').text).toBe(ko(500, '나'));
  });

  it('아직 보이지 않은 칸의 글이 다시 쓰이면 지울 것 없이 새 글을 쓴다', () => {
    const pb = player([card('a', 'WRITING', [slot('S', ko(40)), slot('R', ko(40))])]);
    pb.advance(T0 + 500);
    pb.receive([card('a', 'WRITING', [slot('S', ko(40)), slot('R', ko(10, '나'), { round: 1 })])], { ended: false, now: T0 + 500 });
    pb.advance(T0 + 5000);
    expect(slotOf(pb, 'R').text).toBe(ko(10, '나'));
  });

  it('round 는 그대로인데 앞 글을 이어 쓰지 않고 바뀐 글(저장될 초안으로 맞춤)도 새 글로 바꾼다', () => {
    const pb = player([card('a', 'WRITING', [slot('S', `${ko(10)}${ko(10, '다')}`, { done: false })])]);
    pb.advance(T0 + 1000);
    pb.receive([card('a', 'DONE', [slot('S', ko(10, '마'), { done: true })])], { ended: false, now: T0 + 1000 });
    pb.advance(T0 + 3000);
    expect(slotOf(pb, 'S').text).toBe(ko(10, '마'));
  });

  it('지난 round 의 글은 무시한다', () => {
    const pb = player([card('a', 'WRITING', [slot('S', ko(10, '나'), { round: 1 })])]);
    pb.advance(T0 + 1000);
    pb.receive([card('a', 'WRITING', [slot('S', ko(10), { round: 0 })])], { ended: false, now: T0 + 1000 });
    pb.advance(T0 + 2000);
    expect(slotOf(pb, 'S').text).toBe(ko(10, '나'));
  });

  it('칸에 한 과정은 칸을 다 쓴 뒤에만 보인다', () => {
    const steps = [step({ kind: 'FLAG', field: 'R', actor: 'CODE', code: 'UNSOURCED_NUMBER', value: '50%' }), step({ round: 1, kind: 'REWRITE', field: 'R' })];
    const pb = player([card('a', 'WRITING', [slot('R', ko(40), { round: 1 })], steps)]);
    pb.advance(T0 + 400);
    expect(slotOf(pb, 'R').steps).toEqual([]);
    pb.advance(T0 + 1000);
    expect(slotOf(pb, 'R').steps.map((s) => s.kind)).toEqual(['FLAG', 'REWRITE']);
  });
});

describe('#617 카드 넘기기', () => {
  const two = (a: string, b: string, aSlots = four(40), bSlots: ReturnType<typeof slot>[] = []) => [card('a', a, aSlots), card('b', b, bSlots)];
  /** 첫 카드가 쓰이는 것을 처음부터 보고 있다가 서버가 첫 카드를 끝내고 둘째 카드를 읽기 시작한 상황 */
  const watching = () => {
    const pb = player(two('WRITING', 'WAITING'));
    pb.receive(two('DONE', 'READING'), { ended: false, now: T0 });
    return pb;
  };

  it('뒤 카드가 글을 쓰기 시작하면 앞 카드의 남은 글은 바로 마친다', () => {
    const pb = watching();
    pb.advance(T0 + 500);
    expect(pb.view().current!.cardId).toBe('a');
    expect(typed(pb, 'S')).toBe(20);
    pb.receive(two('DONE', 'WRITING', four(40), [slot('S', ko(40), { done: false })]), { ended: false, now: T0 + 600 });
    const view = pb.view();
    expect(view.current!.cardId).toBe('b');
    expect(view.finished.map((c) => c.cardId)).toEqual(['a']);
    expect(view.finished[0].slots.map((s) => s.text.length)).toEqual([40, 40, 40, 40]);
    expect(view.finished[0].slots.every((s) => s.phase === 'DONE')).toBe(true);
  });

  it('뒤 카드가 읽는 중이면 앞 카드를 끝까지 펼치고 DWELL_MS 쉬었다가 넘어간다', () => {
    const pb = watching();
    pb.advance(T0 + 4000); // 160자 = 4초에 다 보임
    expect(pb.view().current!.cardId).toBe('a');
    pb.advance(T0 + 4000 + DWELL_MS - 100);
    expect(pb.view().current!.cardId).toBe('a');
    pb.advance(T0 + 4000 + DWELL_MS + 100);
    const view = pb.view();
    expect(view.current!.cardId).toBe('b');
    expect(view.current!.state).toBe('READING');
    expect(cursors(pb)).toEqual(['S']);
    expect(view.finished.map((c) => c.cardId)).toEqual(['a']);
  });

  it('앞 카드를 다 쓰기 전에는 뒤 카드가 읽는 중이어도 넘어가지 않는다', () => {
    const pb = watching();
    pb.advance(T0 + 3900);
    expect(pb.view().current!.cardId).toBe('a');
    expect(typed(pb, 'R')).toBeLessThan(40);
  });

  it('폴링이 밀려 한 번에 여러 카드가 와도 건너뛰며 마지막 카드부터 쓴다', () => {
    const pb = player([card('a', 'READING'), card('b', 'WAITING'), card('c', 'WAITING')]);
    pb.advance(T0 + 1000);
    pb.receive([card('a', 'DONE', four(40)), card('b', 'DONE', four(40)), card('c', 'WRITING', [slot('S', ko(40), { done: false })])], { ended: false, now: T0 + 2000 });
    const view = pb.view();
    expect(view.current!.cardId).toBe('c');
    expect(view.finished.map((c) => c.cardId)).toEqual(['a', 'b']);
  });

  it('중간에 들어와도(첫 snapshot 에 끝난 카드가 있어도) 끝나지 않은 첫 카드부터 보이고 앞 카드는 끝난 목록에 있다', () => {
    const pb = player([card('a', 'DONE', four(20)), card('b', 'DONE', four(20)), card('c', 'READING')]);
    const view = pb.view();
    expect(view.current!.cardId).toBe('c');
    expect(view.finished.map((c) => c.cardId)).toEqual(['a', 'b']);
    expect(view.total).toBe(3);
  });

  it('실패한 카드가 중간에 있어도 넘어가고, 쓰다 만 글은 보이되 쓰지 못함으로 표시한다', () => {
    const failed = card('b', 'FAILED', [slot('S', ko(20)), slot('T', ko(10), { done: false })]);
    const pb = player([card('a', 'DONE', four(20)), failed, card('c', 'READING')]);
    expect(pb.view().current!.cardId).toBe('c'); // 처음 본 snapshot 에서 끝난 카드는 목록으로
    const fresh = player([card('a', 'READING'), card('b', 'WAITING'), card('c', 'WAITING')]);
    fresh.advance(T0 + 100);
    fresh.receive([card('a', 'DONE', four(20)), failed, card('c', 'READING')], { ended: false, now: T0 + 200 });
    expect(fresh.view().current!.cardId).toBe('b');
    fresh.advance(T0 + 200 + 1000);
    const shown = fresh.view().current!;
    expect(shown.state).toBe('FAILED');
    expect(shown.slots.map((s) => s.phase)).toEqual(['DONE', 'MISSED', 'MISSED', 'MISSED']);
    expect(shown.slots[1].text).toBe(ko(10));
    expect(cursors(fresh)).toEqual([]);
  });
});

describe('#617 Job 이 끝난 뒤', () => {
  it('마지막 카드를 다 펼치고 쉬기 전까지 holding 이다', () => {
    const pb = player([card('a', 'WRITING', four(40))]);
    pb.advance(T0 + 400);
    pb.receive([card('a', 'DONE', four(40))], { ended: true, now: T0 + 500 });
    expect(pb.holding).toBe(true);
    pb.advance(T0 + 4000); // 160자 4초 중 0.5초 넘게 지남
    expect(pb.holding).toBe(true);
    pb.advance(T0 + 500 + 3600); // 다 보임
    expect(['S', 'T', 'A', 'R'].map((f) => typed(pb, f as StarField))).toEqual([40, 40, 40, 40]);
    expect(pb.holding).toBe(true); // 쉬는 중
    pb.advance(T0 + 500 + 3600 + DWELL_MS + 50);
    expect(pb.holding).toBe(false);
  });

  it('끝난 지 ENDED_MAX_MS 가 지나면 남은 글을 바로 마친다', () => {
    const pb = player([card('a', 'DONE', [slot('S', ko(3000)), slot('T', ko(3000)), slot('A', ko(3000)), slot('R', ko(3000))])], { ended: true });
    pb.advance(T0 + ENDED_MAX_MS - 1);
    expect(pb.holding).toBe(true);
    expect(typed(pb, 'T')).toBe(0);
    pb.advance(T0 + ENDED_MAX_MS);
    expect(typed(pb, 'R')).toBe(3000);
    pb.advance(T0 + ENDED_MAX_MS + DWELL_MS);
    expect(pb.holding).toBe(false);
  });

  it('글이 없는 카드(전부 비움)는 기다릴 것 없이 쉬기만 한다', () => {
    const pb = player([card('a', 'DONE', (['S', 'T', 'A', 'R'] as const).map((f) => slot(f, '')))], { ended: true });
    expect(pb.holding).toBe(true);
    pb.advance(T0 + DWELL_MS);
    expect(pb.holding).toBe(false);
    expect(pb.view().current!.slots.map((s) => s.phase)).toEqual(['BLANK', 'BLANK', 'BLANK', 'BLANK']);
  });

  it('끝났는데 서버가 칸을 끝내지 않은 채 두었어도(마지막 snapshot 이 WRITING) 펼치고 끝낸다', () => {
    const pb = player([card('a', 'WRITING', [slot('S', ko(20), { done: false })])], { ended: true });
    pb.advance(T0 + 500);
    expect(typed(pb, 'S')).toBe(20);
    expect(cursors(pb)).toEqual([]);
    pb.advance(T0 + 500 + DWELL_MS);
    expect(pb.holding).toBe(false);
  });

  it('쓰인 적 없는 뒤 카드가 남아도(중간에 끝남) 거기로 넘어가지 않고 마친다', () => {
    const pb = player([card('a', 'WRITING', four(10)), card('b', 'WAITING')]);
    pb.receive([card('a', 'DONE', four(10)), card('b', 'WAITING')], { ended: true, now: T0 });
    pb.advance(T0 + 400);
    pb.advance(T0 + 1000 + DWELL_MS - 10); // 40자는 1초에 다 보인다
    expect(pb.holding).toBe(true);
    pb.advance(T0 + 1000 + DWELL_MS + 10);
    expect(pb.view().current!.cardId).toBe('a');
    expect(pb.holding).toBe(false);
  });

  it('카드가 없으면 붙잡을 것도 없다', () => {
    const pb = player([], { ended: true });
    expect(pb.holding).toBe(false);
    expect(pb.hasView).toBe(false);
    expect(pb.view()).toEqual({ total: 0, current: null, finished: [] });
  });
});

describe('#617 폴링이 깨져도', () => {
  it('drafts 가 갑자기 [] 가 되어도 보이던 것을 유지하고 펼치기를 마친다', () => {
    const pb = player([card('a', 'WRITING', four(40))]);
    pb.advance(T0 + 1000);
    pb.receive([], { ended: false, now: T0 + 1000 });
    expect(pb.view().current!.cardId).toBe('a');
    pb.advance(T0 + 4000);
    expect(typed(pb, 'R')).toBe(40);
  });

  it('Job 이 끝났는데 drafts 가 [] 여도 받은 글까지 펼치고 끝낸다', () => {
    const pb = player([card('a', 'WRITING', four(40))]);
    pb.advance(T0 + 500);
    pb.receive([], { ended: true, now: T0 + 500 });
    expect(pb.holding).toBe(true);
    pb.advance(T0 + 500 + 4000);
    expect(typed(pb, 'R')).toBe(40);
    pb.advance(T0 + 500 + 4000 + DWELL_MS);
    expect(pb.holding).toBe(false);
  });

  it('카드가 줄어든 응답은 믿지 않고 이미 받은 것을 둔다', () => {
    const pb = player([card('a', 'DONE', four(10)), card('b', 'READING')]);
    pb.receive([card('b', 'WRITING', [slot('S', 'x')])], { ended: false, now: T0 + 1000 });
    expect(pb.view().total).toBe(2);
    expect(pb.view().current!.cardId).toBe('b');
    expect(pb.view().current!.state).toBe('READING');
  });

  it('카드 상태가 거꾸로 가거나 칸이 사라진 응답이 와도 이미 받은 글을 지우지 않는다', () => {
    const pb = player([card('a', 'DONE', four(10))]);
    pb.advance(T0 + 2000);
    pb.receive([card('a', 'READING')], { ended: false, now: T0 + 2000 });
    const view = pb.view().current!;
    expect(view.state).toBe('DONE');
    expect(view.slots.map((s) => s.text.length)).toEqual([10, 10, 10, 10]);
  });

  it('같은 칸이 두 번 오면 뒤의 것을 쓴다', () => {
    const pb = player([card('a', 'DONE', [slot('S', ko(5)), slot('S', ko(8, '나'))])]);
    pb.advance(T0 + 1000);
    expect(slotOf(pb, 'S').text).toBe(ko(8, '나'));
  });

  it('같은 snapshot 을 여러 번 받아도 펼치기가 다시 시작되지 않는다', () => {
    const pb = player([card('a', 'WRITING', four(40))]);
    pb.advance(T0 + 500);
    for (let i = 0; i < 3; i += 1) pb.receive([card('a', 'WRITING', four(40))], { ended: false, now: T0 + 500 });
    expect(typed(pb, 'S')).toBe(20);
  });
});

describe('#617 한 번에 보기와 동작 줄이기', () => {
  it('instant 면 글이 오는 즉시 다 보이고 카드도 바로 넘어가며 쉬지 않는다', () => {
    const pb = player([card('a', 'DONE', four(40)), card('b', 'READING')], { instant: true });
    const view = pb.view();
    expect(view.current!.cardId).toBe('b');
    expect(view.finished[0].slots.every((s) => s.text.length === 40)).toBe(true);
    pb.receive([card('a', 'DONE', four(40)), card('b', 'DONE', four(30))], { ended: true, now: T0 + 10 });
    expect(slotOf(pb, 'R').text).toBe(ko(30));
    expect(pb.holding).toBe(false);
  });

  it('펼치는 도중에 instant 로 바꾸면 남은 글이 바로 보인다', () => {
    const pb = player([card('a', 'DONE', four(40))]);
    pb.advance(T0 + 500);
    expect(typed(pb, 'R')).toBe(0);
    pb.setInstant(true, T0 + 500);
    expect(typed(pb, 'R')).toBe(40);
    expect(cursors(pb)).toEqual([]);
  });
});

describe('#617 서버 글은 글자 그대로만 다룬다', () => {
  it('HTML 처럼 보이는 글도 그대로 두고 자르지 않는다', () => {
    const text = '<img src=x onerror=alert(1)> **굵게** \n둘째 줄';
    const pb = player([card('a', 'DONE', [slot('S', text)])]);
    pb.advance(T0 + 10_000);
    expect(slotOf(pb, 'S').text).toBe(text);
    expect(slotOf(pb, 'S').full).toBe(text);
  });
});

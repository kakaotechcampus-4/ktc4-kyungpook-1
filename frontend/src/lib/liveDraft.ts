/**
 * 카드 초안을 쓰는 동안의 화면 모델 (#617).
 *
 * 서버는 1초마다 Job 의 `drafts` 로 카드별 "지금까지 쓴 전체 글"을 준다. AI 는 카드마다 8초쯤 생각한 뒤
 * 네 칸을 2초 안에 몰아 쓰므로 글은 한꺼번에 도착한다. 이 모델은 도착한 글을 초당 40자 일정한 속도로 펼치는 큐다.
 *
 * 규칙
 *  - 시계를 읽지 않는다. 시각(now, ms)은 늘 바깥에서 넣는다 → 가짜 시계로 그대로 시험할 수 있다.
 *  - 화면에 보이는 글은 서버가 준 글의 앞부분뿐이다. 글자를 만들어 내지 않는다.
 *  - 한 카드를 한 번에 한 칸씩(상황, 과제, 행동, 결과 순) 쓴다. 커서는 지금 쓰는 칸 하나에만 있다.
 *  - 지금 보이는 카드 뒤의 카드가 쓰기 시작하면(WRITING 이상) 보이는 카드의 남은 글은 바로 마치고 넘어간다.
 *    뒤 카드가 아직 읽는 중(READING)이면 보이는 카드를 끝까지 펼친 뒤 DWELL_MS 쉬고 넘어간다.
 *  - 칸의 round 가 오르거나 글이 앞부분을 이어 쓰지 않고 바뀌면 달라진 곳까지 지우고 새 글을 쓴다.
 *    지우는 데 ERASE_MAX_MS, 지우고 다시 쓰는 데 REWRITE_MAX_MS 를 넘기지 않는다.
 *  - Job 이 끝나면 마지막 카드까지 펼친 뒤에야 holding 이 풀린다. 끝나고 ENDED_MAX_MS 가 지나면 남은 글을 바로 마친다.
 *  - 폴링이 깨져 drafts 가 비거나 줄거나 되돌아가도 이미 받은 것을 유지한다.
 */
import type { DraftLive, DraftLiveSlot, DraftLiveState, DraftStep, StarField } from '@/api/schemas';

export const TYPING_CPS = 40;
export const ERASE_CPS = 160;
export const ERASE_MAX_MS = 600;
export const REWRITE_MAX_MS = 3_000;
export const ENDED_MAX_MS = 15_000;
/** 카드 하나를 다 쓴 뒤 다음 카드로 넘어가기(또는 완료 화면으로 가기) 전에 쉬는 시간 */
export const DWELL_MS = 1_500;

export const LIVE_FIELDS: readonly StarField[] = ['S', 'T', 'A', 'R'];

const segmenter = typeof Intl !== 'undefined' && typeof Intl.Segmenter === 'function' ? new Intl.Segmenter('ko', { granularity: 'grapheme' }) : null;
/** 사람이 한 글자로 읽는 단위로 자른다(이모지·결합 문자가 반쪽으로 보이지 않게) */
const graphemes = (text: string): string[] => segmenter ? Array.from(segmenter.segment(text), (part) => part.segment) : Array.from(text);

const RANK: Record<DraftLiveState, number> = { WAITING: 0, READING: 1, WRITING: 2, DONE: 3, FAILED: 3 };
const isOver = (card: DraftLive) => card.state === 'DONE' || card.state === 'FAILED';
/** 글이 오기 시작했거나 끝난 카드. 읽는 중과 기다리는 중은 아직이다 */
const hasStarted = (card: DraftLive) => RANK[card.state] >= 2;

/**
 * 칸의 상태.
 *  WAITING 아직 차례가 아님 · NEXT 쓸 차례(커서만, 글 없음) · WRITING 쓰는 중 · REWRITING 다시 쓴 글을 쓰는 중
 *  DONE 다 씀 · BLANK AI 가 근거가 없어 비움 · EMPTY 서버가 이 칸을 주지 않고 끝남 · MISSED 카드가 실패해 끝내지 못함
 */
export type SlotPhase = 'WAITING' | 'NEXT' | 'WRITING' | 'REWRITING' | 'DONE' | 'BLANK' | 'EMPTY' | 'MISSED';
export type LiveSlot = {
  field: StarField;
  phase: SlotPhase;
  /** 지금 화면에 보이는 글 */
  text: string;
  /** 서버가 준 전체 글. 칸 높이를 미리 잡는 데만 쓰고 보이지 않는다 */
  full: string;
  /** 글 끝 커서. 한 카드에 한 칸뿐이다 */
  cursor: boolean;
  /** 이 칸에 한 AI 의 과정. 칸을 다 쓴 뒤에만 채워진다 */
  steps: DraftStep[];
};
export type LiveCardState = DraftLiveState;
export type LiveCard = { cardId: string; title: string; index: number; state: LiveCardState; slots: LiveSlot[] };
export type LiveView = {
  total: number;
  /** 지금 펼치는 카드. 서버가 아직 아무 카드도 주지 않았으면 null */
  current: LiveCard | null;
  /** 앞서 끝난 카드, 서버 순서대로. 글은 이미 다 보인다 */
  finished: LiveCard[];
};

type SlotRun = {
  present: boolean;
  chars: string[];
  round: number;
  done: boolean;
  /** 화면에 보이는 글자 수. 소수로 두어 짧은 틱도 쌓인다 */
  pos: number;
  /** 다시 쓸 때 이 길이까지 지운다 */
  eraseTo: number | null;
  /** 지우는 동안 화면에 있는 앞 글 */
  erasing: string[] | null;
  eraseDeadline: number | null;
  rewriteDeadline: number | null;
  /**
   * 이 칸을 다 펼쳐 끝난 모습으로 보인 적이 있다. 앞 칸을 다시 쓰는 동안에도 이 칸은 대기로 돌아가지 않고 보이던 글 그대로 둔다.
   * 이 칸도 다시 쓰이면 차례가 왔을 때 보이던 글에서 지우고 새로 쓴다
   */
  finished: boolean;
};
type Run = { cardId: string; slots: Record<StarField, SlotRun>; completedAt: number | null };

const emptySlot = (): SlotRun => ({ present: false, chars: [], round: 0, done: false, pos: 0, eraseTo: null, erasing: null, eraseDeadline: null, rewriteDeadline: null, finished: false });
/** 화면에 보이는 글자들. 지우는 동안은 지워지는 앞 글, 아니면 서버가 준 글 */
const onScreen = (slot: SlotRun) => slot.eraseTo !== null && slot.erasing ? slot.erasing : slot.chars;
const stopErasing = (slot: SlotRun) => { slot.eraseTo = null; slot.erasing = null; slot.eraseDeadline = null; };
const slotMap = (card: DraftLive): Partial<Record<StarField, DraftLiveSlot>> => {
  const map: Partial<Record<StarField, DraftLiveSlot>> = {};
  for (const slot of card.slots) map[slot.field] = slot; // 같은 칸이 두 번 오면 뒤의 것
  return map;
};
const commonPrefix = (a: readonly string[], b: readonly string[]) => {
  let n = 0;
  while (n < a.length && n < b.length && a[n] === b[n]) n += 1;
  return n;
};

/** 받은 카드를 이미 가진 카드와 합친다 — 서버가 되돌아가도(상태가 거꾸로, round 가 작게) 이미 받은 것을 지우지 않는다 */
function mergeCard(old: DraftLive | undefined, next: DraftLive): DraftLive {
  if (!old) return next;
  const oldSlots = slotMap(old);
  const nextSlots = slotMap(next);
  const slots = LIVE_FIELDS.flatMap((field) => {
    const before = oldSlots[field];
    const after = nextSlots[field];
    if (!after) return before ? [before] : [];
    return before && before.round > after.round ? [before] : [after];
  });
  return {
    ...next,
    state: RANK[next.state] < RANK[old.state] ? old.state : next.state,
    slots,
    steps: next.steps.length >= old.steps.length ? next.steps : old.steps,
  };
}

export class DraftPlayback {
  private cards: DraftLive[] = [];
  private ended = false;
  private endedAt: number | null = null;
  private run: Run | null = null;
  private instant: boolean;
  private at: number | null = null;
  /** 바뀔 때마다 오른다 — 다시 그릴지 가르는 값 */
  version = 0;

  constructor(options: { instant?: boolean } = {}) {
    this.instant = !!options.instant;
  }

  /** 글을 펼치지 않고 한 번에 보인다(동작 줄이기 설정, 한 번에 보기). 이미 보인 글은 바로 마친다 */
  setInstant(instant: boolean, now: number) {
    if (this.instant === instant) return;
    this.instant = instant;
    this.version += 1;
    if (instant && this.run) this.flush(this.run);
    this.tick(now);
    this.settle(now);
  }

  /** 서버가 새로 준 drafts 를 받는다. ended 는 Job 이 끝났다는 뜻(더는 새 글이 오지 않는다) */
  receive(drafts: readonly DraftLive[], options: { ended: boolean; now: number }) {
    const { ended, now } = options;
    this.tick(now);
    this.merge(drafts);
    if (ended && !this.ended) {
      this.ended = true;
      this.endedAt = now;
      this.version += 1;
    }
    if (this.run) this.sync(this.run, now);
    this.settle(now);
  }

  /** 시간을 now 까지 흘린다. 바뀐 게 있으면 true */
  advance(now: number): boolean {
    const before = this.version;
    const held = this.holding;
    this.tick(now);
    this.settle(now);
    if (this.holding !== held) this.version += 1; // 쉬는 시간이 끝난 것도 화면이 알아야 한다
    return this.version !== before;
  }

  /** Job 이 끝난 뒤 마지막 카드를 다 펼치고 쉬기까지 화면을 붙잡아 둘지 */
  get holding(): boolean {
    if (!this.ended || !this.run) return false;
    if (this.instant) return false;
    const run = this.run;
    return !(this.complete(run) && this.at !== null && run.completedAt !== null && this.at - run.completedAt >= DWELL_MS);
  }

  /** 보일 카드가 하나라도 있었는지 */
  get hasView(): boolean {
    return this.run !== null;
  }

  view(): LiveView {
    const index = this.shownIndex();
    const run = this.run;
    if (index < 0 || !run) return { total: this.cards.length, current: null, finished: [] };
    const card = this.cards[index];
    const done = this.complete(run);
    const active = this.activeField(run, card);
    const reached = card.state === 'WAITING' ? -1 : active ? LIVE_FIELDS.indexOf(active) : LIVE_FIELDS.length; // 차례가 온 칸까지만 모습을 바꾼다. 뒤 칸은 앞 칸이 끝나기 전에 미리 비움이나 실패로 보이지 않는다
    const slots = LIVE_FIELDS.map((field, at): LiveSlot => {
      const slot = run.slots[field];
      const full = slot.chars.join('');
      // 앞 칸을 다시 쓰느라 차례가 앞으로 돌아가도, 이미 끝난 모습으로 보인 뒤 칸은 대기로 되돌리지 않는다
      const shownBefore = at > reached && reached >= 0 && slot.finished;
      if ((at > reached || reached < 0) && !shownBefore) return { field, phase: 'WAITING', text: '', full, cursor: false, steps: [] };
      const text = onScreen(slot).slice(0, Math.floor(slot.pos)).join('');
      if (at < reached || shownBefore) {
        const phase = this.settledPhase(card, slot);
        return { field, phase, text, full, cursor: false, steps: phase === 'DONE' || phase === 'BLANK' ? card.steps.filter((step) => step.field === field) : [] };
      }
      const phase: SlotPhase = !slot.present || slot.chars.length === 0 ? 'NEXT' : slot.round > 0 ? 'REWRITING' : 'WRITING';
      return { field, phase, text, full, cursor: !done, steps: [] };
    });
    return {
      total: this.cards.length,
      current: { cardId: card.cardId, title: card.title, index, state: this.shownState(card, done), slots },
      finished: this.cards.slice(0, index).map((earlier, at) => staticCard(earlier, at)),
    };
  }

  // ───────────── 받은 글 합치기 ─────────────

  private merge(drafts: readonly DraftLive[]) {
    if (!drafts.length) return; // 진행을 잃은 응답 — 이미 가진 것을 둔다
    if (this.cards.length) {
      const incoming = new Set(drafts.map((card) => card.cardId));
      if (this.cards.some((card) => !incoming.has(card.cardId))) return; // 카드가 줄어든 응답은 믿지 않는다. 카드 목록은 Job 안에서 바뀌지 않는다
    }
    const known = new Map(this.cards.map((card) => [card.cardId, card]));
    this.cards = drafts.map((card) => mergeCard(known.get(card.cardId), card));
    this.version += 1;
  }

  /** 보이는 카드의 칸 글을 새 snapshot 에 맞춘다 */
  private sync(run: Run, now: number) {
    const card = this.cards.find((c) => c.cardId === run.cardId);
    if (!card) return;
    const incoming = slotMap(card);
    for (const field of LIVE_FIELDS) {
      const next = incoming[field];
      const slot = run.slots[field];
      if (!next) continue;
      const chars = graphemes(next.text);
      if (!slot.present) {
        Object.assign(slot, { present: true, chars, round: next.round, done: next.done, pos: 0 });
        this.version += 1;
        continue;
      }
      if (next.round < slot.round) continue; // 지난 round 의 글은 무시
      const extends_ = next.round === slot.round && commonPrefix(slot.chars, chars) === slot.chars.length;
      if (extends_) {
        if (chars.length !== slot.chars.length || (next.done && !slot.done)) {
          slot.chars = chars;
          slot.done = slot.done || next.done;
          this.version += 1;
        }
        continue;
      }
      // 다시 쓴 글(round 가 오름) 이거나, 앞 글을 이어 쓰지 않고 바뀐 글 — 달라진 곳까지 지우고 새로 쓴다
      const shown = Math.floor(slot.pos);
      const visible = onScreen(slot).slice(0, shown);
      const keep = commonPrefix(visible, chars);
      slot.chars = chars;
      slot.round = next.round;
      slot.done = next.done;
      if (shown > keep) {
        slot.erasing = visible;
        slot.eraseTo = keep;
        slot.eraseDeadline = now + ERASE_MAX_MS;
        slot.rewriteDeadline = now + REWRITE_MAX_MS;
      } else {
        stopErasing(slot);
        slot.pos = Math.min(slot.pos, keep);
      }
      run.completedAt = null;
      this.version += 1;
    }
    if (this.instant) this.flush(run);
    this.markFinished(run);
  }

  // ───────────── 시간 흘리기 ─────────────

  private tick(now: number) {
    if (this.at === null) { this.at = now; return; }
    if (now <= this.at) return;
    const elapsed = now - this.at;
    this.at = now;
    const run = this.run;
    if (!run) return;
    if (this.instant) { this.flush(run); return; }
    const card = this.cards.find((c) => c.cardId === run.cardId);
    if (!card) return;
    let ms = elapsed;
    for (let guard = 0; guard < 16 && ms > 0; guard += 1) {
      const field = this.activeField(run, card);
      if (!field) break;
      const slot = run.slots[field];
      if (!slot.present) break; // 서버가 이 칸을 아직 주지 않았다. 새 글을 기다린다
      if (slot.eraseTo !== null) {
        const rate = ERASE_CPS / 1000;
        const need = (slot.pos - slot.eraseTo) / rate;
        if (need <= ms) { slot.pos = slot.eraseTo; stopErasing(slot); ms -= need; } else { slot.pos -= ms * rate; ms = 0; }
        this.version += 1;
        continue;
      }
      if (slot.pos < slot.chars.length) {
        const rate = TYPING_CPS / 1000;
        const need = (slot.chars.length - slot.pos) / rate;
        if (need <= ms) { slot.pos = slot.chars.length; ms -= need; } else { slot.pos += ms * rate; ms = 0; }
        this.version += 1;
        continue;
      }
      break; // 다 보였지만 서버가 아직 이 칸을 끝내지 않았다. 새 글을 기다린다
    }
    for (const slot of Object.values(run.slots)) {
      if (slot.eraseTo !== null && slot.eraseDeadline !== null && now >= slot.eraseDeadline) { slot.pos = slot.eraseTo; stopErasing(slot); this.version += 1; }
      if (slot.rewriteDeadline !== null && now >= slot.rewriteDeadline) {
        stopErasing(slot);
        slot.pos = slot.chars.length;
        slot.rewriteDeadline = null;
        this.version += 1;
      }
    }
    if (this.ended && this.endedAt !== null && now >= this.endedAt + ENDED_MAX_MS) this.flush(run);
    this.markFinished(run);
    // 틱이 굵어도 쉬는 시간은 글이 다 보인 때부터 센다
    if (run.completedAt === null && this.complete(run)) { run.completedAt = now - Math.max(0, ms); this.version += 1; }
  }

  private flush(run: Run) {
    for (const slot of Object.values(run.slots)) {
      if (slot.present && (slot.pos !== slot.chars.length || slot.eraseTo !== null)) this.version += 1;
      slot.pos = slot.chars.length;
      stopErasing(slot);
      slot.rewriteDeadline = null;
    }
    this.markFinished(run);
  }

  /** 지금 쓸 칸 앞의 칸은 끝난 모습으로 보였다고 적어 둔다 */
  private markFinished(run: Run) {
    const card = this.cards.find((c) => c.cardId === run.cardId);
    if (!card) return;
    const active = this.activeField(run, card);
    const end = active ? LIVE_FIELDS.indexOf(active) : LIVE_FIELDS.length;
    for (const field of LIVE_FIELDS.slice(0, end)) run.slots[field].finished = true;
  }

  // ───────────── 카드 넘기기 ─────────────

  private settle(now: number) {
    if (!this.cards.length) return;
    if (!this.run) this.start(this.firstIndex(), now);
    for (let guard = 0; guard <= this.cards.length; guard += 1) {
      const run = this.run;
      if (!run) return;
      const index = this.shownIndex();
      if (index < 0) return;
      const card = this.cards[index];
      const done = this.complete(run);
      if (done && run.completedAt === null) { run.completedAt = now; this.version += 1; }
      else if (!done && run.completedAt !== null) run.completedAt = null; // 글이 더 와서 다시 쓰는 중
      const next = this.cards[index + 1];
      if (!next) return;
      // 뒤 카드가 글을 쓰기 시작했다 — 앞 카드의 남은 글은 바로 마치고 넘어간다
      if (this.cards.slice(index + 1).some(hasStarted)) {
        this.flush(run);
        this.start(index + 1, now);
        continue;
      }
      // 뒤 카드가 아직 읽는 중이면 보이는 카드를 끝까지 펼친 뒤 잠깐 두고 넘어간다. Job 이 끝났는데 뒤 카드가 쓰인 적 없으면 넘어갈 곳이 없다
      const waited = this.instant || (run.completedAt !== null && now - run.completedAt >= DWELL_MS);
      if (done && isOver(card) && waited && !(this.ended && !hasStarted(next))) {
        this.start(index + 1, now);
        continue;
      }
      return;
    }
  }

  private firstIndex(): number {
    const open = this.cards.findIndex((card) => !isOver(card));
    return open < 0 ? this.cards.length - 1 : open;
  }

  private start(index: number, now: number) {
    const card = this.cards[index];
    const slots = { S: emptySlot(), T: emptySlot(), A: emptySlot(), R: emptySlot() };
    this.run = { cardId: card.cardId, slots, completedAt: null };
    this.sync(this.run, now);
    this.version += 1;
  }

  private shownIndex(): number {
    return this.run ? this.cards.findIndex((card) => card.cardId === this.run!.cardId) : -1;
  }

  // ───────────── 칸과 카드의 끝 ─────────────

  private cardOver(card: DraftLive) {
    return isOver(card) || this.ended;
  }

  /** 서버가 이 칸 뒤로 넘어갔는가. 다시 쓰는 칸(round 가 오름)은 뒤 칸에 첫 바퀴 글이 있어도 넘어간 것이 아니다 */
  private laterPresent(run: Run, field: StarField) {
    const round = run.slots[field].round;
    return LIVE_FIELDS.slice(LIVE_FIELDS.indexOf(field) + 1).some((later) => run.slots[later].present && run.slots[later].round >= round);
  }

  /** 글을 다 보였고 서버도 이 칸을 끝냈는가. 서버가 다음 칸으로 넘어갔거나 카드가 끝났으면 끝난 것으로 본다 */
  private slotSettled(run: Run, card: DraftLive, field: StarField): boolean {
    const slot = run.slots[field];
    const closed = this.cardOver(card) || this.laterPresent(run, field);
    if (!slot.present) return closed;
    return (slot.done || closed) && slot.pos >= slot.chars.length && slot.eraseTo === null;
  }

  private settledPhase(card: DraftLive, slot: SlotRun): SlotPhase {
    if (!slot.present) return card.state === 'FAILED' ? 'MISSED' : 'EMPTY';
    if (!slot.done && card.state === 'FAILED') return 'MISSED';
    return slot.chars.length ? 'DONE' : slot.done ? 'BLANK' : 'DONE';
  }

  /** 지금 쓸 칸 = 끝나지 않은 첫 칸 */
  private activeField(run: Run, card: DraftLive): StarField | null {
    return LIVE_FIELDS.find((field) => !this.slotSettled(run, card, field)) ?? null;
  }

  private complete(run: Run): boolean {
    const card = this.cards.find((c) => c.cardId === run.cardId);
    return !!card && this.activeField(run, card) === null;
  }

  private shownState(card: DraftLive, done: boolean): LiveCardState {
    if (!done) return card.state === 'READING' || card.state === 'WAITING' ? card.state : 'WRITING';
    if (card.state === 'FAILED') return 'FAILED';
    if (card.state === 'READING' || card.state === 'WAITING') return card.state;
    // 글이 다 보여도 서버가 검사·고쳐 쓰기 중(WRITING)이면 아직 다 쓴 것이 아니다
    return isOver(card) || this.ended ? 'DONE' : 'WRITING';
  }
}

/** 이미 끝난 카드는 글이 전부 보이는 모양으로 그린다 */
function staticCard(card: DraftLive, index: number): LiveCard {
  const incoming = slotMap(card);
  const slots = LIVE_FIELDS.map((field): LiveSlot => {
    const slot = incoming[field];
    const full = slot?.text ?? '';
    let phase: SlotPhase;
    if (!slot) phase = card.state === 'FAILED' ? 'MISSED' : 'EMPTY';
    else if (!slot.done && card.state === 'FAILED') phase = 'MISSED';
    else phase = full ? 'DONE' : slot.done ? 'BLANK' : 'DONE';
    const revealed = phase === 'DONE' || phase === 'BLANK';
    return { field, phase, text: full, full, cursor: false, steps: revealed ? card.steps.filter((step) => step.field === field) : [] };
  });
  return { cardId: card.cardId, title: card.title, index, state: card.state, slots };
}

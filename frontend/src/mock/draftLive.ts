/**
 * 카드 초안 Job 의 `drafts`(#617, BE #622) 데모 값. 실서버가 줄 수 있는 모양 그대로 시간에 따라 내려 준다.
 *
 * 서버는 카드를 한 장씩 차례로 쓴다. 카드마다 8초쯤 코드를 읽고 생각한 뒤(READING) 네 칸을 2초 안에 몰아 쓴다(WRITING).
 * 칸의 text 는 "지금까지 쓴 전체 글"이고 끝난 문장까지만 온다. 이 값은 경과 시간만으로 계산해서(Job 시뮬레이터와 같은 방식)
 * 폴링 간격이나 새로고침과 상관없이 결정적이다.
 *
 * 데모 대본 (카드 순서대로)
 *  - 첫 카드: 결과 칸을 '근거 없는 숫자'로 써서 검사에 걸리고, 몇 초 뒤 이슈를 열어 본 뒤 다시 쓴다(round 1). 과제 칸은 근거가 없어 비운다.
 *  - 카드가 3장 이상이면 둘째 카드는 도중에 AI 가 끊겨 FAILED 로 끝난다(쓴 칸은 남는다).
 *  - 나머지 카드: 과제 칸만 비우고 한 번에 쓴다.
 *  - 약한 후보가 섞여 일부만 쓴 Job(partial): 상황과 행동 칸만 쓰고 나머지 칸은 주지 않는다.
 * 카드가 많아도 데모는 DEMO_DRAFT_MAX_SEC 안에 끝나게 모든 시간을 같은 비율로 줄인다.
 */
type Any = Record<string, any>;
type Field = 'S' | 'T' | 'A' | 'R';

export const THINK_SEC = 8;
export const DEMO_DRAFT_MAX_SEC = 36;

export type DraftPlan = 'REWRITE' | 'OK' | 'FAIL' | 'PARTIAL';

/** 읽고 생각한 뒤 쓰는 구간의 길이(초). 네 칸은 앞 2초 안에 몰아 쓰고, 다시 쓰는 카드는 검사와 고쳐 쓰기가 그 몇 초 뒤에 이어진다 */
const WRITE_SEC: Record<DraftPlan, number> = { OK: 2, PARTIAL: 2, FAIL: 0.8, REWRITE: 5.2 };

/** 카드마다 시작 시각과 읽는 시간, 전체 길이. 장수가 많으면 전체를 DEMO_DRAFT_MAX_SEC 안으로 같은 비율로 줄인다 */
export function draftSchedule(plans: readonly DraftPlan[]) {
  const base = plans.map((plan) => THINK_SEC + WRITE_SEC[plan]);
  const scale = Math.min(1, DEMO_DRAFT_MAX_SEC / Math.max(1, base.reduce((sum, sec) => sum + sec, 0)));
  const starts: number[] = [];
  let at = 0;
  for (const sec of base) { starts.push(at); at += sec * scale; }
  return { scale, think: THINK_SEC * scale, starts, total: at };
}

export function draftPlans(count: number, partial: boolean): DraftPlan[] {
  let rewritten = false;
  return Array.from({ length: count }, (_, index): DraftPlan => {
    if (partial) return 'PARTIAL';
    if (count >= 3 && index === 1) return 'FAIL';
    if (!rewritten) { rewritten = true; return 'REWRITE'; }
    return 'OK';
  });
}

/** 카드에 저장되는 글과 AI 가 쓰다 고친 첫 글. fillDraft 가 같은 값으로 카드를 채우므로 라이브 마지막 모습과 저장된 카드가 같다 */
export function draftTexts(input: { repoName: string; title: string; commits: number }) {
  const { repoName, title, commits } = input;
  return {
    S: `${repoName} 에서 ${title} 이 필요한 상황이었다`,
    A: `${title} 을 구현했다. ${commits}개 커밋에 걸쳐 나눠 반영했다`,
    R: `${title} 이후 관련 수정 커밋이 줄었다`,
    /** 검사에 걸리기 전의 첫 글 — 근거 없는 숫자가 들어 있다 */
    firstR: `${title} 이후 관련 수정 커밋이 50% 줄었다`,
  };
}

const step = (round: number, actor: 'MODEL' | 'CODE', kind: string, field: Field | null, rest: Record<string, string> = {}) =>
  ({ round, actor, kind, field, code: null, value: null, material: null, reason: null, ...rest });

/** 카드 상세 'AI 가 한 일'(draftSteps)로도 저장되는 과정 */
export function draftStepsOf(plan: DraftPlan, title: string): Any[] {
  if (plan === 'FAIL') return [];
  if (plan === 'PARTIAL') return [step(0, 'MODEL', 'DRAFT', null)];
  const keepT = step(1, 'MODEL', 'KEEP', 'T', { reason: '이슈에도 맡은 일이 적혀 있지 않음' });
  if (plan === 'OK') {
    return [step(0, 'MODEL', 'DRAFT', null), step(0, 'CODE', 'FLAG', 'T', { code: 'NO_EVIDENCE' }), keepT];
  }
  return [
    step(0, 'MODEL', 'DRAFT', null),
    step(0, 'CODE', 'FLAG', 'T', { code: 'NO_EVIDENCE' }),
    step(0, 'CODE', 'FLAG', 'R', { code: 'UNSOURCED_NUMBER', value: '50%' }),
    step(1, 'MODEL', 'OPEN', 'T', { value: `이슈 #98 ${title}`, material: 'ISSUE', reason: '과제가 이슈 본문에 적혀 있을 것 같음' }),
    keepT,
    step(1, 'MODEL', 'OPEN', 'R', { value: `이슈 #98 ${title}`, material: 'ISSUE', reason: '결과 수치가 이슈에 있을 것 같음' }),
    step(1, 'MODEL', 'REWRITE', 'R'),
    step(1, 'CODE', 'PASS', 'R'),
  ];
}

type Event = { at: number; slot?: { field: Field; round: number; text: string; done: boolean }; step?: Any; end?: 'DONE' | 'FAILED' };

/** 쓰는 구간에서 일어나는 일. at 은 쓰기 시작 뒤 흐른 초다. 칸 글은 끝난 문장까지만 온다 */
function events(plan: DraftPlan, texts: ReturnType<typeof draftTexts>, steps: Any[]): Event[] {
  const slot = (field: Field, text: string, done = true, round = 0) => ({ field, round, text, done });
  const firstSentence = texts.A.split('. ')[0] + '.';
  if (plan === 'FAIL') {
    return [{ at: 0.2, slot: slot('S', texts.S) }, { at: 0.5, slot: slot('T', '') }, { at: 0.8, end: 'FAILED' }];
  }
  if (plan === 'PARTIAL') {
    return [{ at: 0, step: steps[0] }, { at: 0.2, slot: slot('S', texts.S) }, { at: 0.8, slot: slot('A', firstSentence, false) },
      { at: 1.1, slot: slot('A', texts.A) }, { at: 2, end: 'DONE' }];
  }
  const burst: Event[] = [
    { at: 0, step: steps[0] }, { at: 0.2, slot: slot('S', texts.S) }, { at: 0.5, slot: slot('T', ''), step: steps[1] },
    { at: 0.8, slot: slot('A', firstSentence, false) }, { at: 1.1, slot: slot('A', texts.A) },
  ];
  if (plan === 'OK') return [...burst, { at: 1.4, slot: slot('R', texts.R) }, { at: 1.6, step: steps[2] }, { at: 2, end: 'DONE' }];
  return [
    ...burst,
    { at: 1.4, slot: slot('R', texts.firstR), step: steps[2] },
    { at: 4.6, step: steps[3] }, { at: 4.7, step: steps[4] }, { at: 4.8, step: steps[5] },
    { at: 5, slot: slot('R', texts.R, true, 1), step: steps[6] }, { at: 5.1, step: steps[7] },
    { at: 5.2, end: 'DONE' },
  ];
}

/**
 * 카드 한 장의 drafts 항목. since 는 이 카드 차례가 시작된 뒤 흐른 초(음수면 아직 차례가 아니다), Infinity 면 끝난 모습.
 * think 와 scale 은 draftSchedule 이 준다.
 */
export function liveDraftOf(input: { cardId: string; title: string; plan: DraftPlan; texts: ReturnType<typeof draftTexts>; think: number; scale: number; since: number }): Any {
  const { cardId, title, plan, texts, think, scale, since } = input;
  const steps = draftStepsOf(plan, title);
  const base = { cardId, title };
  if (since < 0) return { ...base, state: 'WAITING', slots: [], steps: [] };
  if (since < think) return { ...base, state: 'READING', slots: [], steps: [] };
  const written = (since - think) / scale; // 쓰기 시작 뒤 흐른 시간을 대본의 초로 되돌린다
  const slots = new Map<Field, Any>();
  const seen: Any[] = [];
  let state = 'WRITING';
  for (const event of events(plan, texts, steps)) {
    if (event.at > written) break;
    if (event.slot) slots.set(event.slot.field, event.slot);
    if (event.step) seen.push(event.step);
    if (event.end) state = event.end;
  }
  return { ...base, state, slots: [...slots.values()], steps: seen };
}

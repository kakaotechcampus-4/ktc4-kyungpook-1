import { useEffect, useReducer, useRef, useState } from 'react';
import { useJob } from '@/api/queries';
import { isTerminal } from '@/api/schemas';
import { DraftPlayback, type LiveView } from '@/lib/liveDraft';

const TICK_MS = 50;
/** 카드가 이미 완성으로 바뀌었는데 Job 의 끝 상태를 아직 못 받았을 때 기다리는 최대 시간 */
const WAIT_FOR_JOB_MS = 5_000;

const reducedMotionQuery = () => typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;

/** 사용자가 OS 에서 동작 줄이기를 켰는지. 바뀌면 바로 따라간다 */
export function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(() => reducedMotionQuery()?.matches ?? false);
  useEffect(() => {
    const query = reducedMotionQuery();
    if (!query) return;
    const update = () => setReduced(query.matches);
    update();
    query.addEventListener?.('change', update);
    return () => query.removeEventListener?.('change', update);
  }, []);
  return reduced;
}

type Holder = {
  scope: string;
  playback: DraftPlayback;
  /** 이 화면이 보던 Job. 카드가 완성으로 바뀌어 generation 이 없어져도 끝 상태까지 이어서 읽어야 한다 */
  jobId: string | undefined;
  terminal: boolean;
  succeeded: boolean;
  wasGenerating: boolean;
  /** 카드가 완성으로 바뀐(generation 이 사라진) 시각 */
  clearedAt: number | null;
};

/**
 * 카드 초안을 쓰는 동안의 화면 상태(#617). 1초마다 다시 읽는 Job 의 `drafts` 를 펼침 모델(DraftPlayback)에 넣고,
 * 짧은 틱으로 시간을 흘려 화면을 다시 그린다. 시계는 여기서만 읽는다.
 *
 * - scope: 화면이 보는 대상(카드 id). 바뀌면 새로 시작한다.
 * - jobId, generating: 카드의 generation 이 가리키는 Job, 그리고 지금 '초안 생성 중' 화면을 그리고 있는가.
 * - holding: Job 이 끝났지만 마지막 카드를 다 펼치고 쉬기 전이라 이 화면을 붙잡아 둬야 하는가.
 *   카드가 이미 완성으로 바뀌어도(generating 이 false) true 일 수 있다. 이 화면을 보고 있던 경우에만이고,
 *   Job 이 실패하거나 취소됐을 때는 붙잡지 않는다. 카드가 먼저 바뀌고 Job 의 끝 상태가 늦게 오는 순서도 있어서
 *   화면이 보던 Job 을 직접 끝까지 읽는다(카드의 Job 조회는 generation 이 사라지면 꺼진다).
 * - 동작 줄이기를 켰거나 '한 번에 보기'를 누르면 글을 펼치지 않고 오는 대로 한 번에 보인다.
 */
export function useLiveDraft(options: { scope: string; jobId: string | undefined; generating: boolean }) {
  const { scope, jobId, generating } = options;
  const reducedMotion = usePrefersReducedMotion();
  const [manualInstant, setManualInstant] = useState(false);
  const instant = reducedMotion || manualInstant;
  const [, redraw] = useReducer((count: number) => count + 1, 0);

  const ref = useRef<Holder | null>(null);
  if (!ref.current || ref.current.scope !== scope) {
    ref.current = { scope, playback: new DraftPlayback({ instant }), jobId: undefined, terminal: false, succeeded: false, wasGenerating: false, clearedAt: null };
  }
  const holder = ref.current;
  const { playback } = holder;
  if (jobId) holder.jobId = jobId;

  const jobQuery = useJob(holder.jobId);
  const job = jobQuery.data;

  useEffect(() => {
    if (!job) return;
    holder.terminal = isTerminal(job.state);
    holder.succeeded = job.state === 'SUCCEEDED';
    playback.receive(job.drafts ?? [], { ended: holder.terminal, now: Date.now() });
    redraw();
  }, [job, holder, playback]);

  useEffect(() => {
    playback.setInstant(instant, Date.now());
    redraw();
  }, [instant, playback]);

  // 카드가 완성으로 바뀐 순간을 렌더에서 바로 적는다(같은 값을 다시 적어도 그대로다). 효과로 미루면 그 첫 렌더에서 붙잡을 이유를 잃는다
  if (generating) holder.clearedAt = null;
  else if (holder.wasGenerating && holder.clearedAt === null) holder.clearedAt = Date.now();

  const holdingNow = (now: number) => {
    if (generating || !holder.wasGenerating) return false;
    if (holder.terminal) return holder.succeeded && playback.holding;
    return holder.clearedAt !== null && now - holder.clearedAt < WAIT_FOR_JOB_MS;
  };
  const holding = holdingNow(Date.now());
  useEffect(() => { holder.wasGenerating = generating || holding; });

  const active = generating || holding;
  useEffect(() => {
    if (!active) return;
    let held = true;
    const timer = window.setInterval(() => {
      const now = Date.now();
      const changed = playback.advance(now);
      const stillHeld = generating || holdingNow(now);
      if (changed || stillHeld !== held) redraw();
      held = stillHeld;
    }, TICK_MS);
    return () => window.clearInterval(timer);
    // holdingNow 는 매 렌더 새로 만들어지지만 holder 의 값만 읽는다
  }, [active, generating, playback]);

  const view: LiveView = playback.view();
  return {
    view,
    holding,
    /** 홀드 중 진행 막대에 쓸 Job. 카드가 완성으로 바뀌면 카드가 따라가던 Job 조회가 꺼져 화면이 가진 Job 이 없어지기 때문이다 */
    job,
    reducedMotion,
    manualInstant,
    setManualInstant,
  };
}

import { useEffect, useRef, useState } from 'react';
import { advanceLive, isSettled, liveFrom, shownText, type LiveState } from '@/lib/draftPaper';

const QUERY = '(prefers-reduced-motion: reduce)';
/** 움직임을 줄여 달라는 설정이면 true — 타이핑·취소선 없이 글을 바로 바꾼다 */
export function useReducedMotion(): boolean {
  const [reduced, setReduced] = useState(() => typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia(QUERY).matches);
  useEffect(() => {
    if (!window.matchMedia) return;
    const mq = window.matchMedia(QUERY);
    const on = () => setReduced(mq.matches);
    mq.addEventListener?.('change', on);
    return () => mq.removeEventListener?.('change', on);
  }, []);
  return reduced;
}

/**
 * 서버 글(target)을 사람이 치듯 따라붙이는 글로 바꾼다. 처음 받은 글은 바로 보이고, 그 뒤 늘어난 글자만 친다.
 * 다음 조회 전에 다 따라잡도록 속도를 맞춘다(pollMs 의 8할 안). 고쳐 쓴 글은 문장 단위로 지우고 다시 친다.
 */
export function useLiveText(target: string, { active = true, pollMs = 1500 }: { active?: boolean; pollMs?: number } = {}) {
  const reduced = useReducedMotion();
  const instant = !active || reduced;
  const [state, setState] = useState<LiveState>(() => liveFrom(null, target, performance.now(), true));
  const ref = useRef(state);
  ref.current = state;

  useEffect(() => {
    const next = liveFrom(ref.current, target, performance.now(), instant);
    if (next !== ref.current) setState(next);
  }, [target, instant]);

  const settled = isSettled(state);
  useEffect(() => {
    if (settled) return;
    let last = performance.now();
    let frame = 0;
    const tick = (now: number) => {
      const dt = Math.min(100, now - last);
      last = now;
      setState((s) => advanceLive(s, now, dt, pollMs * 0.8));
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [settled, pollMs]);

  return { segs: state.segs, text: shownText(state), typing: !settled };
}

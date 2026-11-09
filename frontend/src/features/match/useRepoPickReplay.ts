import { useCallback, useEffect, useMemo, useState } from 'react';
import { REPLAY_FRAME_MS, replayFrames, type Live } from '@/lib/repoPick';

/**
 * 끝난 고르기 과정을 장면별로 다시 보인다. auto 가 true 면 처음 한 번 저절로 시작하고, start 로는 언제든 다시 시작한다.
 * 마지막 장면에 닿으면 끝나고 onEnd 를 부른다. 움직임을 줄이는 설정이면 start 는 바로 끝낸다.
 */
export function useRepoPickReplay(live: Live | null, auto: boolean, reduceMotion: boolean, onEnd: () => void) {
  const frames = useMemo(() => (live ? replayFrames(live) : []), [live]);
  const [index, setIndex] = useState<number | null>(null);
  const [autoUsed, setAutoUsed] = useState(false);
  const autoPending = auto && !autoUsed && frames.length > 0;
  const replaying = index !== null || autoPending;
  const current = index ?? 0;

  const finish = useCallback(() => { setIndex(null); setAutoUsed(true); onEnd(); }, [onEnd]);
  const start = useCallback(() => {
    if (reduceMotion) { onEnd(); return; }
    setIndex(0);
  }, [reduceMotion, onEnd]);

  useEffect(() => {
    if (!autoPending) return;
    setAutoUsed(true);
    setIndex(0);
  }, [autoPending]);

  useEffect(() => {
    if (index === null) return;
    const timer = setTimeout(() => {
      if (index + 1 >= frames.length - 1) finish();
      else setIndex(index + 1);
    }, REPLAY_FRAME_MS);
    return () => clearTimeout(timer);
  }, [index, frames.length, finish]);

  return { replaying, frame: replaying ? frames[current] ?? null : null, start, skip: finish };
}

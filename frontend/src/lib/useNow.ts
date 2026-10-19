import { useEffect, useState } from 'react';

/** active 인 동안 ms 마다 현재 시각을 다시 읽는다 — 경과 시간처럼 흐르는 값을 그릴 때 쓴다. */
export function useNow(active: boolean, ms = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) return;
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), ms);
    return () => window.clearInterval(timer);
  }, [active, ms]);
  return now;
}

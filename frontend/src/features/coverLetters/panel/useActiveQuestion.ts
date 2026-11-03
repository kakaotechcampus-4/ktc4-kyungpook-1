import { useEffect, useState, type RefObject } from 'react';

/** 패널이 아래 막대 + 시트로 바뀌는 폭 — panel.css 의 미디어 쿼리와 같은 값이어야 한다 */
export const SHEET_QUERY = '(max-width: 859.98px)';

export function useMatches(query: string): boolean {
  const [on, setOn] = useState(() => typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia(query).matches);
  useEffect(() => {
    if (!window.matchMedia) return;
    const mq = window.matchMedia(query);
    const change = () => setOn(mq.matches);
    change();
    mq.addEventListener?.('change', change);
    return () => mq.removeEventListener?.('change', change);
  }, [query]);
  return on;
}

/**
 * 지금 보고 있는 문항 — 화면 위에서 35~45% 띠에 걸린 문항이고, 글상자에 들어가면 그 문항이다. 맨 아래까지 내리면 마지막 문항.
 * lock 이 있으면(첨삭 받는 중) 종이를 내려도 그 문항에 묶인다.
 */
export function useActiveQuestion(paperRef: RefObject<HTMLElement | null>, ids: string[], lock: string | null): [string, (id: string) => void] {
  const [active, setActive] = useState(ids[0] ?? '');
  const key = ids.join('|');

  useEffect(() => {
    const root = paperRef.current;
    if (!root) return;
    const idOf = (el: Element | null) => (el instanceof HTMLElement ? el.closest<HTMLElement>('[data-question]')?.dataset.question ?? null : null);
    const onFocus = (e: FocusEvent) => { const id = idOf(e.target as Element); if (id) setActive(id); };
    root.addEventListener('focusin', onFocus);
    const onScroll = () => {
      const doc = document.documentElement;
      if (window.innerHeight + window.scrollY >= doc.scrollHeight - 4 && ids.length) setActive(ids[ids.length - 1]);
    };
    window.addEventListener('scroll', onScroll, { passive: true });
    let io: IntersectionObserver | null = null;
    if (typeof IntersectionObserver !== 'undefined') {
      const inBand = new Map<string, boolean>();
      io = new IntersectionObserver((entries) => {
        for (const e of entries) { const id = idOf(e.target); if (id) inBand.set(id, e.isIntersecting); }
        const first = ids.find((id) => inBand.get(id));
        if (first) setActive(first);
      }, { rootMargin: '-35% 0px -55% 0px' });
      root.querySelectorAll('[data-question]').forEach((el) => io!.observe(el));
    }
    return () => { root.removeEventListener('focusin', onFocus); window.removeEventListener('scroll', onScroll); io?.disconnect(); };
    // ids 는 key 로 비교한다 — 응답마다 새 배열이라 그대로 두면 관찰을 매번 다시 건다
  }, [paperRef, key]);

  const current = lock ?? (ids.includes(active) ? active : ids[0] ?? '');
  return [current, setActive];
}

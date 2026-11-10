import { useEffect, useState } from 'react';

/**
 * 가로로 넘겨 보는 줄(탭, 칩)에 아직 보이지 않는 쪽이 있으면 data-edge 에 start, end, both 를 단다(#919).
 * 끝 흐림은 CSS(.scroll-edges)가 그린다. 다 보이면 속성을 지워 흐림이 남지 않는다.
 * 줄이 늦게 그려져도 붙도록 콜백 ref 를 돌려준다.
 */
export function useScrollEdges<T extends HTMLElement>() {
  const [el, setEl] = useState<T | null>(null);
  useEffect(() => {
    if (!el) return;
    const update = () => {
      const start = el.scrollLeft > 1;
      const end = el.scrollLeft + el.clientWidth < el.scrollWidth - 1;
      const edge = start && end ? 'both' : start ? 'start' : end ? 'end' : null;
      if (edge) el.dataset.edge = edge;
      else delete el.dataset.edge;
    };
    update();
    el.addEventListener('scroll', update, { passive: true });
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(update);
    observer?.observe(el);
    return () => { el.removeEventListener('scroll', update); observer?.disconnect(); };
  }, [el]);
  return setEl;
}

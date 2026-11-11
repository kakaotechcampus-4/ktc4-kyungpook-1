import { useEffect, useState, type RefObject } from 'react';

const reduced = () => typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/**
 * 첫 화면의 스크롤 움직임 세 가지를 한곳에서 건다.
 * - [data-reveal] 블록과 [data-split] 제목은 화면에 들어오면 떠오른다(PC 는 조금 미리 시작한다).
 * - [data-ink] 구간의 [data-ink-part] 구절은 스크롤한 만큼 회색에서 검게 채워진다.
 * - 새로고침하면 브라우저가 기억한 위치 대신 맨 위에서 시작한다.
 * 움직임 줄이기를 켰거나 IntersectionObserver 가 없으면 아무것도 숨기지 않는다(data-motion 을 달지 않는다).
 */
export function useLandingMotion(root: RefObject<HTMLElement | null>, ready: boolean) {
  const [scrolled, setScrolled] = useState(false);

  useEffect(() => {
    const prev = history.scrollRestoration;
    history.scrollRestoration = 'manual';
    if (window.scrollY > 0) window.scrollTo(0, 0);
    return () => { history.scrollRestoration = prev; };
  }, []);

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  useEffect(() => {
    const el = root.current;
    if (!el || !ready || reduced() || typeof IntersectionObserver === 'undefined') return;
    el.setAttribute('data-motion', 'on');
    el.querySelectorAll<HTMLElement>('[data-split]').forEach((h) => {
      h.querySelectorAll<HTMLElement>('[data-word]').forEach((w, i) => { w.style.transitionDelay = `${i * 70}ms`; });
    });
    const wide = window.innerWidth >= 900;
    const io = new IntersectionObserver((entries) => {
      for (const e of entries) if (e.isIntersecting) { e.target.setAttribute('data-shown', ''); io.unobserve(e.target); }
    }, { rootMargin: wide ? '0px 0px 18% 0px' : '0px 0px -4% 0px', threshold: 0 });
    el.querySelectorAll('[data-reveal],[data-split]').forEach((n) => io.observe(n));

    const ink = el.querySelector<HTMLElement>('[data-ink]');
    const parts = ink ? Array.from(ink.querySelectorAll<HTMLElement>('[data-ink-part]')) : [];
    let frame = 0;
    const paint = () => {
      frame = 0;
      if (!ink) return;
      const r = ink.getBoundingClientRect(); const vh = window.innerHeight;
      // 고정되는 구간(PC)은 들어오면서 칠하기 시작해 고정이 풀리기 전에 끝내고, 고정 없는 구간(모바일)은 지나가는 동안 칠한다
      const p = r.height > vh * 1.2 ? (vh * 0.6 - r.top) / (r.height - vh * 0.55) : (vh * 0.9 - r.top) / (vh * 0.6);
      const done = Math.min(1, Math.max(0, p)) * parts.length * 1.1;
      parts.forEach((s, i) => s.toggleAttribute('data-inked', done > i + 0.5));
    };
    const onScroll = () => { if (!frame) frame = requestAnimationFrame(paint); };
    paint();
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll);
    return () => {
      io.disconnect();
      window.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', onScroll);
      if (frame) cancelAnimationFrame(frame);
      el.removeAttribute('data-motion');
    };
  }, [root, ready]);

  return { scrolled };
}

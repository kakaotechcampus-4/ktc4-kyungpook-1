import { useCallback, useEffect, useRef } from 'react';
import { Wordmark } from '@/components/layout/AppShell';

const SEEN_KEY = 'gitory.brand-intro.v1';

export function shouldPlayLandingIntro(params: URLSearchParams): boolean {
  if (params.has('reason') || params.has('error') || params.has('consent')) return false;
  if (typeof window === 'undefined' || !window.matchMedia || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return false;
  try { return params.get('intro') === '1' || sessionStorage.getItem(SEEN_KEY) !== '1'; }
  catch { return false; }
}

/** One wordmark travels to its real header slot; the two clipped parts retain the original artwork. */
export function LandingBrand({ active, onComplete }: { active: boolean; onComplete: () => void }) {
  const brand = useRef<HTMLDivElement>(null);
  const veil = useRef<HTMLDivElement>(null);
  const complete = useCallback(() => {
    try { sessionStorage.setItem(SEEN_KEY, '1'); } catch { /* Storage is optional. */ }
    onComplete();
  }, [onComplete]);

  useEffect(() => {
    if (!active) return;
    let disposed = false;
    const animations: Animation[] = [];
    const finish = () => {
      if (disposed) return;
      complete();
    };
    const keydown = (event: KeyboardEvent) => { if (event.key === 'Escape') finish(); };
    const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const onMotionChange = () => { if (motion.matches) finish(); };
    const fallback = window.setTimeout(finish, 2800);
    window.addEventListener('resize', finish);
    document.addEventListener('keydown', keydown);
    motion.addEventListener('change', onMotionChange);

    async function play() {
      const node = brand.current;
      if (!node || typeof node.animate !== 'function') { finish(); return; }
      await Promise.allSettled(Array.from(node.querySelectorAll('img'), img => img.decode()));
      if (disposed) return;
      const bounds = node.getBoundingClientRect();
      if (!bounds.width) { finish(); return; }
      const scale = Math.min(window.innerWidth < 768 ? 2.35 : 3.5, (window.innerWidth - 64) / bounds.width);
      const x = window.innerWidth / 2 - bounds.left - bounds.width / 2;
      const y = window.innerHeight / 2 - bounds.top - bounds.height / 2;
      const centered = `translate(${x}px, ${y}px) scale(${scale})`;
      const flight = node.animate([
        { transform: centered, opacity: 0, filter: 'blur(6px)', offset: 0, easing: 'cubic-bezier(.16, 1, .3, 1)' },
        { transform: centered, opacity: 1, filter: 'blur(0px)', offset: .2 },
        { transform: centered, opacity: 1, filter: 'blur(0px)', offset: .43, easing: 'cubic-bezier(.76, 0, .24, 1)' },
        { transform: 'translate(0, 0) scale(1)', opacity: 1, filter: 'blur(0px)', offset: 1 },
      ], { duration: 1600, easing: 'linear', fill: 'both' });
      animations.push(flight);
      const glyph = node.querySelector<HTMLElement>('.landing-brand__glyph');
      if (glyph) animations.push(glyph.animate([
        { transform: 'rotate(-8deg) scale(.9)' }, { transform: 'rotate(0) scale(1)' },
      ], { duration: 720, easing: 'cubic-bezier(.16, 1, .3, 1)', fill: 'both' }));
      const type = node.querySelector<HTMLElement>('.landing-brand__type');
      if (type) animations.push(type.animate([
        { clipPath: 'inset(0 0 100% 33.4%)', opacity: 0, transform: 'translateY(5px)' },
        { clipPath: 'inset(0 0 0 33.4%)', opacity: 1, transform: 'translateY(0)' },
      ], { duration: 600, delay: 180, easing: 'cubic-bezier(.16, 1, .3, 1)', fill: 'both' }));
      if (veil.current) animations.push(veil.current.animate([
        { opacity: 1 }, { opacity: 0 },
      ], { duration: 650, delay: 850, fill: 'both', easing: 'ease-in-out' }));
      flight.finished.then(finish, finish);
    }
    void play().catch(finish);
    return () => {
      disposed = true;
      clearTimeout(fallback);
      animations.forEach(animation => animation.cancel());
      window.removeEventListener('resize', finish);
      document.removeEventListener('keydown', keydown);
      motion.removeEventListener('change', onMotionChange);
    };
  }, [active, complete]);

  return <>
    {active && <div className="landing-intro" ref={veil}>
      <div className="landing-intro__wash" aria-hidden="true" />
      <button className="landing-intro__skip" onClick={complete}>건너뛰기 <span aria-hidden="true">↗</span></button>
    </div>}
    <div className="landing-brand" ref={brand} role="img" aria-label="Gitory">
      <div className="landing-brand__glyph" aria-hidden="true"><Wordmark height={34} /></div>
      <div className="landing-brand__type" aria-hidden="true"><Wordmark height={34} /></div>
    </div>
  </>;
}

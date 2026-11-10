import { useEffect, useRef, useState, type ReactNode } from 'react';

/** 밝은 화면과 어두운 화면 두 장을 같이 두고, 지금 테마에 맞는 쪽만 CSS 로 보여 준다 */
export function ThemedShot({ name, alt, width, height }: { name: string; alt: string; width: number; height: number }) {
  return <>
    <img className="lp-img lp-img--light" src={`/landing/${name}-light.png`} width={width} height={height} loading="lazy" decoding="async" alt={alt} />
    <img className="lp-img lp-img--dark" src={`/landing/${name}-dark.png`} width={width} height={height} loading="lazy" decoding="async" alt="" />
  </>;
}

/** 화면에 들어오면 한 번만 살짝 올라오며 나타난다. 관찰할 수 없는 환경에서는 처음부터 보인다 */
export function Reveal({ children, className = '' }: { children: ReactNode; className?: string }) {
  const box = useRef<HTMLDivElement>(null);
  const [seen, setSeen] = useState(typeof IntersectionObserver === 'undefined');

  useEffect(() => {
    if (seen || !box.current) return;
    const observer = new IntersectionObserver(([entry]) => { if (entry.isIntersecting) setSeen(true); }, { threshold: .15 });
    observer.observe(box.current);
    return () => observer.disconnect();
  }, [seen]);

  return <div ref={box} className={`lp-reveal ${seen ? 'lp-reveal--in' : ''} ${className}`}>{children}</div>;
}

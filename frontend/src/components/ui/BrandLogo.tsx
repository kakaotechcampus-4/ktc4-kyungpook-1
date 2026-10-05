/** Both themes use the same logo shape; only its foreground color changes. */
export function BrandLogo({ height = 26, mark = false, className = '' }: { height?: number; mark?: boolean; className?: string }) {
  const asset = mark ? 'gitory-mark' : 'gitory-wordmark';
  return <span className={`brand-logo ${mark ? 'brand-logo--mark' : 'brand-logo--wordmark'} ${className}`} style={{ height }}>
    <img className="brand-logo__light" src={`/${asset}.png`} alt="Gitory" draggable={false} />
    <img className="brand-logo__dark" src={`/${asset}-white.svg`} alt="Gitory" draggable={false} />
  </span>;
}

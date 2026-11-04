import markDark from '@/assets/brand/gitory-mark-white.svg';
import markLight from '@/assets/brand/gitory-mark.svg';
import wordmarkDark from '@/assets/brand/gitory-wordmark-white.svg';
import wordmarkLight from '@/assets/brand/gitory-wordmark.svg';

/**
 * Both themes use the same logo shape; only its foreground color changes.
 * The artwork is vector (assets/brand): the old PNG-in-SVG files were shrunk ~8x by the browser and looked smeared.
 */
export function BrandLogo({ height = 26, mark = false, className = '' }: { height?: number; mark?: boolean; className?: string }) {
  return <span className={`brand-logo ${mark ? 'brand-logo--mark' : 'brand-logo--wordmark'} ${className}`} style={{ height }}>
    <img className="brand-logo__light" src={mark ? markLight : wordmarkLight} alt="Gitory" draggable={false} />
    <img className="brand-logo__dark" src={mark ? markDark : wordmarkDark} alt="Gitory" draggable={false} />
  </span>;
}

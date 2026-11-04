import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { BrandLogo } from '@/components/ui/BrandLogo';

afterEach(cleanup);

const asset = (name: string) => readFileSync(resolve(process.cwd(), 'src/assets/brand', name), 'utf8');

/** 실제 사이트 점검(11/04)에서 "Gitory 로고가 종종 뭉개진다"고 나왔다. 640x212 그림을 8배쯤 줄여 쓰던 PNG(와 그것을 감싼 SVG)가 원인이었다 */
describe('브랜드 로고는 벡터다', () => {
  for (const name of ['gitory-wordmark.svg', 'gitory-wordmark-white.svg', 'gitory-mark.svg', 'gitory-mark-white.svg']) {
    it(`${name}: 도형(path)으로만 그려지고 그림(래스터)을 품지 않으며 필터도 쓰지 않는다`, () => {
      const svg = asset(name);
      expect(svg).toContain('<path');
      expect(svg).not.toMatch(/<image|data:image|<filter|base64/);
      expect(svg.length).toBeLessThan(12_000);
    });
  }

  it('라이트는 검정, 다크는 흰색이고 같은 도형이다', () => {
    for (const base of ['gitory-wordmark', 'gitory-mark']) {
      const light = asset(`${base}.svg`);
      const dark = asset(`${base}-white.svg`);
      expect(light).toContain('fill="#000"');
      expect(dark).toContain('fill="#fff"');
      expect(dark.replace('fill="#fff"', 'fill="#000"')).toBe(light);
    }
  });

  it('워드마크는 원래 그림과 같은 640x212 틀, 심볼은 48x48 틀을 쓴다(자리가 바뀌지 않는다)', () => {
    expect(asset('gitory-wordmark.svg')).toContain('viewBox="0 0 640 212"');
    expect(asset('gitory-mark.svg')).toContain('viewBox="0 0 48 48"');
  });

  it('BrandLogo 는 라이트·다크 그림을 SVG 로 두 장 두고 이름은 Gitory 다', () => {
    for (const mark of [false, true]) {
      const { container, unmount } = render(<BrandLogo height={26} mark={mark} />);
      const light = container.querySelector('img.brand-logo__light')!;
      const dark = container.querySelector('img.brand-logo__dark')!;
      for (const img of [light, dark]) {
        expect(img).toHaveAttribute('alt', 'Gitory');
        expect(img.getAttribute('src')).toMatch(/\.svg(\?|$)|^data:image\/svg\+xml/);
        expect(img.getAttribute('src')).not.toMatch(/\.png/);
      }
      expect(light.getAttribute('src')).not.toBe(dark.getAttribute('src'));
      expect(container.firstElementChild).toHaveClass(mark ? 'brand-logo--mark' : 'brand-logo--wordmark');
      unmount();
    }
  });
});

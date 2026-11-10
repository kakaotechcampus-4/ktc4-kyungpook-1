/**
 * 디자인 기본값 지킴이(#804, #918). 화면이 늘어도 색, 글자 크기, 굵기, 버튼이 다시 벌어지지 않게 한다.
 *
 * - 굵기는 토큰(var(--weight-*))만, 숫자 굵기는 0개
 * - 버튼 클래스("btn …")를 손으로 쓰지 않는다 — Button, ButtonLink, buttonClass() 만
 * - tokens.css 밖의 #hex 색과 단계 밖 글자 크기는 지금 개수(design-tokens-baseline.json)를 넘지 못한다.
 *   줄이면 통과하고, 기준을 낮추려면 UPDATE_DESIGN_BASELINE=1 npx vitest run design-tokens 로 다시 쓴다
 */
import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { describe, expect, it } from 'vitest';
import baseline from './design-tokens-baseline.json';

const ROOT = join(__dirname, '..');
/** 글자 크기 단계 — tokens.css 의 주석과 같다. 랜딩 큰 제목(30, 40)만 따로 둔다 */
const FONT_SCALE = new Set([11, 12, 13, 14, 16, 20, 28, 30, 40]);

const walk = (dir: string, ext: string): string[] => readdirSync(dir).flatMap((name) => {
  const p = join(dir, name);
  return statSync(p).isDirectory() ? walk(p, ext) : p.endsWith(ext) ? [p] : [];
});
const rel = (p: string) => relative(ROOT, p).split('\\').join('/');
const css = walk(ROOT, '.css').filter((p) => rel(p) !== 'styles/tokens.css');
/** 버튼 클래스를 만드는 곳(components/ui/index.tsx)과 시험은 뺀다 */
const tsx = walk(ROOT, '.tsx').filter((p) => !rel(p).startsWith('test/') && rel(p) !== 'components/ui/index.tsx');

function counts() {
  const hex: Record<string, number> = {};
  const size: Record<string, number> = {};
  for (const file of css) {
    const text = readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
    const h = (text.match(/#[0-9a-fA-F]{3,8}\b/g) ?? []).length;
    const s = [...text.matchAll(/font-size:\s*([\d.]+)px/g)].filter((m) => !FONT_SCALE.has(Number(m[1]))).length;
    if (h) hex[rel(file)] = h;
    if (s) size[rel(file)] = s;
  }
  return { hex, size };
}

describe('디자인 기본값', () => {
  it('굵기는 토큰 세 단계(400, 700, 800)만 쓴다', () => {
    const numeric = css.flatMap((f) => [...readFileSync(f, 'utf8').matchAll(/font-weight:\s*\d{3}/g)].map(() => rel(f)));
    expect(numeric).toEqual([]);
  });

  it('버튼 클래스를 손으로 쓰지 않는다 — Button, ButtonLink, buttonClass() 만', () => {
    const hand = tsx.filter((f) => /["'`]btn btn--|className="btn\b|btn--(outline|ghost)\b/.test(readFileSync(f, 'utf8'))).map(rel);
    expect(hand).toEqual([]);
  });

  it('tokens.css 밖의 #hex 색과 단계 밖 글자 크기가 늘지 않는다', () => {
    const now = counts();
    if (process.env.UPDATE_DESIGN_BASELINE) {
      writeFileSync(join(__dirname, 'design-tokens-baseline.json'), `${JSON.stringify(now, null, 2)}\n`);
      return;
    }
    const grew: string[] = [];
    for (const kind of ['hex', 'size'] as const) {
      const base = (baseline as Record<string, Record<string, number>>)[kind] ?? {};
      for (const [file, n] of Object.entries(now[kind])) {
        if (n > (base[file] ?? 0)) grew.push(`${file}: ${kind} ${base[file] ?? 0} → ${n}`);
      }
    }
    expect(grew, '새 색은 tokens.css 에 토큰으로 더하고, 글자 크기는 11, 12, 13, 14, 16, 20, 28 중에서 고르세요').toEqual([]);
  });
});

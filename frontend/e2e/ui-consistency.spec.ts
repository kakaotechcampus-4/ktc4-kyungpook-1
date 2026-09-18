import { test, expect } from '@playwright/test';
import { loginAsDemo } from './helpers';

/**
 * 목록을 "테두리 하나짜리 박스 + 칸막이선"으로 바꾸고 나서, 낱장 카드 시절의 hover 규칙이 남아
 * 마우스를 올린 칸의 칸막이선 하나만 진해지거나 아예 사라지는 문제가 세 화면에서 있었다.
 * 눈으로만 잡히는 종류라 규칙 자체를 검사한다 — 칸막이선을 가진 요소에는 border 를 건드리는 hover 가 붙으면 안 된다.
 */
const ROUTES = ['/', '/repos', '/repos/r_auth/candidates', '/cards'];

/** 칸막이선(보이는 테두리)을 가진 요소에 걸리는 :hover 규칙 중 border 를 바꾸는 것들 */
const AUDIT = () => {
  const rules = Array.from(document.styleSheets)
    .flatMap((s) => { try { return Array.from(s.cssRules) as CSSStyleRule[]; } catch { return []; } })
    .filter((r) => r.selectorText && r.selectorText.includes(':hover'));

  const bad: string[] = [];
  document.querySelectorAll('.record-list > *, .experience-list > *').forEach((el) => {
    const cs = getComputedStyle(el);
    const hasSeparator = (['Top', 'Right', 'Bottom', 'Left'] as const)
      .some((s) => parseFloat(cs[`border${s}Width` as 'borderTopWidth']) > 0);
    if (!hasSeparator) return;
    rules.forEach((r) => {
      r.selectorText.split(',').forEach((sel) => {
        const base = sel.trim().replace(/:hover/g, '');
        if (!base) return;
        try {
          if (el.matches(base) && /border/.test(r.style.cssText)) bad.push(`${sel.trim()} { ${r.style.cssText} }`);
        } catch { /* 지원 안 되는 선택자는 건너뛴다 */ }
      });
    });
  });
  return Array.from(new Set(bad));
};

test('목록의 칸에는 테두리를 건드리는 hover 가 없다', async ({ page }) => {
  await loginAsDemo(page);
  for (const route of ROUTES) {
    await page.goto(route);
    await page.waitForLoadState('networkidle');
    const bad = await page.evaluate(AUDIT);
    expect(bad, `${route} 의 목록 칸에 테두리를 바꾸는 hover 규칙이 붙어 있다`).toEqual([]);
  }
});

test('목록 행에 마우스를 올리면 배경만 바뀐다 — 칸막이선은 그대로다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/repos');
  const row = page.locator('.record-list > *').nth(1); // 첫 행은 칸막이선이 없다
  const before = await row.evaluate((el) => {
    const cs = getComputedStyle(el);
    return { border: cs.borderTopColor, bg: cs.backgroundColor };
  });
  await row.hover();
  const after = await row.evaluate((el) => {
    const cs = getComputedStyle(el);
    return { border: cs.borderTopColor, bg: cs.backgroundColor };
  });
  expect(after.border).toBe(before.border);  // 선은 그대로
  expect(after.bg).not.toBe(before.bg);      // 배경은 눈에 띄게 바뀐다
});

test('경험 카드가 홀수 개여도 마지막 칸이 한 줄을 다 쓴다', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await loginAsDemo(page);
  await page.goto('/cards');
  await page.locator('.experience-list > *').first().waitFor();
  const geo = await page.evaluate(() => {
    const list = document.querySelector('.experience-list')!;
    const kids = Array.from(list.children);
    return { count: kids.length, lastW: kids[kids.length - 1].getBoundingClientRect().width, listW: list.getBoundingClientRect().width };
  });
  expect(geo.count % 2).toBe(1);                       // 시드가 홀수여야 이 검사가 의미 있다
  expect(geo.lastW).toBeGreaterThan(geo.listW - 4);    // 오른쪽이 비지 않는다
});

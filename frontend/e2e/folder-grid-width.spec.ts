import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

const SHOTS = 'docs/screenshots/2026-11-04-folder-grid';

/** 폴더 격자는 머리 줄(폴더 수·활동 만들기)과 같은 폭을 채운다 — 넓은 화면에서 오른쪽만 비어 보이지 않게 */
for (const width of [1920, 1440]) {
  test(`폴더 격자가 머리 줄과 같은 폭을 채우고 ${width} 에서 한 줄에 ${width >= 1920 ? 4 : 3}개다`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await loginAsDemo(page);
    await page.goto('/cards');
    const folders = page.locator('.folder');
    await expect(folders).toHaveCount(5);
    const columns = await folders.evaluateAll((els) => new Set(els.map((el) => Math.round(el.getBoundingClientRect().left))).size);
    expect(columns).toBe(width >= 1920 ? 4 : 3);
    const [bar, grid] = await page.evaluate(() => ['.folders__bar', '.folder-grid'].map((s) => document.querySelector(s)!.getBoundingClientRect().right));
    expect(Math.abs(bar - grid)).toBeLessThanOrEqual(1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await page.screenshot({ path: `${SHOTS}/cards-${width}-light.png`, fullPage: true, animations: 'disabled' });
  });
}

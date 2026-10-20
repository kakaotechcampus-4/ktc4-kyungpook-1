import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

const routes = ['/repos', '/repos/r_auth/candidates', '/cards/new', '/cards/card_01?mode=edit', '/cards/card_01?mode=mask', '/cards/card_01'];
for (const [width, height] of [[320, 740], [390, 844], [767, 844], [768, 1024], [1024, 768], [1440, 900], [320, 480], [390, 480]]) {
  test(`shared footer keeps content clear at ${width}x${height}`, async ({ page }) => {
    await page.setViewportSize({ width, height });
    await loginAsDemo(page);
    for (const path of routes) {
      await page.goto(path);
      await expect(page.locator('.sticky-footer')).toBeVisible();
      await expect(page.locator('main .skeleton')).toHaveCount(0);
      await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
      const metrics = await page.evaluate(() => {
        const footer = document.querySelector('.sticky-footer')!;
        const content = document.querySelector('.card-navigation') ?? Array.from(document.querySelector('main')!.children).filter(el => el !== footer && el.getBoundingClientRect().height > 0).at(-1)!;
        const f = footer.getBoundingClientRect(), c = content.getBoundingClientRect();
        const nav = document.querySelector('.mobile-nav')!;
        return { gap: f.top - c.bottom, navTop: nav.getBoundingClientRect().top, footerBottom: f.bottom, overflow: document.documentElement.scrollWidth - innerWidth };
      });
      expect(metrics.gap, path).toBeGreaterThanOrEqual(width < 768 ? 15 : 0);
      expect(metrics.overflow, path).toBeLessThanOrEqual(0);
      if (width < 768) expect(metrics.footerBottom, path).toBeLessThanOrEqual(metrics.navTop + 1);
    }
  });
}

test('320px primary and masking preset labels remain on one line', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 });
  await loginAsDemo(page);
  for (const [path, labels] of [
    ['/repos/r_auth/run', ['정리 시작']],
    ['/cards/card_01?mode=mask', ['+ 규칙 추가', '+ 팀원 익명화', '+ 회사명 가리기']],
  ] as const) {
    await page.goto(path);
    for (const label of labels) {
      const button = page.getByRole('button', { name: label, exact: true });
      await expect(button).toBeVisible();
      const lines = await button.evaluate(el => {
        const range = document.createRange(); range.selectNodeContents(el);
        return new Set(Array.from(range.getClientRects()).filter(r => r.height > 0).map(r => Math.round(r.y))).size;
      });
      expect(lines, label).toBe(1);
    }
  }
});

import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

for (const theme of ['light', 'dark'] as const) {
  test(`${theme} collapsed navigation fits without horizontal scrolling`, async ({ page }) => {
    await loginAsDemo(page);
    await page.addInitScript(value => localStorage.setItem('gitory.theme', value), theme);
    await page.goto('/');
    await page.getByRole('button', { name: '사이드바 접기', exact: true }).click();
    const sidebar = page.locator('.sidebar');
    await expect.poll(async () => Math.round((await sidebar.boundingBox())!.width)).toBe(72);
    await page.keyboard.press('Tab');
    for (const [width, height] of [[1440, 960], [1920, 960], [1440, 390]]) {
      await page.setViewportSize({ width, height });
      const bounds = await sidebar.evaluate(element => {
        const rect = element.getBoundingClientRect();
        return { clientWidth: element.clientWidth, scrollWidth: element.scrollWidth, left: rect.left + element.clientLeft, right: rect.left + element.clientLeft + element.clientWidth };
      });
      expect(bounds.scrollWidth).toBeLessThanOrEqual(bounds.clientWidth);
      for (const control of await sidebar.locator('.menu-item, .sidebar__toggle').all()) {
        const box = (await control.boundingBox())!;
        expect(box.width).toBeGreaterThanOrEqual(44); expect(box.height).toBeGreaterThanOrEqual(44);
        expect(box.x).toBeGreaterThanOrEqual(bounds.left); expect(box.x + box.width).toBeLessThanOrEqual(bounds.right + .5);
        await control.focus();
        const ring = await control.evaluate(element => {
          const rect = element.getBoundingClientRect(), css = getComputedStyle(element);
          const extent = Math.max(0, parseFloat(css.outlineWidth) + parseFloat(css.outlineOffset));
          return { style: css.outlineStyle, left: rect.left - extent, right: rect.right + extent };
        });
        expect(ring.style).not.toBe('none');
        expect(ring.left).toBeGreaterThanOrEqual(bounds.left); expect(ring.right).toBeLessThanOrEqual(bounds.right + .5);
      }
    }
  });

  test(`${theme} tablet navigation keeps every menu inside the sidebar and reachable`, async ({ page }) => {
    await loginAsDemo(page);
    await page.addInitScript(value => localStorage.setItem('gitory.theme', value), theme);
    for (const width of [768, 800, 820, 1024]) {
      await page.setViewportSize({ width, height: 960 }); await page.goto('/');
      const sidebar = page.locator('.sidebar');
      await expect(sidebar).toBeVisible();
      const bounds = await sidebar.evaluate(element => ({ left: element.getBoundingClientRect().left, right: element.getBoundingClientRect().left + element.clientWidth, clientWidth: element.clientWidth, scrollWidth: element.scrollWidth }));
      expect(bounds.scrollWidth).toBeLessThanOrEqual(bounds.clientWidth);
      for (const control of await sidebar.locator('.menu-item').all()) {
        await expect(control).toBeInViewport(); const box = (await control.boundingBox())!;
        expect(box.x).toBeGreaterThanOrEqual(bounds.left); expect(box.x + box.width).toBeLessThanOrEqual(bounds.right + .5);
      }
      const settings = sidebar.locator('.menu-item[href="/settings"]');
      await settings.focus(); await settings.press('Enter'); await expect(page).toHaveURL(/\/settings$/);
      await expect(page.getByRole('heading', { name: '마이페이지', exact: true })).toBeVisible();
    }
  });

  test(`${theme} short collapsed navigation retains vertical scrolling and keyboard navigation`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 280 });
    await loginAsDemo(page);
    await page.addInitScript(value => localStorage.setItem('gitory.theme', value), theme);
    await page.goto('/');
    await page.getByRole('button', { name: '사이드바 접기', exact: true }).click();
    const sidebar = page.locator('.sidebar');
    await expect.poll(async () => Math.round((await sidebar.boundingBox())!.width)).toBe(72);
    const dimensions = await sidebar.evaluate(element => ({ clientWidth: element.clientWidth, scrollWidth: element.scrollWidth, clientHeight: element.clientHeight, scrollHeight: element.scrollHeight }));
    expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth);
    expect(dimensions.scrollHeight).toBeGreaterThan(dimensions.clientHeight);
    await sidebar.hover(); await page.mouse.wheel(0, 1000);
    await expect.poll(() => sidebar.evaluate(element => element.scrollTop)).toBeGreaterThan(0);
    const settings = sidebar.locator('.menu-item[href="/settings"]');
    await settings.focus(); await expect(settings).toBeInViewport(); await settings.press('Enter');
    await expect(page).toHaveURL(/\/settings$/);
    await expect(page.getByRole('heading', { name: '마이페이지', exact: true })).toBeVisible();
  });
}

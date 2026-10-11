import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

for (const theme of ['light', 'dark'] as const) {
  test(`${theme} collapsed navigation fits without horizontal scrolling`, async ({ page }) => {
    await loginAsDemo(page);
    await page.addInitScript(value => localStorage.setItem('gitory.theme', value), theme);
    await page.goto('/');
    await page.getByRole('button', { name: '사이드바 접기', exact: true }).click();
    const sidebar = page.locator('.sidebar');
    await expect.poll(async () => Math.round((await sidebar.boundingBox())!.width)).toBe(64);
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
    await expect.poll(async () => Math.round((await sidebar.boundingBox())!.width)).toBe(64);
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

  test(`${theme} pending repository steps have visible boundaries and readable numbers`, async ({ page }) => {
    await loginAsDemo(page);
    await page.addInitScript(value => localStorage.setItem('gitory.theme', value), theme);
    await page.goto('/repos');
    const pending = page.locator('main > .wizard__steps .wstep:not(.wstep--now):not(.wstep--done) .wstep__no');
    await expect(pending).toHaveCount(2);
    const colors = await pending.evaluateAll(elements => elements.map(element => {
      const css = getComputedStyle(element);
      return { canvas: getComputedStyle(document.documentElement).backgroundColor, fill: css.backgroundColor, text: css.color, border: css.borderTopColor, borderWidth: parseFloat(css.borderTopWidth) };
    }));
    const luminance = (color: string) => {
      const values = color.match(/[\d.]+/g)!.slice(0, 3).map(Number).map(value => color.startsWith('color(srgb') ? value : value / 255);
      return values.map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4).reduce((sum, value, index) => sum + value * [.2126, .7152, .0722][index], 0);
    };
    const contrast = (first: string, second: string) => (Math.max(luminance(first), luminance(second)) + .05) / (Math.min(luminance(first), luminance(second)) + .05);
    for (const color of colors) {
      expect(Math.max(contrast(color.fill, color.canvas), color.borderWidth > 0 ? contrast(color.border, color.canvas) : 0)).toBeGreaterThanOrEqual(3);
      expect(contrast(color.text, color.fill)).toBeGreaterThanOrEqual(4.5);
    }
  });
}

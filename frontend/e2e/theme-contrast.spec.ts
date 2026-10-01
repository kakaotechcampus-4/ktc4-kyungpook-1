import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

for (const theme of ['light', 'dark'] as const) {
  test(`${theme} keeps text, primary actions and selected controls readable`, async ({ page }) => {
    await loginAsDemo(page);
    await page.addInitScript((value) => localStorage.setItem('gitory.theme', value), theme);
    await page.goto('/cards');
    await expect(page.getByRole('button', { name: '레포에서 만들기' })).toBeVisible();
    const contrast = await page.evaluate(() => {
      const luminance = (color: string) => {
        const channels = color.match(/[\d.]+/g)!.slice(0, 3).map(Number).map((c) => c / 255).map((c) => c <= .04045 ? c / 12.92 : ((c + .055) / 1.055) ** 2.4);
        return .2126 * channels[0] + .7152 * channels[1] + .0722 * channels[2];
      };
      const ratio = (a: string, b: string) => {
        const pair = [luminance(a), luminance(b)].sort((x, y) => y - x);
        return (pair[0] + .05) / (pair[1] + .05);
      };
      const button = getComputedStyle(document.querySelector('.btn--primary')!);
      const root = getComputedStyle(document.documentElement);
      const title = getComputedStyle(document.querySelector('h1')!);
      const subtle = getComputedStyle(document.querySelector('.experience-item .c-2')!);
      const card = getComputedStyle(document.querySelector('.experience-item')!);
      const selected = getComputedStyle(document.querySelector('.menu-item[aria-current=page]')!);
      return { background: root.backgroundColor, title: ratio(title.color, root.backgroundColor), primary: ratio(button.color, button.backgroundColor),
        subtle: ratio(subtle.color, card.backgroundColor), navigation: ratio(selected.color, selected.backgroundColor) };
    });
    expect(contrast.background).toBe(theme === 'light' ? 'rgb(245, 243, 234)' : 'rgb(18, 20, 15)');
    for (const value of [contrast.title, contrast.primary, contrast.subtle, contrast.navigation]) expect(value).toBeGreaterThanOrEqual(4.5);
  });
}

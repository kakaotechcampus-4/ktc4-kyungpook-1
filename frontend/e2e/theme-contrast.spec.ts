import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

const readContrasts = (pairs: { text: string; surface: string }[]) => {
  const luminance = (color: string) => {
    const channels = color.match(/[\d.]+/g)!.slice(0, 3).map(Number).map((c) => c / 255).map((c) => c <= .04045 ? c / 12.92 : ((c + .055) / 1.055) ** 2.4);
    return .2126 * channels[0] + .7152 * channels[1] + .0722 * channels[2];
  };
  return pairs.map(({ text: foreground, surface: background }) => {
    const text = luminance(getComputedStyle(document.querySelector(foreground)!).color);
    const surface = luminance(getComputedStyle(document.querySelector(background)!).backgroundColor);
    return (Math.max(text, surface) + .05) / (Math.min(text, surface) + .05);
  });
};

for (const theme of ['light', 'dark'] as const) {
  test(`${theme} keeps text, primary actions and selected controls readable`, async ({ page }) => {
    await loginAsDemo(page);
    await page.addInitScript((value) => localStorage.setItem('gitory.theme', value), theme);
    await page.goto('/cards');
    await expect(page.getByRole('button', { name: '레포에서 만들기' })).toBeVisible();
    const palette = await page.evaluate(() => {
      const button = getComputedStyle(document.querySelector('.btn--primary')!);
      const root = getComputedStyle(document.documentElement);
      const card = getComputedStyle(document.querySelector('.experience-item')!);
      const selected = getComputedStyle(document.querySelector('.menu-item[aria-current=page]')!);
      return { background: root.backgroundColor, action: button.backgroundColor, selection: selected.backgroundColor, surface: card.backgroundColor };
    });
    expect(palette.background).toBe(theme === 'light' ? 'rgb(242, 247, 244)' : 'rgb(18, 20, 15)');
    expect(palette.action).toBe(theme === 'light' ? 'rgb(70, 112, 78)' : 'rgb(168, 196, 124)');
    expect(palette.selection).toBe(theme === 'light' ? 'rgb(223, 237, 227)' : 'rgb(31, 39, 25)');
    expect(palette.surface).toBe(theme === 'light' ? 'rgb(255, 255, 255)' : 'rgb(26, 29, 22)');
    const contrasts = await page.evaluate(readContrasts, [
      { text: 'h1', surface: 'html' }, { text: '.btn--primary', surface: '.btn--primary' },
      { text: '.experience-item .c-2', surface: '.experience-item' },
      { text: '.menu-item[aria-current=page]', surface: '.menu-item[aria-current=page]' },
    ]);
    for (const value of contrasts) expect(value).toBeGreaterThanOrEqual(4.5);

    await page.goto('/cards/card_01?versions=1');
    await expect(page.locator('.modal .card--selected .c-3')).toBeVisible();
    const [versionDateContrast] = await page.evaluate(readContrasts, [{ text: '.modal .card--selected .c-3', surface: '.modal .card--selected' }]);
    expect(versionDateContrast).toBeGreaterThanOrEqual(4.5);
  });
}

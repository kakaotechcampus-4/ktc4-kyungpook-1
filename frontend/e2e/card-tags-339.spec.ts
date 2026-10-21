import { test, expect } from '@playwright/test';
import { loginAsDemo } from './helpers';

for (const width of [320, 390, 768, 1440]) for (const theme of ['light', 'dark']) {
  test(`card tags list/filter/detail ${width} ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.addInitScript(value => localStorage.setItem('gitory.theme', value), theme);
    await loginAsDemo(page);
    await page.goto('/cards');
    const cards = page.locator('.experience-item');
    await expect(cards).toHaveCount(5);
    // Untagged cards remain visible, without an invented tag or empty pill.
    await expect(cards.filter({ hasText: '팀원과 토큰 저장 위치로 갈린 경험' }).locator('.card-tags')).toHaveCount(0);
    await page.getByRole('combobox', { name: '분야', exact: true }).click();
    await page.getByRole('option', { name: 'Backend', exact: true }).click();
    await expect(cards).toHaveCount(1);
    await page.getByRole('combobox', { name: '기술', exact: true }).click();
    await page.getByRole('option', { name: 'React', exact: true }).click();
    await expect(page.getByText('조건에 맞는 카드가 없어요')).toBeVisible();
    await page.getByRole('combobox', { name: '기술', exact: true }).click();
    await page.getByRole('option', { name: 'Java', exact: true }).click();
    await expect(cards).toHaveCount(1);
    await expect(cards.locator('.card-tags')).toContainText('Backend');
    await expect(cards.locator('.card-tags')).toContainText('Java');
    const taggedCard = cards.first();
    const tagBox = await taggedCard.locator('.card-tags').boundingBox();
    const titleBox = await taggedCard.locator('.gcard__title').boundingBox();
    expect(tagBox!.y + tagBox!.height).toBeLessThanOrEqual(titleBox!.y);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await page.screenshot({ path: `docs/screenshots/2026-10-21-tags/list-${width}-${theme}.png`, fullPage: true });
    await cards.click();
    await expect(page.locator('.card-page-header .card-tags')).toHaveText(['BackendJava']);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await page.screenshot({ path: `docs/screenshots/2026-10-21-tags/detail-${width}-${theme}.png`, fullPage: true });
    await page.goBack();
    await expect(cards).toHaveCount(5);
  });
}

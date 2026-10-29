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
    // 분야·기술 선택 칸 대신 "기술별"로 묶어 본다(팀 피드백 10/26). 태그 없는 카드는 지어내지 않고 "기술 정보 없음" 묶음에
    await page.getByRole('radio', { name: '기술별', exact: true }).click();
    const java = page.getByRole('region', { name: 'Java', exact: true });
    await expect(java.locator('.experience-item')).toHaveCount(1);
    await expect(page.getByRole('region', { name: '기술 정보 없음', exact: true }).locator('.experience-item')).toHaveCount(4);
    await expect(java.locator('.card-tags')).toContainText('Backend');
    await expect(java.locator('.card-tags')).toContainText('Java');
    const taggedCard = java.locator('.experience-item').first();
    const tagBox = await taggedCard.locator('.card-tags').boundingBox();
    const titleBox = await taggedCard.locator('.gcard__title').boundingBox();
    expect(tagBox!.y + tagBox!.height).toBeLessThanOrEqual(titleBox!.y);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await page.screenshot({ path: `docs/screenshots/2026-10-21-tags/list-${width}-${theme}.png`, fullPage: true });
    await taggedCard.click();
    await expect(page.locator('.card-page-header .card-tags')).toHaveText(['BackendJava']);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await page.screenshot({ path: `docs/screenshots/2026-10-21-tags/detail-${width}-${theme}.png`, fullPage: true });
    await page.goBack();
    await expect(cards).toHaveCount(5);
  });
}

import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

for (const width of [320, 390, 768, 1440]) for (const theme of ['light', 'dark']) {
  test(`card deletion confirmation and candidate recovery ${width} ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.addInitScript(value => localStorage.setItem('gitory.theme', value), theme);
    await loginAsDemo(page);
    await page.goto('/cards');
    await expect(page.locator('.experience-item')).toHaveCount(5);
    const remove = page.getByRole('button', { name: '로그인 세션 처리 삭제', exact: true });
    const bounds = await remove.boundingBox();
    expect(bounds!.width).toBeGreaterThanOrEqual(44);
    expect(bounds!.height).toBeGreaterThanOrEqual(44);
    await remove.click();
    await expect(page).toHaveURL(/\/cards$/);
    const dialog = page.getByRole('dialog', { name: '카드를 삭제할까요?' });
    await expect(dialog).toContainText('후보 목록에서 다시 골라');
    await dialog.getByRole('button', { name: '취소', exact: true }).click();
    await expect(page.locator('.experience-item')).toHaveCount(5);
    await remove.focus();
    await page.keyboard.press('Enter');
    await expect(dialog).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await page.screenshot({ path: `docs/screenshots/2026-10-23-delete/confirm-${width}-${theme}.png`, animations: 'disabled' });
    await dialog.getByRole('button', { name: '삭제', exact: true }).click();
    await expect(page.locator('.experience-item')).toHaveCount(4);
    const exists = await page.evaluate(async () => (await fetch('/api/cards/card_01')).status);
    expect(exists).toBe(404);
    await page.screenshot({ path: `docs/screenshots/2026-10-23-delete/list-${width}-${theme}.png`, fullPage: true });
  });
}

test('confirmed detail uses the shared dialog and leaves the deleted card', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/cards/card_03');
  await page.getByRole('button', { name: '카드 삭제', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('매칭 결과에서도 빠져요');
  await dialog.getByRole('button', { name: '삭제', exact: true }).click();
  await expect(page).toHaveURL(/\/cards$/);
  await expect(page.locator('.experience-item')).toHaveCount(4);
});

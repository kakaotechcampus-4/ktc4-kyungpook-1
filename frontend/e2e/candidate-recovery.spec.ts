import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

test('failed manual candidate submission retains the draft in its dialog', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await loginAsDemo(page);
  await page.goto('/repos/r_auth/candidates?demoScenario=candidate-error');
  await page.getByRole('button', { name: '+ 직접 추가', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('textbox', { name: '후보 이름' }).fill('직접 만든 배포 도구');
  await dialog.getByRole('textbox', { name: '어떤 일을 했나요' }).fill('배포 확인 도구를 만들었다');
  await dialog.getByRole('button', { name: '후보 추가', exact: true }).click();
  await expect(page.locator('.toast--danger')).toBeVisible();
  await expect(dialog.getByRole('textbox', { name: '후보 이름' })).toHaveValue('직접 만든 배포 도구');
  await dialog.getByRole('button', { name: '후보 추가', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.locator('.cand-tile__title').filter({ hasText: '직접 만든 배포 도구' })).toBeVisible();
  expect(errors).toEqual([]);
});

test('mobile selection survives a refresh', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 });
  await loginAsDemo(page);
  await page.goto('/repos/r_auth/candidates');
  const tile = page.locator('.cand-tile__pick').first();
  await tile.click();
  await expect(tile).toHaveAttribute('aria-pressed', 'true');
  await page.reload();
  await expect(tile).toHaveAttribute('aria-pressed', 'true');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('failed card generation preserves selection for deliberate retry', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await loginAsDemo(page);
  await page.goto('/repos/r_auth/candidates?demoScenario=candidate-error');
  const tile = page.locator('.cand-tile__pick').first();
  await tile.click();
  await page.getByRole('button', { name: '카드 만들기', exact: true }).click();
  await expect(page.locator('.toast')).toContainText('잠시 뒤 다시 시도해 주세요'); // 5xx: 연결이 아니라 서버가 처리하지 못한 것
  await expect(tile).toHaveAttribute('aria-pressed', 'true');
  await expect(page).toHaveURL(/candidates/);
  await page.getByRole('button', { name: '카드 만들기', exact: true }).click();
  await expect(page).toHaveURL(/\/cards\//);
  expect(errors).toEqual([]);
});

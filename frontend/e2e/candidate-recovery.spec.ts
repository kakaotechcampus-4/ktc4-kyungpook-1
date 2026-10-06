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
  await dialog.getByRole('textbox', { name: '무엇을 한 작업인가요' }).fill('배포 확인 도구를 만들었다');
  await dialog.getByRole('button', { name: '후보 추가', exact: true }).click();
  await expect(page.locator('.toast--danger')).toBeVisible();
  await expect(dialog.getByRole('textbox', { name: '후보 이름' })).toHaveValue('직접 만든 배포 도구');
  await dialog.getByRole('button', { name: '후보 추가', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.locator('.cand__title').filter({ hasText: '직접 만든 배포 도구' })).toBeVisible();
  expect(errors).toEqual([]);
});

test('mobile search reset preserves selection and refresh restores it', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 });
  await loginAsDemo(page);
  await page.goto('/repos/r_auth/candidates');
  const checkbox = page.getByRole('checkbox', { disabled: false }).first();
  await checkbox.check();
  await page.getByRole('textbox', { name: '후보 검색 (제목·추천 이유)' }).fill('일치하지 않는 검색');
  await page.getByRole('button', { name: '검색 초기화' }).click();
  await expect(checkbox).toBeChecked();
  await page.reload();
  await expect(checkbox).toBeChecked();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('failed exclusion keeps selection and has no false success or unhandled rejection', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await loginAsDemo(page);
  await page.goto('/repos/r_auth/candidates?demoScenario=candidate-error');
  const row = page.locator('.cand').filter({ has: page.getByRole('checkbox', { disabled: false }) }).first();
  await row.getByRole('checkbox').check();
  await row.getByRole('button', { name: '제외', exact: true }).click();
  await expect(page.locator('.toast')).toContainText('연결');
  await expect(row.getByRole('checkbox')).toBeChecked();
  await expect(page.getByRole('button', { name: '실행 취소' })).toHaveCount(0);
  await row.getByRole('button', { name: '제외', exact: true }).click();
  await expect(page.getByRole('button', { name: '실행 취소' })).toBeVisible();
  expect(errors).toEqual([]);
});

test('failed card generation preserves selection for deliberate retry', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await loginAsDemo(page);
  await page.goto('/repos/r_auth/candidates?demoScenario=candidate-error');
  const checkbox = page.getByRole('checkbox', { disabled: false }).first();
  await checkbox.check();
  await page.getByRole('button', { name: '선택한 1개로 카드 만들기' }).click();
  await expect(page.locator('.toast')).toContainText('연결');
  await expect(checkbox).toBeChecked();
  await expect(page).toHaveURL(/candidates/);
  await page.getByRole('button', { name: '선택한 1개로 카드 만들기' }).click();
  await expect(page).toHaveURL(/\/cards\//);
  expect(errors).toEqual([]);
});

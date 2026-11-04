import { test, expect } from '@playwright/test';
import { loginAsDemo } from './helpers';

test('내 조건으로 맞춰 보기 — 진행을 보이다가 맞춰 본 공고를 상자의 타일로 올리고, 타일을 누르면 공고 상세로 간다', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/match');
  await page.getByRole('button', { name: '내 조건으로 맞춰 보기', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: '곳을 확인했어요' })).toBeVisible();
  await expect(page.getByRole('button', { name: '맞춰 보는 중', exact: true })).toBeDisabled();

  await expect(page.getByRole('button', { name: '다시 맞춰 보기', exact: true })).toBeEnabled({ timeout: 15_000 });
  const box = page.getByRole('region', { name: '내 카드로 맞춰 본 공고' });
  await expect(box.getByText(/확정 카드 \d+장과 인적사항으로 기술이 겹치는 15곳을 확인했어요$/)).toBeVisible();
  await box.locator('.match-card__link').first().click();
  await expect(page).toHaveURL(/\/match\/[^/]+$/);
});

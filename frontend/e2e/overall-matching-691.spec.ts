import { test, expect } from '@playwright/test';
import { loginAsDemo } from './helpers';

test('내 조건으로 매칭하기 — 진행을 보이다가 나와 맞는 기업을 보이고, 기업을 누르면 대표 공고 상세로 간다', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/match');
  const box = page.getByRole('region', { name: '나와 맞는 기업' });
  await box.getByRole('button', { name: '내 조건으로 매칭하기', exact: true }).click();
  await expect(box.getByRole('status')).toContainText('곳을 확인했어요');
  await expect(box.getByRole('button', { name: '매칭하는 중', exact: true })).toBeDisabled();

  await expect(box.getByRole('button', { name: '다시 매칭하기', exact: true })).toBeEnabled({ timeout: 15_000 });
  const companies = box.getByRole('listitem');
  await expect(companies).toHaveCount(5);
  await expect(box).toContainText('마지막 매칭');
  await companies.first().getByRole('link').click();
  await expect(page).toHaveURL(/\/match\/[^/]+$/);
});

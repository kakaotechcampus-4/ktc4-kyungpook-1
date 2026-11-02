import { test, expect } from '@playwright/test';
import { loginAsDemo } from './helpers';

test('공고 상세에서 마감일을 캘린더에 담으면 홈 캘린더에 뜨고, 누르면 그 공고로 돌아간다', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/match');
  await page.locator('a[href^="/match/"]').filter({ hasText: /엔지니어|개발자/ }).first().click();
  await expect(page).toHaveURL(/\/match\/[^/?]+/);
  const detail = new URL(page.url()).pathname;
  await page.getByRole('button', { name: '캘린더에 담기', exact: true }).click();
  await expect(page.getByRole('button', { name: '캘린더에서 빼기', exact: true })).toBeVisible();

  await page.goto('/');
  const events = page.locator('.job-calendar .calendar-event');
  await expect(events).toHaveCount(1);
  await events.first().click();
  await expect(page).toHaveURL(detail);
  await page.getByRole('button', { name: '캘린더에서 빼기', exact: true }).click();
  await page.goto('/');
  await expect(page.locator('.job-calendar .calendar-event')).toHaveCount(0);
});

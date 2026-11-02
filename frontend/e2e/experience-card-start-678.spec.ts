import { test, expect } from '@playwright/test';
import { loginAsDemo } from './helpers';

test('경험 카드 만들기 — 제목만 적고 질문 받으며 쓰기를 누르면 카드를 만들고 상황 칸부터 묻는다', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/cards/new');
  await expect(page.getByRole('heading', { name: '경험 카드 만들기', exact: true })).toBeVisible();
  await page.getByRole('textbox', { name: '카드 제목', exact: true }).fill('동아리 축제 예약 웹 중복 예약 막기');
  await page.getByRole('button', { name: '질문 받으며 쓰기', exact: true }).click();

  await expect(page).toHaveURL(/\/cards\/[^/]+\/interview$/);
  await expect(page.locator('.star-read__row--now')).toContainText('상황');
  await expect(page.getByRole('textbox', { name: '답변', exact: true })).toBeVisible();
});

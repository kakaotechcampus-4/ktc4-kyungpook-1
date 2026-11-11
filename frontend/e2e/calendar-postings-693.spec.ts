import { test, expect } from '@playwright/test';
import { loginAsDemo } from './helpers';

test('공고 매칭 화면의 캘린더는 마감일이 있는 공고를 스스로 띄우고, 일정을 누르면 그 공고로 간다', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/match');
  await expect(page.locator('.job-calendar .calendar-day').first()).toBeVisible();
  // 샘플 공고 가운데 마감일이 11월인 공고(나래랩스 11-07)가 11월 달력에 있고, 마감일 없는 상시 공고(다온페이)는 없다
  const calendar = page.locator('.job-calendar');
  for (let i = 0; i < 24 && !(await calendar.getByText('2026년 11월').count()); i += 1) await calendar.getByRole('button', { name: '이전 달' }).click();
  await expect(calendar.getByText('2026년 11월')).toBeVisible();
  const event = page.locator('.job-calendar .calendar-event').filter({ hasText: '나래랩스' });
  await expect(event).toHaveCount(1);
  await expect(page.locator('.job-calendar .calendar-event').filter({ hasText: '다온페이' })).toHaveCount(0);
  await event.click();
  await expect(page).toHaveURL(/\/match\/8a1e5c34-2f7b-4d90-b6c3-5e2f9a7d3b02$/);
  // 상세에는 더 이상 캘린더에 담는 버튼이 없다 — 마감일이 있으면 이미 달력에 있다
  await expect(page.getByRole('button', { name: '캘린더에 담기', exact: true })).toHaveCount(0);
});

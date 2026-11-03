import { test, expect } from '@playwright/test';
import { loginAsDemo } from './helpers';

test('공고 상세에서 마감일을 캘린더에 담으면 홈 캘린더에 뜨고, 누르면 그 공고로 돌아간다', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/match');
  await page.locator('a[href^="/match/"]').filter({ hasText: /엔지니어|개발자/ }).first().click();
  await expect(page).toHaveURL(/\/match\/[^/?]+/);
  const detail = new URL(page.url()).pathname;
  await page.getByRole('button', { name: '캘린더에 담기', exact: true }).click();
  await expect(page.getByRole('button', { name: '캘린더에서 빼기', exact: true })).toBeVisible();
  // 담은 일정(기업 이름·날짜)은 브라우저에 저장된다 — 홈 캘린더에는 추천 공고 마감일도 함께 뜰 수 있어 이 일정 하나를 찾아 본다
  const saved = await page.evaluate(() => {
    const key = Object.keys(localStorage).find(k => k.startsWith('gitory.job-calendar.v1.'))!;
    return (JSON.parse(localStorage.getItem(key)!) as { company: string; date: string }[])[0];
  });

  await page.goto('/');
  const event = page.locator('.job-calendar .calendar-event').filter({ hasText: saved.company });
  await expect(event).toHaveCount(1);
  // 그 공고의 마감 날짜 칸에 있다
  const day = page.locator('.job-calendar .calendar-day').filter({ has: page.locator('.calendar-event').filter({ hasText: saved.company }) });
  await expect(day.locator('.calendar-day__number')).toHaveText(String(Number(saved.date.slice(8))));
  await event.click();
  await expect(page).toHaveURL(detail);
  await page.getByRole('button', { name: '캘린더에서 빼기', exact: true }).click();
  await page.goto('/');
  await expect(page.locator('.job-calendar .calendar-day').first()).toBeVisible();
  await expect(page.locator('.job-calendar .calendar-event').filter({ hasText: saved.company })).toHaveCount(0);
});

import { test, expect } from '@playwright/test';
import { loginAsDemo } from './helpers';

for (const width of [320, 390, 1440]) test(`weak candidates search, manual selection and collapse ${width}`, async ({ page }) => {
  await page.setViewportSize({ width, height: 900 });
  await loginAsDemo(page);
  await page.goto('/repos/r_auth/candidates');
  const weak = await page.evaluate(async () => {
    const response = await (await fetch('/api/repos/r_auth/candidates')).json();
    return response.data.candidates.find((c: { weak: boolean; status: string }) => c.weak && c.status === 'NEW') as { title: string };
  });
  const group = page.getByRole('button', { name: /더 보기 후보 \d+개/ });
  await expect(group).toHaveAttribute('aria-expanded', 'false');
  const checkbox = page.getByRole('checkbox', { name: `${weak.title} 선택`, exact: true });
  await expect(checkbox).toHaveCount(0);
  await page.getByRole('button', { name: '추천 후보 선택', exact: true }).click();
  await group.click();
  await expect(checkbox).toHaveAttribute('aria-checked', 'false');
  // 추천 후보 선택이 8개를 채워 두면 더 고를 수 없어서, 접힌 후보를 고르는 동작은 선택을 비운 뒤 본다
  await page.getByRole('button', { name: '선택 해제' }).click();
  await checkbox.click();
  await group.click();
  await expect(group).toContainText('1개 선택');
  await page.getByPlaceholder('후보 검색 (제목·추천 이유)').fill(weak.title);
  await expect(checkbox).toBeVisible();
  await expect(checkbox).toHaveAttribute('aria-checked', 'true');
  await expect(page.locator('.record-list')).toHaveCount(1);
  await page.getByPlaceholder('후보 검색 (제목·추천 이유)').blur();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
  await page.screenshot({ path: `docs/screenshots/2026-10-21-followups/candidates-${width}.png`, fullPage: true });
});

test('created cards keep only the new batch as their navigation order', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/repos/r_auth/candidates');
  await page.getByRole('button', { name: '추천 후보 선택', exact: true }).click();
  await page.getByRole('button', { name: /선택한 \d+개로 카드 만들기/ }).click();
  await expect(page).toHaveURL(/\/cards\//);
  const order = await page.evaluate(() => window.history.state.usr.cardOrder as string[]);
  expect(order.length).toBeGreaterThan(1);
  expect(page.url()).toContain(`/cards/${order[0]}`);
  await expect(page.getByRole('link', { name: /다음 카드/ })).toBeVisible({ timeout: 30000 });
  await expect(page.getByRole('link', { name: /다음 카드/ })).toHaveAttribute('href', `/cards/${order[1]}`);
});

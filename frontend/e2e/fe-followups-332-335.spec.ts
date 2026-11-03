import { test, expect } from '@playwright/test';
import { loginAsDemo } from './helpers';

for (const width of [320, 390, 1440]) test(`weak candidate picked from the rest stays picked after folding ${width}`, async ({ page }) => {
  await page.setViewportSize({ width, height: 900 });
  await loginAsDemo(page);
  await page.goto('/repos/r_auth/candidates');
  const weak = await page.evaluate(async () => {
    const response = await (await fetch('/api/repos/r_auth/candidates')).json();
    return response.data.candidates.find((c: { weak: boolean; status: string }) => c.weak && c.status === 'NEW') as { title: string };
  });
  const tile = page.locator('.cand-tile__pick').filter({ hasText: weak.title });
  await expect(tile).toHaveCount(0);
  await page.getByRole('button', { name: /^나머지 \d+개 더 보기$/ }).click();
  await tile.click();
  await expect(tile).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('button', { name: '처음 5개만 보기' }).click();
  await expect(tile).toHaveCount(0);
  await expect(page.locator('.sticky-footer__text')).toContainText('1개 골랐어요');
  await page.getByRole('button', { name: /^나머지 \d+개 더 보기$/ }).click();
  await expect(tile).toHaveAttribute('aria-pressed', 'true');
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
  await page.screenshot({ path: `docs/screenshots/2026-10-21-followups/candidates-${width}.png`, fullPage: true });
});

test('created cards open with only the new batch as their order, then land on the repo folder once all are written', async ({ page }) => {
  test.setTimeout(120_000); // 데모는 카드를 한 장씩 차례로 쓰고(#617) 마지막 카드까지 펼친 뒤에 넘어간다
  await loginAsDemo(page);
  await page.goto('/repos/r_auth/candidates');
  for (const index of [0, 1]) await page.locator('.cand-tile__pick').nth(index).click();
  await page.getByRole('button', { name: '카드 만들기', exact: true }).click();
  await expect(page).toHaveURL(/\/cards\//);
  const order = await page.evaluate(() => window.history.state.usr.cardOrder as string[]);
  expect(order.length).toBeGreaterThan(1);
  expect(page.url()).toContain(`/cards/${order[0]}`);
  // 여러 장을 다 쓰면 그 레포의 카드 폴더로 옮겨 여러 장을 한눈에 본다 (#704)
  await expect(page).toHaveURL(/\/cards\/repo\/r_auth$/, { timeout: 90_000 });
});

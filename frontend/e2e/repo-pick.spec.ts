import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

/** 맞는 기업 고르기 화면 — 목 서버가 후보를 하나씩 확인하는 모습(20초쯤)을 두 폭에서 끝까지 본다 */
for (const width of [1440, 390]) {
  test(`맞는 기업 고르기 화면은 ${width} 폭에서 가로로 밀리지 않고, 다 고르면 폴더로 가기가 보인다`, async ({ page }) => {
    test.setTimeout(90_000);
    await page.setViewportSize({ width, height: 900 });
    await loginAsDemo(page);
    await page.goto('/cards/repo/r_auth/match');

    await expect(page.getByRole('heading', { level: 1, name: /^맞는 기업을 (곧 )?고르/ })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);

    await expect(page.getByRole('link', { name: '폴더로 가기', exact: true })).toBeVisible({ timeout: 45_000 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
  });
}

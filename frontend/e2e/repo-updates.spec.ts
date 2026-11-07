import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

const SHOTS = 'docs/screenshots/2026-11-06-auto-refresh';
const cases = [
  { key: 'pc', width: 1440, theme: 'light', pick: true },
  { key: 'pc-dark', width: 1440, theme: 'dark', pick: false },
  { key: 'mo', width: 390, theme: 'light', pick: true },
] as const;

/** 서버가 다시 읽어 카드 초안을 써 둔 레포가 홈에 보이고, 카드 확인하기로 새 카드를 넣어 다시 고르는 모습(20초쯤)을 끝까지 본다 */
for (const { key, width, theme, pick } of cases) {
  test(`다시 읽은 레포 알림에서 다시 고르는 화면까지 ${key}`, async ({ page }) => {
    test.setTimeout(90_000);
    await page.setViewportSize({ width, height: 900 });
    await page.addInitScript((value) => localStorage.setItem('gitory.theme', value), theme);
    await loginAsDemo(page);
    await page.goto('/?demoScenario=repo-update');

    const notice = page.getByRole('region', { name: '새로 써 둔 카드' });
    await expect(notice).toContainText('hong-dev/auth-service에서 새로 한 일로 카드 3장을 써 뒀어요');
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await page.screenshot({ path: `${SHOTS}/home-${key}.png`, animations: 'disabled' });
    if (!pick) return;

    await notice.getByRole('link', { name: '카드 확인하기' }).click();
    await expect(page).toHaveURL(/\/cards\/repo\/r_auth\/match\?again=1&cards=3$/);
    await expect(page.getByRole('heading', { level: 1, name: '새 카드 3장을 더해 다시 고르고 있어요' })).toBeVisible();
    await expect(page.getByRole('heading', { level: 1, name: '새 카드 3장을 더해 다시 골랐어요' })).toBeVisible({ timeout: 45_000 });
    await expect(page.getByText('새로 들어옴')).toHaveCount(1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    // 고른 기업 타일이 제 고리로 옮겨 가는 동안 찍으면 타일이 겹쳐 보인다
    await page.waitForTimeout(1500);
    await page.screenshot({ path: `${SHOTS}/pick-again-${key}.png`, fullPage: true, animations: 'disabled' });

    await page.goto('/');
    await expect(page.getByRole('heading', { level: 1, name: '지원할 곳' })).toBeVisible();
    await expect(notice).toHaveCount(0);
  });
}

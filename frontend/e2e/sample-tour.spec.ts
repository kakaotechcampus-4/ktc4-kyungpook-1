import { expect, test } from '@playwright/test';

/** freshSeed 는 페이지를 열 때마다 로그인 표시를 지우므로 쓰지 않는다 — 둘러보기가 켠 표시는 새로고침 뒤에도 남아야 한다 */
const seedOnce = () => {
  try {
    if (!sessionStorage.getItem('gitory.e2e.seeded')) {
      sessionStorage.removeItem('gitory.demo.db');
      sessionStorage.removeItem('gitory.demo.session');
      sessionStorage.setItem('gitory.e2e.seeded', '1');
    }
  } catch { /* 시크릿 모드 등 */ }
};

test('랜딩에서 둘러보기를 누르면 띠가 뜬 홈으로 가고, 나가기로 로그인 화면에 돌아온다', async ({ page }) => {
  await page.addInitScript(seedOnce);
  await page.goto('/login');
  await page.getByRole('button', { name: '둘러보기' }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByText('샘플 데이터로 둘러보는 중이에요. 실제 분석이 아니에요')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole('search')).toBeVisible();
  await page.getByRole('button', { name: '나가기' }).click();
  await expect(page).toHaveURL(/\/login/);
  await expect(page.getByText('샘플 데이터로 둘러보는 중이에요')).toHaveCount(0);
});

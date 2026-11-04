import { expect, test, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

const SHOTS = 'docs/screenshots/2026-11-04-repo-groups';
const TITLES = ['내 기여가 많은 레포', '아직 정리하지 않은 레포', '이미 정리한 레포'];
const namesIn = (page: Page, title: string) => page.getByRole('region', { name: title }).locator('.repo-row__name').allTextContents();

for (const width of [1440, 390]) for (const theme of ['light', 'dark'] as const) {
  test(`레포 목록은 세 묶음이고 정리한 레포는 링크 줄이며 가로로 넘치지 않는다 ${width} ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.addInitScript((value) => localStorage.setItem('gitory.theme', value), theme);
    await loginAsDemo(page);
    await page.goto('/repos');

    await expect(page.getByRole('heading', { level: 2 })).toHaveText(TITLES);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    const done = page.getByRole('region', { name: TITLES[2] });
    await expect(done.getByRole('radio')).toHaveCount(0);
    await expect(done.getByRole('link', { name: '후보 보기' }).first()).toHaveAttribute('href', '/repos/r_auth/candidates');
    await expect(done.getByRole('link', { name: '다시 정리' }).first()).toHaveAttribute('href', '/repos?select=r_auth&disclose=1');
    for (const link of await done.getByRole('link').all()) expect((await link.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    await page.screenshot({ path: `${SHOTS}/repos-${width}-${theme}.png`, fullPage: true, animations: 'disabled' });

    if (width === 1440 && theme === 'light') {
      await done.getByRole('link', { name: '다시 정리' }).first().click();
      await expect(page).toHaveURL(/\/repos\?select=r_auth&disclose=1$/);
      const dialog = page.getByRole('dialog', { name: '읽는 범위 확인' });
      await expect(dialog.getByText('전에 정리한 레포예요')).toBeVisible();
      await expect(dialog.getByRole('button', { name: '정리 시작' })).toBeEnabled(); // 읽는 범위를 다 받은 뒤의 모습을 찍는다
      await page.screenshot({ path: `${SHOTS}/again-1440-light.png`, animations: 'disabled' });
    }
  });
}

test('첫째·둘째 묶음에 걸쳐 레포는 하나만 골라지고, 다음을 누르면 그 레포의 읽는 범위 확인 창이 열린다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/repos');
  await page.getByRole('radio', { name: /web-client/ }).click();
  await page.getByRole('radio', { name: /team-board/ }).click();

  await expect(page.locator('[role="radio"][aria-checked="true"]')).toHaveCount(1);
  await expect(page.getByRole('radio', { name: /team-board/ })).toHaveAttribute('aria-checked', 'true');
  await page.getByRole('button', { name: '다음', exact: true }).click();
  await expect(page.getByRole('dialog', { name: '읽는 범위 확인' })).toContainText('kbu-capstone / team-board');
});

test('정렬은 묶음 안에서만 바뀌고 묶음 순서는 그대로다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/repos');
  await expect(page.getByRole('heading', { level: 2 })).toHaveText(TITLES);
  expect(await namesIn(page, TITLES[0])).toEqual(['big-monorepo', 'web-client', 'algorithm-study']);   // 활동량 순

  await page.getByRole('combobox', { name: '정렬', exact: true }).click();
  await page.getByRole('option', { name: '이름 순', exact: true }).click();

  await expect(page.getByRole('heading', { level: 2 })).toHaveText(TITLES);
  expect(await namesIn(page, TITLES[0])).toEqual(['algorithm-study', 'big-monorepo', 'web-client']);
  expect(await namesIn(page, TITLES[1])).toEqual(['data-pipeline', 'legacy-monolith', 'side-project-new', 'team-board']);
  expect(await namesIn(page, TITLES[2])).toEqual(['auth-service', 'free-tier-sleep']);
});

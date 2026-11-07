import { expect, test, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

/** 오른쪽 위 알림 자리 — 목 서버에 알림 두 개를 넣고 숫자 → 열기 → 이동 → 숫자 줄어듦을 따라간다 */
const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();
const seedTwo = async (page: Page) => {
  await page.goto('/');
  await page.evaluate(async (items) => {
    await fetch('/api/__notices', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ items }) });
  }, [
    { kind: 'CARDS_DRAFTED', repoId: 'r_auth', repoName: 'team/auth', cardCount: 3, cardTitles: ['로그인 세션 처리', '토큰 재발급'], createdAt: ago(10) },
    { kind: 'ANALYSIS_FAILED', repoId: 'r_board', repoName: 'team/board', jobId: 'job_9', errorCode: 'GITHUB_UNAUTHORIZED', createdAt: ago(90) },
  ]);
  await page.reload();
};
const bell = (page: Page) => page.getByRole('button', { name: /^알림/ });
const overflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);

test('알림 버튼의 숫자를 보고 열어서 한 알림의 버튼으로 이동하면 숫자가 줄어든다', async ({ page }) => {
  await loginAsDemo(page);
  await seedTwo(page);
  await expect(bell(page)).toHaveAccessibleName('알림, 안 읽은 알림 2개');

  await bell(page).click();
  const panel = page.getByRole('dialog', { name: '알림' });
  await expect(panel.getByText('team/auth의 카드 3장을 다 썼어요')).toBeVisible();
  await expect(panel.getByText('team/board를 읽다가 멈췄어요')).toBeVisible();

  await panel.getByRole('button', { name: '보기', exact: true }).click();
  await expect(page).toHaveURL(/\/cards\/repo\/r_auth$/);
  await expect(panel).toBeHidden();
  await expect(bell(page)).toHaveAccessibleName('알림, 안 읽은 알림 1개');
});

test('휴대폰 폭에서는 위쪽 줄에 들어가고 목록을 열어도 가로로 넘치지 않는다', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await loginAsDemo(page);
  await seedTwo(page);
  const header = page.locator('.mobile-brand');
  await expect(header.getByRole('button', { name: /^알림/ })).toBeVisible();
  await bell(page).click();
  await expect(page.getByRole('dialog', { name: '알림' })).toBeVisible();
  expect(await overflow(page)).toBeLessThanOrEqual(0);
});

import { expect, test } from '@playwright/test';
import { freshSeed, loginAsDemo } from './helpers';

test('failed reads show unknown counts while a confirmed empty response shows zero', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/cards?demoScenario=read-error');
  await expect(page.getByRole('heading', { name: '서버에 연결하지 못했어요' })).toBeVisible();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('경험 카드 —');
  await expect(page.getByRole('button', { name: '전체 —', exact: true })).toBeVisible();
  await expect(page.locator('.query-failure')).not.toContainText('입력한 내용');
  await page.goto('/cards?demoScenario=empty');
  await expect(page.getByText('아직 카드가 없어요', { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('경험 카드 0');
});

test('drafts lead to answering missing fields while confirmation remains available', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/cards/card_01');
  const next = page.locator('.sticky-footer .btn--primary');
  await expect(next).toHaveText('답변으로 보완');
  await expect(page.getByRole('button', { name: '확정', exact: true })).toBeEnabled();
  await next.click();
  await expect(page).toHaveURL(/\/interview\?field=T$/);
  await expect(page.getByRole('textbox', { name: '답변', exact: true })).toBeVisible();
});

test('field edit has a phone-sized target and focuses the requested field', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await loginAsDemo(page); await page.goto('/cards/card_01');
  const edit = page.getByRole('region', { name: '행동 (Action)', exact: true }).getByRole('button', { name: '행동 수정', exact: true });
  await expect(edit).toBeVisible();
  expect((await edit.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  await edit.click();
  await expect(page.getByRole('textbox', { name: '행동 (Action)', exact: true })).toBeFocused();
  await expect(page).toHaveURL(/mode=edit&field=A/);
});

test('mobile landing shows the output before another screen and expands complete examples', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await freshSeed(page); await page.goto('/login?reason=expired');
  const output = page.getByLabel('경험 카드 예시');
  expect((await output.boundingBox())!.y).toBeLessThan(650);
  await expect(page.locator('.landing-card__field[data-slot=T]')).toBeHidden();
  await page.getByRole('button', { name: '전체 예시 보기', exact: true }).click();
  await expect(page.locator('.landing-card__field[data-slot=T]')).toBeVisible();
  await expect(page.locator('.github-snapshot__commits li')).toHaveCount(3);
});

test('returning home starts with drafts and keeps one entry for each new-card action', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await loginAsDemo(page); await page.goto('/');
  await expect(page.locator('.home-resume')).toBeVisible();
  const resume = (await page.locator('.home-resume').boundingBox())!;
  const start = (await page.locator('.prompt').boundingBox())!;
  expect(resume.y).toBeLessThan(start.y);
  const main = page.locator('main');
  await expect(main.getByRole('link', { name: '직접 작성', exact: true })).toHaveCount(1);
  await expect(main.getByRole('link', { name: '레포 고르기', exact: true })).toHaveCount(1);
});

test('settings prioritizes controls and keeps storage details available on demand', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await loginAsDemo(page); await page.goto('/settings');
  const dark = page.getByRole('radio', { name: '다크', exact: true });
  expect((await dark.boundingBox())!.y).toBeLessThan(500);
  await expect(page.getByText('Free Plan', { exact: true })).toHaveCount(0);
  await expect(page.getByText('GitHub 액세스 토큰', { exact: true })).toBeHidden();
  await page.locator('summary').filter({ hasText: '저장·보관 정보' }).click();
  await expect(page.getByText('GitHub 액세스 토큰', { exact: true })).toBeVisible();
});

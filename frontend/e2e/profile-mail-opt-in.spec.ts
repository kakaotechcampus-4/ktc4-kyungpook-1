import { expect, test, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

/** 인적사항의 메일 알림 칸 — 마이페이지에서 저장·새로고침, 처음 입력 창, 밝은·어두운·폰 화면 기록. */

const SHOTS = 'docs/screenshots/2026-11-07-mail-opt-in';
const OPT_IN = '내 경험에 맞는 공고를 메일로 받을게요';
const THEMES = ['light', 'dark'] as const;
const WIDTHS = [1440, 390] as const;

const savedProfile = (page: Page) => page.evaluate(() => JSON.parse(sessionStorage.getItem('gitory.demo.db') ?? '{}').profile);
const mail = (page: Page) => page.getByRole('region', { name: '메일 알림', exact: true });
const noOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth);

async function seedInterests(page: Page) {
  await page.goto('/settings');
  await page.evaluate(async () => {
    await fetch('/api/profile', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ interests: ['BACKEND'] }) });
  });
}

test.beforeEach(async ({ page }) => { await loginAsDemo(page); });

test('the MyPage form saves the address and the checkbox and shows them again after a reload', async ({ page }) => {
  await seedInterests(page);
  await page.goto('/settings/profile');
  await expect(mail(page)).toBeVisible();
  await mail(page).getByLabel('메일 주소', { exact: true }).fill(' me@example.com ');
  await mail(page).getByRole('checkbox', { name: OPT_IN, exact: true }).check();
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(page.locator('.sticky-footer strong')).toHaveText('저장했어요');
  expect(await savedProfile(page)).toMatchObject({ email: 'me@example.com', mailOptIn: true });
  await page.reload();
  await expect(mail(page).getByLabel('메일 주소', { exact: true })).toHaveValue('me@example.com');
  await expect(mail(page).getByRole('checkbox', { name: OPT_IN, exact: true })).toBeChecked();
});

for (const theme of THEMES) {
  for (const width of WIDTHS) {
    test(`screens: 메일 알림 칸 in the first-entry dialog and on MyPage (${theme}, ${width})`, async ({ page }) => {
      await page.addInitScript((value) => localStorage.setItem('gitory.theme', value), theme);
      await page.setViewportSize({ width, height: width === 390 ? 844 : 1024 });

      // 처음 입력 창
      await page.goto('/?demoScenario=no-profile');
      await page.getByRole('checkbox', { name: '백엔드', exact: true }).check();
      await expect(mail(page)).toBeVisible();
      await mail(page).getByRole('checkbox', { name: OPT_IN, exact: true }).evaluate((el) => el.scrollIntoView({ block: 'center' }));
      await mail(page).getByLabel('메일 주소', { exact: true }).fill('me@example.com');
      await mail(page).getByRole('checkbox', { name: OPT_IN, exact: true }).check();
      expect(await noOverflow(page)).toBe(true);
      await page.screenshot({ path: `${SHOTS}/dialog-${width}-${theme}.png`, animations: 'disabled' });

      // 마이페이지 — 주소 모양이 틀린 상태
      await page.getByRole('button', { name: '저장하고 시작하기', exact: true }).click();
      await expect(page.getByRole('dialog')).toHaveCount(0);
      await page.goto('/settings/profile');
      await expect(mail(page)).toBeVisible();
      await mail(page).getByLabel('메일 주소', { exact: true }).fill('sunjun@');
      await page.getByRole('button', { name: '저장', exact: true }).click();
      await expect(mail(page).getByRole('alert')).toHaveText('메일 주소를 다시 확인해 주세요');
      await mail(page).getByRole('checkbox', { name: OPT_IN, exact: true }).evaluate((el) => el.scrollIntoView({ block: 'center' }));
      expect(await noOverflow(page)).toBe(true);
      await page.screenshot({ path: `${SHOTS}/page-error-${width}-${theme}.png`, animations: 'disabled' });

      await mail(page).getByLabel('메일 주소', { exact: true }).fill('me@example.com');
      await expect(mail(page).getByRole('alert')).toHaveCount(0);
      await mail(page).getByRole('checkbox', { name: OPT_IN, exact: true }).evaluate((el) => el.scrollIntoView({ block: 'center' }));
      await page.screenshot({ path: `${SHOTS}/page-${width}-${theme}.png`, animations: 'disabled' });
    });
  }
}

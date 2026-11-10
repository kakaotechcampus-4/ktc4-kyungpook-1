import { expect, test, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

/** 인적사항(BE #552 계약) — 처음 입력 창은 관심 분야만 묻는다. 저장 → 새로고침 → 마이페이지 폼. 목 서버가 브라우저 안에서 돈다. */

const noHorizontalOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth);
const dialog = (page: Page) => page.getByRole('dialog', { name: '관심 분야', exact: true });
/** 시드 사용자는 처음 입력을 마친 사람이라, 처음 입력 창은 인적사항 미입력 장면으로 새로 연 데모에서 본다 */
const NO_PROFILE = '/?demoScenario=no-profile';
/** 처음 입력 창을 지나간 상태로 만든다 — 관심 분야 하나만 저장(목 서버가 브라우저 안에서 돌아 페이지에서 PUT 한다) */
async function seedInterests(page: Page) {
  await page.goto('/settings');
  await page.evaluate(async () => {
    await fetch('/api/profile', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ educations: [], careers: [], languages: [], certifications: [], interests: ['BACKEND'] }) });
  });
}
const status = (page: Page) => page.locator('.sticky-footer strong');
const savedProfile = (page: Page) => page.evaluate(() => JSON.parse(sessionStorage.getItem('gitory.demo.db') ?? '{}').profile);

test.beforeEach(async ({ page }) => { await loginAsDemo(page); });

test('first entry: the dialog asks only for 관심 분야 over the home page, then the MyPage form opens with what was saved', async ({ page }) => {
  await page.goto(NO_PROFILE);
  await expect(dialog(page)).toBeVisible();
  await expect(dialog(page).getByRole('button', { name: '닫기', exact: true })).toHaveCount(0);
  await expect(dialog(page).getByRole('button', { name: '다음', exact: true })).toHaveCount(0);
  await expect(dialog(page)).not.toContainText(/학력|경력|어학|자격증/);
  await page.getByRole('button', { name: '저장하고 시작하기', exact: true }).click();
  await expect(page.getByText('관심 분야를 1개 이상 골라 주세요')).toBeVisible();
  await page.getByRole('checkbox', { name: '백엔드', exact: true }).check();
  await page.getByRole('button', { name: '저장하고 시작하기', exact: true }).click();
  await expect(dialog(page)).toHaveCount(0);
  const afterSetup = await savedProfile(page);
  expect(afterSetup.interests).toEqual(['BACKEND']);
  expect([afterSetup.educations, afterSetup.careers, afterSetup.languages, afterSetup.certifications]).toEqual([[], [], [], []]);
  await page.reload();
  await expect(page.getByRole('heading', { name: '지원할 곳', level: 1 })).toBeVisible();
  await expect(dialog(page)).toHaveCount(0);

  await page.goto('/settings/profile');
  await expect(page.getByRole('heading', { name: '인적사항', level: 1 })).toBeVisible();
  await expect(page.getByRole('checkbox', { name: '백엔드', exact: true })).toBeChecked();
  await expect(status(page)).toHaveText('고친 곳이 없어요');
  await page.getByRole('checkbox', { name: '인프라, DevOps', exact: true }).check();
  await expect(status(page)).toHaveText('저장하지 않은 변경이 있어요');
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(status(page)).toHaveText('저장했어요');
  await expect(page.getByRole('button', { name: '저장', exact: true })).toBeFocused();
  expect((await savedProfile(page)).savedAt).not.toBeNull();

  await page.reload();
  await expect(page.getByRole('checkbox', { name: '백엔드', exact: true })).toBeChecked();
  await expect(page.getByRole('checkbox', { name: '인프라, DevOps', exact: true })).toBeChecked();
  await expect(status(page)).toHaveText('고친 곳이 없어요');

  await page.goto('/settings');
  const row = page.getByRole('link', { name: /인적사항/ });
  await expect(row).toContainText('관심 백엔드, 인프라, DevOps');
  await expect(row).not.toContainText(/학력|경력|어학|자격증/);
  await row.click();
  await expect(page).toHaveURL(/\/settings\/profile$/);
});

test('마이페이지에는 관심 분야와 메일 알림 구역만 있다', async ({ page }) => {
  await seedInterests(page);
  await page.goto('/settings/profile');
  await expect(page.getByRole('region', { name: '관심 분야', exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: '메일 알림', exact: true })).toBeVisible();
  await expect(page.getByRole('main').getByRole('region')).toHaveCount(2);
  await expect(page.getByRole('main')).not.toContainText(/학력|경력|어학|자격증/);
});

test('the dialog cannot be dismissed: Escape, clicking outside and a reload all keep it, and nothing is saved until 저장', async ({ page }) => {
  await page.goto(NO_PROFILE);
  await expect(dialog(page)).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(dialog(page)).toBeVisible();
  await page.mouse.click(8, 8);
  await expect(dialog(page)).toBeVisible();
  await page.getByRole('checkbox', { name: '백엔드', exact: true }).check();
  await page.reload();
  await expect(dialog(page)).toBeVisible();
  await expect(page.getByRole('checkbox', { name: '백엔드', exact: true })).not.toBeChecked(); // 저장 전이라 처음부터
  expect((await savedProfile(page)).savedAt).toBeNull();
  expect(await page.evaluate(() => Object.keys(localStorage).filter((key) => key.startsWith('gitory.profile')))).toEqual([]);
});

test('the dialog also appears for a first-time user with no cards, and the home start actions are there once it is done', async ({ page }) => {
  await page.goto('/?demoScenario=empty');
  await expect(dialog(page)).toBeVisible();
  await seedInterests(page);
  await page.goto('/?demoScenario=empty');
  await expect(dialog(page)).toHaveCount(0);
  await expect(page.getByRole('link', { name: '레포 고르기', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: '레포 정리하기', exact: true })).toBeVisible();
});

test('a failed save keeps the input and the retry saves it', async ({ page }) => {
  await seedInterests(page);
  await page.goto('/settings/profile?demoScenario=save-error');
  await page.getByRole('checkbox', { name: '데이터', exact: true }).check();
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('서버에서 처리하지 못했어요');
  await expect(status(page)).toHaveText('저장하지 못했어요. 입력한 내용은 그대로예요');
  await expect(page.getByRole('checkbox', { name: '데이터', exact: true })).toBeChecked();
  await expect(page.getByRole('button', { name: '다시 저장', exact: true })).toBeFocused();
  await page.getByRole('button', { name: '다시 저장', exact: true }).click();
  await expect(status(page)).toHaveText('저장했어요');
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('an empty interest list is explained next to the tiles and nothing is saved', async ({ page }) => {
  await seedInterests(page);
  await page.goto('/settings/profile');
  await page.getByRole('checkbox', { name: '백엔드', exact: true }).uncheck();
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(page.getByText('관심 분야를 1개 이상 골라 주세요')).toBeVisible();
  await expect(page.getByRole('alert')).toContainText('1곳');
  expect((await savedProfile(page)).interests).toEqual(['BACKEND']); // 처음 저장한 것만 남아 있다
  await page.getByRole('checkbox', { name: '프론트엔드', exact: true }).check();
  await expect(page.getByText('관심 분야를 1개 이상 골라 주세요')).toHaveCount(0);
});

test('leaving with unsaved input asks first', async ({ page }) => {
  await seedInterests(page);
  await page.goto('/settings/profile');
  await page.getByRole('checkbox', { name: '데이터', exact: true }).check();
  await page.getByRole('link', { name: '마이페이지로', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '아직 저장되지 않은 내용이 있어요' });
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: '계속 작성', exact: true }).click();
  await expect(page).toHaveURL(/\/settings\/profile$/);
  await expect(page.getByRole('checkbox', { name: '데이터', exact: true })).toBeChecked();
  await page.getByRole('link', { name: '마이페이지로', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: '저장하지 않고 나가기', exact: true }).click();
  await expect(page).toHaveURL(/\/settings$/);
});

for (const width of [390, 320]) {
  test(`mobile ${width}: dialog, form and MyPage have no horizontal scroll and touch-sized controls`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await page.goto(NO_PROFILE);
    await expect(dialog(page)).toBeVisible();
    expect(await noHorizontalOverflow(page)).toBe(true);
    for (const control of [dialog(page).locator('.interest-tile').first(), dialog(page).getByRole('button', { name: '저장하고 시작하기', exact: true })]) {
      expect((await control.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    }

    await seedInterests(page);
    await page.goto('/settings/profile');
    await expect(page.getByRole('checkbox', { name: '백엔드', exact: true })).toBeChecked();
    expect(await noHorizontalOverflow(page)).toBe(true);
    const controls = page.locator('.profile-page input:not([type="checkbox"]), .profile-page .interest-tile, .profile-page .btn');
    const heights = await controls.evaluateAll((els) => els.filter((el) => (el as HTMLElement).offsetParent !== null).map((el) => el.getBoundingClientRect().height));
    expect(heights.length).toBeGreaterThan(5);
    expect(Math.min(...heights)).toBeGreaterThanOrEqual(43.5);
    expect(await page.locator('.profile-page input:not([type="checkbox"])').first().evaluate((el) => parseFloat(getComputedStyle(el).fontSize))).toBeGreaterThanOrEqual(16); // iOS 확대 방지

    await page.getByRole('checkbox', { name: '데이터', exact: true }).check();
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await expect(status(page)).toHaveText('저장했어요');
    await page.goto('/settings');
    await expect(page.getByRole('link', { name: /인적사항/ })).toContainText('관심 백엔드, 데이터');
    expect(await noHorizontalOverflow(page)).toBe(true);
  });
}

test('mobile 390: the sticky save bar does not hide the last section', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await seedInterests(page);
  await page.goto('/settings/profile');
  const optIn = page.getByRole('region', { name: '메일 알림', exact: true }).getByRole('checkbox');
  await optIn.scrollIntoViewIfNeeded();
  const box = await optIn.boundingBox();
  const footer = await page.locator('.sticky-footer').boundingBox();
  expect(box!.y + box!.height).toBeLessThanOrEqual(footer!.y + 1);
});

for (const width of [1440, 390]) {
  test(`dark theme ${width}: dialog, form and errors stay readable`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.addInitScript(() => localStorage.setItem('gitory.theme', 'dark'));
    const contrast = (pairs: { text: string; surface: string }[]) => pairs.map(({ text: foreground, surface: background }) => {
      const luminance = (color: string) => {
        const [r, g, b] = color.match(/[\d.]+/g)!.slice(0, 3).map(Number).map((c) => c / 255).map((c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
      };
      const text = luminance(getComputedStyle(document.querySelector(foreground)!).color);
      const surface = luminance(getComputedStyle(document.querySelector(background)!).backgroundColor);
      return (Math.max(text, surface) + 0.05) / (Math.min(text, surface) + 0.05);
    });
    await page.goto(NO_PROFILE);
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await expect(dialog(page)).toBeVisible();
    await page.getByRole('checkbox', { name: '백엔드', exact: true }).check();
    for (const value of await page.evaluate(contrast, [
      { text: '.modal__title', surface: '.modal' }, { text: '.modal__foot-text strong', surface: '.modal__foot' },
      { text: '.interest-tile.is-on span', surface: '.interest-tile.is-on' }, { text: '.interest-tile:not(.is-on) span', surface: '.interest-tile:not(.is-on)' },
      { text: '.modal__foot .btn--primary', surface: '.modal__foot .btn--primary' },
    ])) expect(value).toBeGreaterThanOrEqual(4.5);
    expect(await noHorizontalOverflow(page)).toBe(true);

    await seedInterests(page);
    await page.goto('/settings/profile');
    await page.getByRole('checkbox', { name: '백엔드', exact: true }).uncheck();
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await expect(page.locator('.profile-error').first()).toBeVisible();
    for (const value of await page.evaluate(contrast, [
      { text: '.profile-error', surface: '.profile-section' }, { text: '.note--danger strong', surface: '.note--danger' },
    ])) expect(value).toBeGreaterThanOrEqual(4.5);
    expect(await noHorizontalOverflow(page)).toBe(true);
  });
}

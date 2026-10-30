import { expect, test, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

/** 페이지가 뜨는 동안 눈에 보이는 로딩 문구가 한 번이라도 채워졌는지 기록한다 — 시점을 맞춰 찍지 않아도 깜빡임을 잡는다. */
async function recordLoadingText(page: Page) {
  await page.addInitScript(() => {
    const w = window as unknown as { __loadingTextSeen: boolean };
    w.__loadingTextSeen = false;
    const check = () => {
      document.querySelectorAll('.page-loading__text').forEach((el) => { if (el.textContent?.trim()) w.__loadingTextSeen = true; });
    };
    new MutationObserver(check).observe(document, { subtree: true, childList: true, characterData: true });
  });
}
const seen = (page: Page) => page.evaluate(() => (window as unknown as { __loadingTextSeen: boolean }).__loadingTextSeen);

test('slow first response: status text is always there, visible text shows up after 1.5s', async ({ page }) => {
  await recordLoadingText(page);
  await loginAsDemo(page);
  await page.goto('/cards?demoScenario=slow');
  // 로그인 확인(/me)이 3.5초 걸리는 동안
  await expect(page.getByRole('status')).toBeAttached();
  await expect(page.locator('.page-loading__text')).toHaveText('불러오는 중이에요', { timeout: 3000 });
  await expect(page.locator('.page-loading__text')).toBeVisible();
  // 응답이 오면 화면이 바뀌고 로딩 문구는 사라진다
  await expect(page.getByRole('heading', { level: 1 })).toContainText('경험 카드', { timeout: 15000 });
  await expect(page.locator('.page-loading__text')).toHaveCount(0);
  expect(await seen(page)).toBe(true);
});

test('fast response never flashes the visible loading text', async ({ page }) => {
  await recordLoadingText(page);
  await loginAsDemo(page);
  await page.goto('/cards');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('경험 카드');
  await expect(page.locator('.page-loading__text')).toHaveCount(0);
  expect(await seen(page)).toBe(false);
});

test('slow route chunk shows the same loading text inside the page', async ({ page }) => {
  await recordLoadingText(page);
  await loginAsDemo(page);
  // 화면 코드(청크)만 2.5초 늦춘다 — 로그인 확인은 빠르다
  await page.route(/CardsListPage/, async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 2500));
    await route.continue();
  });
  await page.goto('/cards');
  await expect(page.locator('.page-loading__text')).toHaveText('불러오는 중이에요', { timeout: 3000 });
  await expect(page.getByRole('heading', { level: 1 })).toContainText('경험 카드', { timeout: 15000 });
  await expect(page.locator('.page-loading__text')).toHaveCount(0);
  expect(await seen(page)).toBe(true);
});

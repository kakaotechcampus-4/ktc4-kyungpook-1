import { expect, test, type Page } from '@playwright/test';
import { freshSeed, loginAsDemo } from './helpers';

async function expectNoHorizontalOverflow(page: Page) {
  const metrics = await page.evaluate(() => ({
    viewport: window.innerWidth,
    document: document.documentElement.scrollWidth,
    body: document.body.scrollWidth,
  }));
  expect(metrics.document, JSON.stringify(metrics)).toBeLessThanOrEqual(metrics.viewport);
  expect(metrics.body, JSON.stringify(metrics)).toBeLessThanOrEqual(metrics.viewport);
}

test.describe('반응형 레이아웃', () => {
  test('320px 로그인은 단일 열이고 상세 권한을 접어 둔다', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 740 });
    await freshSeed(page);
    await page.goto('/login?reason=expired');

    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page.getByRole('button', { name: 'GitHub으로 시작하기' })).toBeVisible();
    await expect(page.getByText('로그인이 만료됐어요')).toBeVisible();
    await expect(page.getByRole('group', { name: 'GitHub 접근 범위 자세히 보기' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '이건 읽어요' })).toBeHidden();
    await expectNoHorizontalOverflow(page);

    const columns = await page.locator('.landing__hero').evaluate((el) => getComputedStyle(el).gridTemplateColumns.split(' ').length);
    expect(columns).toBe(1);

    await page.getByRole('button', { name: 'GitHub으로 시작하기' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    const box = await dialog.boundingBox();
    expect(box?.width).toBeLessThanOrEqual(296);
    await expectNoHorizontalOverflow(page);
  });

  test('390px 앱은 이름이 보이는 모바일 내비게이션과 단일 열을 쓴다', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await loginAsDemo(page);
    await page.goto('/');

    const nav = page.getByRole('navigation', { name: '모바일 주 메뉴' });
    await expect(nav).toBeVisible();
    await expect(nav.getByText('홈', { exact: true })).toBeVisible();
    await expect(nav.getByText('레포', { exact: true })).toBeVisible();
    await expect(nav.getByText('카드', { exact: true })).toBeVisible();
    await expect(nav.getByText('설정', { exact: true })).toBeVisible();
    await expect(page.locator('.sidebar')).toBeHidden();
    await expectNoHorizontalOverflow(page);
  });

  test('주요 라우트는 모바일·태블릿·데스크톱에서 가로로 넘치지 않는다', async ({ page }) => {
    await loginAsDemo(page);
    const routes = ['/', '/repos', '/repos/r_auth/candidates', '/cards', '/cards/card_01', '/cards/card_01/interview?field=T', '/cards/new', '/settings', '/settings/github'];
    for (const viewport of [
      { width: 390, height: 844 },
      { width: 768, height: 1024 },
      { width: 1024, height: 768 },
      { width: 1440, height: 900 },
    ]) {
      await page.setViewportSize(viewport);
      for (const path of routes) {
        await page.goto(path);
        await expect(page.locator('main')).toBeVisible();
        await expectNoHorizontalOverflow(page);
      }
    }
  });

  test('1920px 앱은 남는 작업 폭을 활용한다', async ({ page }) => {
    await page.setViewportSize({ width: 1920, height: 1080 });
    await loginAsDemo(page);
    await page.goto('/cards');

    const mainWidth = await page.locator('main').evaluate((el) => el.getBoundingClientRect().width);
    expect(mainWidth).toBeGreaterThan(1500);
    await expectNoHorizontalOverflow(page);
  });
});

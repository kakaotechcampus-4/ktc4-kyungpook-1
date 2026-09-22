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
    await expect(page.getByRole('button', { name: '샘플로 체험하기' })).toBeVisible();
    await expect(page.getByText('로그인이 만료됐어요')).toBeVisible();
    await expect(page.getByRole('group', { name: 'GitHub 접근 범위 자세히 보기' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '이건 읽어요' })).toBeHidden();
    await expectNoHorizontalOverflow(page);

    const columns = await page.locator('.landing__hero').evaluate((el) => getComputedStyle(el).gridTemplateColumns.split(' ').length);
    expect(columns).toBe(1);

    await page.getByRole('button', { name: '샘플로 체험하기' }).click();
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

  test('PC 로그인 헤더와 본문은 좌우 정렬선과 로고 높이를 맞춘다', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await freshSeed(page);
    await page.goto('/login');

    const buttons = page.getByRole('button', { name: '샘플로 체험하기', exact: true });
    await expect(buttons).toHaveCount(2);
    await expect(page.getByText(/GitHub으로 시작/)).toHaveCount(0);

    const geometry = await page.evaluate(() => {
      const header = document.querySelector<HTMLElement>('.landing__top')!;
      const hero = document.querySelector<HTMLElement>('.landing__hero')!;
      const logo = header.querySelector<HTMLImageElement>('img')!;
      const button = header.querySelector<HTMLButtonElement>('button')!;
      const h = header.getBoundingClientRect();
      const content = hero.getBoundingClientRect();
      return {
        leftDelta: Math.abs(h.left - content.left),
        rightDelta: Math.abs(h.right - content.right),
        heightDelta: Math.abs(logo.getBoundingClientRect().height - button.getBoundingClientRect().height),
      };
    });
    expect(geometry.leftDelta).toBeLessThanOrEqual(1);
    expect(geometry.rightDelta).toBeLessThanOrEqual(1);
    expect(geometry.heightDelta).toBeLessThanOrEqual(1);
  });

  test('홈과 카드 목록은 하나의 여유 있는 경험 목록과 정렬된 STAR를 쓴다', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await loginAsDemo(page);

    for (const path of ['/', '/cards']) {
      await page.goto(path);
      await expect(page.locator('.experience-list')).toHaveCount(1);
      await expect(page.locator('.experience-item').first()).toBeVisible();
      const itemShadow = await page.locator('.experience-item').first().evaluate((el) => getComputedStyle(el).boxShadow);
      expect(itemShadow).toBe('none');
      const widths = await page.locator('.experience-item').first().locator('.stardot').evaluateAll((els) => els.map((el) => el.getBoundingClientRect().width));
      expect(new Set(widths.map((width) => Math.round(width))).size).toBe(1);
      await expect(page.locator('.experience-item').first().locator('.stardot__label')).toHaveCount(4);
    }
  });

  test('설정은 관련 정보를 큰 섹션으로 묶고 선택 경계는 유지한다', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await loginAsDemo(page);
    await page.goto('/settings');

    await expect(page.locator('.settings-section')).toHaveCount(4);
    await expect(page.locator('.stat-grid > .card')).toHaveCount(0);
    const shadows = await page.locator('.settings-section').evaluateAll((els) => els.map((el) => getComputedStyle(el).boxShadow));
    expect(shadows.every((shadow) => shadow === 'none')).toBe(true);

    await page.goto('/repos');
    await page.getByRole('radio').first().click();
    const selectedBorder = await page.locator('.card--selected').first().evaluate((el) => parseFloat(getComputedStyle(el).borderTopWidth));
    expect(selectedBorder).toBeGreaterThanOrEqual(1);
  });

  test('저장소와 후보는 개별 카드 대신 하나의 행 목록으로 묶는다', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await loginAsDemo(page);
    for (const path of ['/repos', '/repos/r_auth/candidates']) {
      await page.goto(path);
      await expect(page.locator('.record-list')).toHaveCount(1);
      const children = page.locator('.record-list > .card');
      expect(await children.count()).toBeGreaterThan(1);
      const shadows = await children.evaluateAll((els) => els.map((el) => getComputedStyle(el).boxShadow));
      expect(shadows.every((shadow) => shadow === 'none')).toBe(true);
    }
  });
});

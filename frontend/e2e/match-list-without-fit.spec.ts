import { expect, test, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

/**
 * 서버가 매칭 목록(GET /api/matches)에서 등급(fit)과 채운 개수(met)를 비워 보내는 때를 흉내 낸다.
 * 목 API 는 앱이 뜰 때 window.fetch 를 덮어쓰므로, 덮어쓰는 순간을 잡아 응답만 고쳐 준다.
 * confirmedCards 를 주면 /api/me 의 확정 카드 수도 그 값으로 바꾼다.
 */
async function serverWithoutFit(page: Page, confirmedCards?: number) {
  await page.addInitScript((cards) => {
    let current = window.fetch;
    const rewrite = (path: string, body: { data: unknown }) => {
      if (path === '/api/matches' && Array.isArray(body.data)) {
        body.data = body.data.map((x: { required: object; preferred: object }) => ({ ...x, fit: null, required: { ...x.required, met: 0 }, preferred: { ...x.preferred, met: 0 } }));
      }
      if (path === '/api/me' && cards !== undefined && body.data && typeof body.data === 'object') {
        const me = body.data as { stats: object };
        body.data = { ...me, stats: { ...me.stats, confirmedCards: cards } };
      }
      return body;
    };
    Object.defineProperty(window, 'fetch', {
      configurable: true,
      get: () => current,
      set: (next: typeof window.fetch) => {
        current = async (input, init) => {
          const response = await next(input, init);
          const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
          const path = new URL(raw, location.origin).pathname;
          if (!response.ok || (path !== '/api/matches' && path !== '/api/me')) return response;
          const body = rewrite(path, await response.clone().json());
          return new Response(JSON.stringify(body), { status: response.status, headers: response.headers });
        };
      },
    });
  }, confirmedCards);
}

const sortMenu = (page: Page) => page.getByRole('combobox', { name: '공고 정렬', exact: true });

for (const width of [1440, 390]) {
  test(`확정 카드가 있고 목록의 등급이 비어 오면 공고만 보여 준다 ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await loginAsDemo(page);
    await serverWithoutFit(page, 3);
    await page.goto('/match');
    await expect(page.locator('.match-card')).toHaveCount(5);
    await expect(page.getByText(/확정한 카드가 없어요/)).toHaveCount(0);
    await expect(page.getByRole('link', { name: '카드 확정하러 가기' })).toHaveCount(0);
    await expect(page.getByText('카드를 확정하면 등급이 나와요')).toHaveCount(0);
    await expect(page.locator('.match-fit')).toHaveCount(0);
    await expect(page.locator('.match-counts')).toHaveCount(0);
    await expect(sortMenu(page)).toContainText('기본 순서');
    await sortMenu(page).click();
    await expect(page.getByRole('option')).toHaveText(['기본 순서', '마감 순']);
    await page.keyboard.press('Escape');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  });
}

test('주소에 sort=fit 이 남아 있어도 기본 순서로 목록을 보여 준다', async ({ page }) => {
  await loginAsDemo(page);
  await serverWithoutFit(page, 3);
  await page.goto('/match?sort=fit');
  await expect(page.locator('.match-card')).toHaveCount(5);
  await expect(sortMenu(page)).toContainText('기본 순서');
  await page.locator('.match-card').first().click();
  await page.getByRole('link', { name: '← 공고 목록' }).click();
  await expect(page.locator('.match-card')).toHaveCount(5);
});

test('확정 카드가 0장이면 안내와 카드 확정 링크를 보여 준다', async ({ page }) => {
  await loginAsDemo(page);
  await serverWithoutFit(page, 0);
  await page.goto('/match');
  await expect(page.getByText(/확정한 카드가 없어요/)).toBeVisible();
  await expect(page.getByRole('link', { name: '카드 확정하러 가기' })).toHaveAttribute('href', '/cards');
  await expect(page.locator('.match-card')).toHaveCount(5);
});

test('등급이 있는 목록은 그대로 근거 등급 순과 마감 순을 고를 수 있다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/match');
  await expect(page.locator('.match-card')).toHaveCount(5);
  // 등급은 목록 카드에 그리지 않고 상세에서만 보여 주므로, 정렬 선택지로만 확인한다
  await expect(page.locator('.match-fit')).toHaveCount(0);
  await expect(sortMenu(page)).toContainText('근거 등급 순');
  await sortMenu(page).click();
  await expect(page.getByRole('option')).toHaveText(['근거 등급 순', '마감 순']);
});

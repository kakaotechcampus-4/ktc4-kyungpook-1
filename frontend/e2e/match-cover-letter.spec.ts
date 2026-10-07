import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

test('매칭 목록에서 상세를 거쳐 자소서 초안을 만들고 고치면 새로고침해도 남는다', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await loginAsDemo(page);
  await page.goto('/');
  await page.getByRole('link', { name: '기업·직무 매칭', exact: true }).first().click();
  await expect(page.getByRole('heading', { name: /기업·직무 매칭/ })).toBeVisible();
  await expect(page.getByRole('link', { name: /블루오션페이/ })).toContainText('근거 충분');
  await expect(page.getByText('해든소프트(예시)')).toHaveCount(0); // 확인일이 지난 기업은 빠진다
  await expect(page.getByText(/\d+\s*%/)).toHaveCount(0);

  await page.getByRole('link', { name: /블루오션페이/ }).click();
  await expect(page.getByRole('heading', { name: '블루오션페이(예시)' })).toBeVisible();
  await expect(page.getByText('근거 없음')).toBeVisible(); // 빈 칸을 숨기지 않는다
  await expect(page.getByRole('link', { name: 'https://example.com/careers/blueocean-pay' })).toHaveAttribute('rel', /noopener/);

  await page.getByRole('link', { name: '자소서 초안 만들기' }).click();
  await expect(page.getByRole('checkbox', { name: '토큰 만료 자동 재발급 사용' })).toBeChecked();
  await expect(page.getByRole('checkbox', { name: '팀원과 토큰 저장 위치로 갈린 경험 사용' })).toBeChecked();
  await page.getByRole('button', { name: '초안 만들기' }).click();
  await expect(page).toHaveURL(/\/cover-letter\/cl_/);
  await expect(page.getByText('AI가 이은 문장')).toHaveCount(2);
  await expect(page.getByText('확정한 카드 문장')).toHaveCount(2);
  await expect(page.getByText('근거가 없어 초안에 넣지 않은 인재상 1개')).toBeVisible();

  await page.getByRole('button', { name: '초안 고치기' }).click();
  await page.getByRole('textbox', { name: '자소서 초안' }).fill('제가 직접 다듬은 자소서입니다.');
  await page.getByRole('button', { name: '저장하고 닫기' }).click();
  await expect(page.getByText('직접 고침').first()).toBeVisible();
  await page.reload();
  await expect(page.getByText('제가 직접 다듬은 자소서입니다.')).toBeVisible();

  await page.goto('/cover-letter');
  await expect(page.getByRole('link', { name: /블루오션페이.*지원 동기/ })).toContainText('직접 고침');
  expect(errors).toEqual([]);
});

test('확정한 카드가 없으면 근거를 지어내지 않고 카드 정리로 안내한다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/cards');
  await expect(page.getByRole('heading', { name: /경험 카드/ }).first()).toBeVisible();
  // 목 DB 는 sessionStorage 에 저장된다 — 모든 카드를 초안 상태로 되돌린 뒤 새로 연다.
  await page.evaluate(() => {
    const saved = JSON.parse(sessionStorage.getItem('gitory.demo.db')!);
    saved.cards.forEach((card: { status: string }) => { card.status = 'DRAFT'; });
    sessionStorage.setItem('gitory.demo.db', JSON.stringify(saved));
  });
  await page.goto('/match');
  await expect(page.getByText('확정한 카드가 있어야 해요')).toBeVisible();
  await expect(page.getByText(/근거 충분|근거 보통|근거 적음|근거 부족/)).toHaveCount(0);
  await page.goto('/cover-letter');
  await expect(page.getByText('확정한 카드가 있어야 해요')).toBeVisible();
  await expect(page.getByRole('button', { name: '초안 만들기' })).toBeDisabled();
});

for (const width of [320, 390, 768, 1440]) {
  test(`${width}px 에서 매칭·자소서 화면이 가로로 넘치지 않고 메뉴가 모두 보인다`, async ({ page }) => {
    await loginAsDemo(page);
    await page.setViewportSize({ width, height: 900 });
    for (const path of ['/match', '/match/co_pay', '/cover-letter']) {
      await page.goto(path);
      await expect(page.locator('main h1, main h2').first()).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), path).toBe(true);
    }
    if (width < 768) {
      const nav = page.getByRole('navigation', { name: '모바일 주 메뉴' });
      await expect(nav.getByRole('link')).toHaveCount(5);
      for (const link of await nav.getByRole('link').all()) {
        const box = (await link.boundingBox())!;
        expect(box.x).toBeGreaterThanOrEqual(0); expect(box.x + box.width).toBeLessThanOrEqual(width);
        await expect(link.locator('span')).toBeVisible(); // 이름이 보여야 한다
      }
    }
  });
}

test('다크 테마에서도 등급 배지와 근거 문장이 읽힌다', async ({ page }) => {
  await loginAsDemo(page);
  await page.addInitScript(() => localStorage.setItem('gitory.theme', 'dark'));
  await page.goto('/match/co_pay');
  await expect(page.getByRole('heading', { name: '블루오션페이(예시)' })).toBeVisible();
  const ratio = await page.locator('.star__text').first().evaluate((element) => {
    const lum = (c: string) => { const v = c.match(/[\d.]+/g)!.slice(0, 3).map(Number).map((x) => x / 255).map((x) => x <= .04045 ? x / 12.92 : ((x + .055) / 1.055) ** 2.4); return .2126 * v[0] + .7152 * v[1] + .0722 * v[2]; };
    let bg: HTMLElement | null = element as HTMLElement; let color = 'rgba(0, 0, 0, 0)';
    while (bg && /rgba\(0, 0, 0, 0\)|transparent/.test(color)) { color = getComputedStyle(bg).backgroundColor; bg = bg.parentElement; }
    const [a, b] = [lum(getComputedStyle(element).color), lum(color)].sort((x, y) => y - x);
    return (a + .05) / (b + .05);
  });
  expect(ratio).toBeGreaterThanOrEqual(4.5);
});

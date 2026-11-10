import { expect, test, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

/**
 * #520 — 모바일 셸의 누르는 자리와 글자 크기.
 *  G-01 머리글의 로고·프로필·건너뛰기 링크 44px, G-02 브레드크럼 링크 44px(줄 높이는 그대로),
 *  G-04·G-05 탭바 이름은 사이드바와 같은 "마이페이지", 글자 12px 이상, H-05 홈 검색 상자 어디를 눌러도 입력으로, A-06 320px 일부만 정리 카드.
 */
const overflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);

for (const width of [390, 320]) {
  test(`${width}px 머리글과 브레드크럼은 44px 로 눌리고, 머리글 높이는 그대로다`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await loginAsDemo(page); await page.goto('/repos/r_auth/run');
    const header = page.locator('.mobile-brand');
    await expect(header).toBeVisible();
    for (const control of [header.getByRole('link', { name: 'Gitory 홈' }), header.getByRole('button', { name: /계정 메뉴/ })]) {
      expect((await control.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    }
    expect((await header.boundingBox())!.height).toBeLessThanOrEqual(64); // 44px 컨트롤을 넣고도 머리글이 본문을 더 밀지 않는다

    const crumb = page.locator('.crumb');
    await expect(crumb.getByRole('link', { name: '홈', exact: true })).toBeVisible();
    for (const link of await crumb.getByRole('link').all()) expect((await link.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    expect((await crumb.boundingBox())!.height).toBeLessThanOrEqual(20); // 줄 높이는 그대로, 눌리는 자리만 커졌다
    expect(await overflow(page)).toBeLessThanOrEqual(0);

    await page.locator('.skip-link').focus(); // 건너뛰기 링크는 초점이 올 때만 보인다
    expect((await page.locator('.skip-link').boundingBox())!.height).toBeGreaterThanOrEqual(44);
  });

  test(`${width}px 탭바는 사이드바와 같은 이름이고 글자가 12px 이상이며 가로로 넘치지 않는다`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await loginAsDemo(page); await page.goto('/');
    const tabs = page.getByRole('navigation', { name: '모바일 주 메뉴' });
    await expect(tabs.getByText('마이페이지', { exact: true })).toBeVisible();
    await expect(tabs.getByText('설정', { exact: true })).toHaveCount(0);
    const sizes = await tabs.locator('a span').evaluateAll((spans) => spans.map((span) => ({ text: span.textContent, size: parseFloat(getComputedStyle(span).fontSize), clipped: span.scrollWidth > (span.parentElement as HTMLElement).clientWidth })));
    expect(sizes.map((s) => s.text)).toEqual(['홈', '레포', '카드', '매칭', '자소서', '마이페이지']);
    for (const s of sizes) { expect(s.size).toBeGreaterThanOrEqual(12); expect(s.clipped).toBe(false); }
    expect(await overflow(page)).toBeLessThanOrEqual(0);
  });
}

test('데스크톱 사이드바와 레포 화면의 이름이 같다 (레포)', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await loginAsDemo(page); await page.goto('/repos');
  await expect(page.getByRole('complementary', { name: '주 메뉴' }).getByRole('link', { name: '레포', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { level: 1 })).toContainText('레포');
  await expect(page.getByRole('heading', { level: 1 })).not.toContainText('고르기');
  await expect(page).toHaveTitle('레포 · Gitory');
});

for (const width of [320, 390]) {
  test(`${width}px 일부만 정리한 결과의 안내 줄과 아래 줄이 가로로 넘치지 않는다`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    // 일부만 정리한 화면은 1초 안에 카드로 넘어가므로 가짜 시계로 멈춰 두고 잰다
    await page.clock.install();
    await loginAsDemo(page); await page.goto('/');
    // 목의 r_ratelimit 은 요청 한도에 걸려 일부만 끝난다(이어 읽기까지 기다려야 한다)
    const jobId = await page.evaluate(async () => {
      const response = await fetch('/api/repos/r_ratelimit/analyze', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() } });
      return (await response.json()).data.jobId as string;
    });
    await page.goto(`/repos/r_ratelimit/run?job=${jobId}`);
    const heading = page.getByRole('heading', { name: '읽은 데까지 정리했어요' });
    for (let i = 0; i < 150 && !(await heading.isVisible()); i += 1) await page.clock.runFor(200); // 끝난 화면은 1초 남짓이라 잘게 돌린다
    await expect(heading).toBeVisible();
    const note = page.locator('.run-capnote'); const foot = page.locator('.run-foot');
    await expect(note).toBeVisible();
    await expect(foot).toBeVisible();
    const box = (await note.boundingBox())!;
    expect(box.x).toBeGreaterThanOrEqual(0); expect(box.x + box.width).toBeLessThanOrEqual(width);
    const row = (await foot.boundingBox())!;
    expect(row.x + row.width).toBeLessThanOrEqual(width);
    expect(await overflow(page)).toBeLessThanOrEqual(0);
    await expect(page).toHaveTitle(/일부만 정리 · Gitory$/);
  });
}

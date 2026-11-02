import { expect, test, type Page } from '@playwright/test';
import { loginAsDemo, openRepoSearch } from './helpers';

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

for (const width of [1440, 390]) {
  test(`${width}px 홈 검색 상자는 위아래 여백을 눌러도 입력에 초점이 간다`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await loginAsDemo(page); await page.goto('/');
    await openRepoSearch(page); // 검색칸은 '레포 검색하기' 를 눌러야 열린다
    const input = page.getByRole('combobox', { name: '레포 검색' });
    await expect(input).toBeVisible();
    expect((await input.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    // 카드 타일이 커지면(다른 PR) 검색 상자가 화면 아래 메뉴에 가려질 수 있어, 화면 가운데로 가져온 뒤 잰다
    await page.locator('.prompt__row').evaluate((el) => el.scrollIntoView({ block: 'center' }));
    const row = (await page.locator('.prompt__row').boundingBox())!;
    await page.mouse.click(row.x + row.width / 2, row.y + 4); // 글자 줄 바깥, 상자 안쪽 위
    await expect(input).toBeFocused();
    await input.blur();
    await page.mouse.click(row.x + row.width / 2, row.y + row.height - 4);
    await expect(input).toBeFocused();
  });
}

for (const width of [320, 390]) {
  test(`${width}px 일부만 정리한 결과의 버튼은 한 줄이고 가로로 넘치지 않는다`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await loginAsDemo(page); await page.goto('/');
    // 목의 r_ratelimit 은 요청 한도에 걸려 일부만 끝난다(이어 읽기까지 기다려야 한다)
    const jobId = await page.evaluate(async () => {
      const response = await fetch('/api/repos/r_ratelimit/analyze', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() } });
      return (await response.json()).data.jobId as string;
    });
    await page.goto(`/repos/r_ratelimit/run?job=${jobId}`);
    await expect(page.getByRole('heading', { name: '읽은 데까지 정리했어요' })).toBeVisible({ timeout: 30_000 });
    const button = page.locator('.partial-card').getByRole('button', { name: /뒤 이어 읽기$/ });
    await expect(button).toBeVisible();
    const box = (await button.boundingBox())!;
    expect(box.height).toBeLessThanOrEqual(48); // 네 줄로 쪼개지면 80px 을 넘는다
    expect(box.height).toBeGreaterThanOrEqual(44);
    const card = (await page.locator('.partial-card').boundingBox())!;
    expect(box.x).toBeGreaterThanOrEqual(card.x); expect(box.x + box.width).toBeLessThanOrEqual(card.x + card.width);
    expect(await overflow(page)).toBeLessThanOrEqual(0);
    await expect(page).toHaveTitle(/일부만 정리 · Gitory$/);
  });
}

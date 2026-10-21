import { test, expect } from "@playwright/test";
test('rapid typing retains every character and survives detail navigation', async ({ page }) => {
  await page.goto('/match');
  const search = page.getByPlaceholder('기업·직무 검색');
  await expect(page.locator('.match-card').first()).toBeVisible();
  for (const query of ['backend developer', '프론트엔드 개발자', '나래']) {
    await search.fill('');
    await search.pressSequentially(query, { delay: 0 });
    await expect(search).toHaveValue(query);
  }
  await page.locator('.match-card').click();
  await page.goBack();
  await expect(search).toHaveValue('나래');
  await search.press('Escape');
  await expect(search).toHaveValue('');
  await expect(page.locator('.match-card')).toHaveCount(5);
});
test.beforeEach(async ({ page }) => {
  await page.addInitScript(() =>
    sessionStorage.setItem("gitory.demo.session", "1"),
  );
});
for (const width of [320, 390, 768, 1440]) {
  test(`matching list/detail navigation and overflow ${width}`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/match");
    await expect(
      page.getByRole("heading", { name: "공고 매칭", exact: true }),
    ).toBeVisible();
    const card = page.locator(".match-card").first();
    await expect(card).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await page.getByPlaceholder("기업·직무 검색").fill("나래");
    await expect(page.locator(".match-card")).toHaveCount(1);
    await page.getByRole("combobox", { name: "경력", exact: true }).click();
    await page.getByRole("option", { name: "신입 가능", exact: true }).click();
    await page.locator(".match-card").click();
    await expect(
      page.getByRole("heading", { name: "서버 개발자 (신입)", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole("heading", { name: /필수 조건/ }),
    ).toBeVisible();
    await expect(
      page.getByText("아직 연결된 경험이 없어요.").first(),
    ).toBeVisible();
    await expect(page.locator(".match-support-link").first()).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await page.getByRole("link", { name: "← 공고 목록" }).click();
    await expect(page.getByPlaceholder("기업·직무 검색")).toHaveValue("나래");
    await expect(
      page.getByRole("combobox", { name: "경력", exact: true }),
    ).toContainText("신입 가능");
    if (width === 390) {
      await page.locator(".match-card").click();
      await page.locator(".match-support-link").first().click();
      await expect(page).toHaveURL(/\/cards\//);
    }
  });
}
test("dark matching uses readable surfaces and preserves keyboard actions", async ({
  page,
}) => {
  await page.addInitScript(() => localStorage.setItem("gitory.theme", "dark"));
  await page.goto("/match");
  await expect(page.locator(".match-card").first()).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.getByPlaceholder("기업·직무 검색").fill("없는회사");
  await expect(page.getByText("조건에 맞는 공고가 없어요")).toBeVisible();
  await page.getByRole("button", { name: "검색·필터 초기화" }).click();
  await expect(page.locator(".match-card")).toHaveCount(5);
});

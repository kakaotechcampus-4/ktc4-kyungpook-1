import { test, expect } from "@playwright/test";
test('rapid typing retains every character and survives detail navigation', async ({ page }) => {
  await page.goto('/match');
  const search = page.getByPlaceholder('기업이나 직무 검색');
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
    await page.getByPlaceholder("기업이나 직무 검색").fill("나래");
    await expect(page.locator(".match-card")).toHaveCount(1);
    const backend = page.getByRole("group", { name: "직무" }).getByRole("button", { name: "백엔드", exact: true });
    await backend.click();
    await page.locator(".match-card").click();
    await expect(
      page.getByRole("heading", { name: "서버 개발자 (신입)", exact: true }),
    ).toBeVisible();
    // 맞춰 보기 전에는 공고 원문(자격 요건 절)만 보이고, 주 버튼은 '이 공고와 맞춰 보기' 다
    await expect(
      page.getByRole("heading", { name: "자격 요건", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: "이 공고와 맞춰 보기" }),
    ).toBeVisible();
    await expect(page.locator(".posting-condition__evidence a")).toHaveCount(0);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await page.getByRole("link", { name: "공고 목록으로" }).click();
    await expect(page.getByPlaceholder("기업이나 직무 검색")).toHaveValue("나래");
    await expect(backend).toHaveAttribute("aria-pressed", "true");
    if (width === 390) {
      await page.locator(".match-card").click();
      // 맞춰 보면 결과가 차례로 펼쳐진 뒤(6초쯤) 조건 아래 근거 카드 링크가 생긴다
      await page.getByRole("button", { name: "이 공고와 맞춰 보기" }).click();
      await page.locator(".posting-condition__evidence a").first().click({ timeout: 20_000 });
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
  await page.getByPlaceholder("기업이나 직무 검색").fill("없는회사");
  await expect(page.getByText("조건에 맞는 공고가 없어요")).toBeVisible();
  await page.getByRole("button", { name: "검색과 직무 초기화" }).click();
  await expect(page.locator(".match-card")).toHaveCount(5);
});

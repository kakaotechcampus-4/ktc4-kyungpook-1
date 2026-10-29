import { expect, test } from "@playwright/test";
import { loginAsDemo } from "./helpers";

test.beforeEach(async ({ page }) => {
  await loginAsDemo(page);
});

const overflowFree = (page: import("@playwright/test").Page) =>
  page.evaluate(() => document.documentElement.scrollWidth <= innerWidth);

test("목록 → 기업 페이지 → 공고 상세 → 뒤로: 기업 페이지와 목록의 검색어가 그대로 남는다", async ({ page }) => {
  await page.goto("/match");
  const search = page.getByPlaceholder("기업·직무 검색");
  await expect(page.locator(".match-card")).toHaveCount(5);
  await search.fill("나래");
  await expect(page.locator(".match-card")).toHaveCount(1);

  // 카드의 기업 이름 → 기업 페이지 (검색어를 주소로 이어 간다)
  await page.getByRole("link", { name: "나래랩스", exact: true }).click();
  await expect(page).toHaveURL(/\/match\/company\/%EB%82%98%EB%9E%98%EB%9E%A9%EC%8A%A4\?q=/);
  await expect(page.getByRole("heading", { level: 1, name: "나래랩스" })).toBeVisible();
  await expect(page.getByRole("status").filter({ hasText: "공고 1건" })).toBeVisible();
  await expect(page.getByLabel("최고 등급 B")).toBeVisible();
  await expect(page.locator(".match-card")).toHaveCount(1);
  expect(await overflowFree(page)).toBe(true);

  // 카드 → 공고 상세
  await page.locator(".match-card").click();
  await expect(page.getByRole("heading", { name: "서버 개발자 (신입)", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "나래랩스", exact: true })).toBeVisible(); // 상세 머리말의 기업 링크

  // 브라우저 뒤로 → 기업 페이지
  await page.goBack();
  await expect(page.getByRole("heading", { level: 1, name: "나래랩스" })).toBeVisible();

  // 돌아가는 링크 → 같은 검색어의 목록
  await page.getByRole("link", { name: "← 공고 목록" }).click();
  await expect(page.getByRole("heading", { name: "공고 매칭", exact: true })).toBeVisible();
  await expect(search).toHaveValue("나래");
  await expect(page.locator(".match-card")).toHaveCount(1);
});

for (const width of [390, 1440]) {
  test(`기업별 보기: 최고 등급 순 카드, 기업 페이지 이동, 기업 목록으로 복귀 (${width}px)`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/match");
    await expect(page.locator(".match-card")).toHaveCount(5);
    if (width === 390) {
      // 카드 안의 기업 링크도 44px 이상(손가락으로 누르기 쉽게)
      for (const link of await page.locator(".match-company-link").all()) {
        expect((await link.boundingBox())!.height).toBeGreaterThanOrEqual(44);
      }
    }
    const toggle = page.getByRole("group", { name: "보기 방식" });
    await expect(toggle.getByRole("button", { name: "공고별" })).toHaveAttribute("aria-pressed", "true");
    await toggle.getByRole("button", { name: "기업별" }).click();
    await expect(page).toHaveURL(/[?&]view=company/);
    await expect(toggle.getByRole("button", { name: "기업별" })).toHaveAttribute("aria-pressed", "true");

    const cards = page.locator(".match-card--company");
    await expect(cards).toHaveCount(5);
    // 최고 등급 A → B(이름 순) → C → D
    await expect(cards.getByRole("heading", { level: 2 })).toHaveText(["가온소프트", "나래랩스", "다온페이", "라온모빌리티", "마루데이터"]);
    await expect(cards.first().getByLabel("최고 등급 A")).toBeVisible();
    await expect(cards.first().getByText("공고 1건")).toBeVisible();
    expect(await overflowFree(page)).toBe(true);

    // 누르기 쉬운 크기(44px)
    for (const button of await toggle.getByRole("button").all()) {
      const box = (await button.boundingBox())!;
      expect(box.height).toBeGreaterThanOrEqual(44);
    }

    await cards.filter({ hasText: "다온페이" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "다온페이" })).toBeVisible();
    await expect(page.getByLabel("최고 등급 B")).toBeVisible();
    expect(await overflowFree(page)).toBe(true);

    await page.getByRole("link", { name: "← 기업 목록" }).click();
    await expect(page).toHaveURL(/[?&]view=company/);
    await expect(page.locator(".match-card--company")).toHaveCount(5);
    await expect(page.getByRole("group", { name: "보기 방식" }).getByRole("button", { name: "기업별" })).toHaveAttribute("aria-pressed", "true");
  });
}

test("기업 페이지는 주소로 바로 열어도 새로고침해도 보이고, 모르는 기업은 안내와 목록 링크를 보인다", async ({ page }) => {
  const path = `/match/company/${encodeURIComponent("다온페이")}`;
  await page.goto(path);
  await expect(page.getByRole("heading", { level: 1, name: "다온페이" })).toBeVisible();
  await expect(page.locator(".match-card")).toHaveCount(1);
  await page.reload();
  await expect(page.getByRole("heading", { level: 1, name: "다온페이" })).toBeVisible();

  await page.goto(`/match/company/${encodeURIComponent("없는회사")}`);
  await expect(page.getByText("이 기업의 공고를 찾지 못했어요")).toBeVisible();
  await expect(page.locator(".match-card")).toHaveCount(0);
  await page.getByRole("link", { name: "전체 공고 보기" }).click();
  await expect(page.locator(".match-card")).toHaveCount(5);
});

test("기업 마크는 스크린리더에 숨기고(aria-hidden), 기업 이름은 항상 글로 있다", async ({ page }) => {
  await page.goto("/match");
  await expect(page.locator(".match-card")).toHaveCount(5);
  const marks = page.locator(".match-card .company-mark");
  await expect(marks).toHaveCount(5);
  for (const mark of await marks.all()) await expect(mark).toHaveAttribute("aria-hidden", "true");
  await expect(page.getByRole("link", { name: "가온소프트", exact: true })).toBeVisible();
});

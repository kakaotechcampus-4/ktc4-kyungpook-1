import { expect, test } from "@playwright/test";
import { loginAsDemo } from "./helpers";

test.beforeEach(async ({ page }) => {
  await loginAsDemo(page);
});

const overflowFree = (page: import("@playwright/test").Page) =>
  page.evaluate(() => document.documentElement.scrollWidth <= innerWidth);

// 타일의 어느 자리를 눌러도 공고 상세다 — 제목 링크의 ::after 가 카드 전체를 덮어서, 요소 가운데가 아닌 화면 좌표로 눌러 실제로 닿는 곳을 시험한다
const clickCenter = async (locator: import("@playwright/test").Locator, page: import("@playwright/test").Page) => {
  const box = (await locator.boundingBox())!;
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
};

test("목록 → 공고 상세 → 기업 페이지 → 뒤로: 타일의 회사 줄을 눌러도 공고 상세로 가고, 기업 페이지는 상세의 회사 링크로 가며, 목록의 검색어가 그대로 남는다", async ({ page }) => {
  await page.goto("/match");
  const search = page.getByPlaceholder("기업이나 직무 검색");
  await expect(page.locator(".match-card")).toHaveCount(5);
  await search.fill("나래");
  await expect(page.locator(".match-card")).toHaveCount(1);

  // 타일 맨 위 회사 줄을 눌러도 기업 페이지가 아니라 공고 상세다
  await clickCenter(page.locator(".match-card .posting-company"), page);
  await expect(page.getByRole("heading", { name: "서버 개발자 (신입)", exact: true })).toBeVisible();
  await expect(page).not.toHaveURL(/\/match\/company\//);

  // 상세 머리말의 기업 링크 → 기업 페이지 (주소 조각은 서버의 companyId, 검색어를 주소로 이어 간다)
  await page.getByRole("link", { name: "나래랩스", exact: true }).click();
  await expect(page).toHaveURL(/\/match\/company\/narae\?q=/);
  await expect(page.getByRole("heading", { level: 1, name: "나래랩스" })).toBeVisible();
  await expect(page.getByRole("status").filter({ hasText: "공고 1건" })).toBeVisible();
  await expect(page.locator(".match-card").getByText("등급")).toHaveCount(0); // 목록 카드에는 등급이 없다
  await expect(page.locator(".match-card")).toHaveCount(1);
  expect(await overflowFree(page)).toBe(true);

  // 기업 페이지의 카드 → 공고 상세
  await page.locator(".match-card").click();
  await expect(page.getByRole("heading", { name: "서버 개발자 (신입)", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "나래랩스", exact: true })).toBeVisible(); // 상세 머리말의 기업 링크

  // 브라우저 뒤로 → 기업 페이지
  await page.goBack();
  await expect(page.getByRole("heading", { level: 1, name: "나래랩스" })).toBeVisible();

  // 돌아가는 링크 → 같은 검색어의 목록
  await page.getByRole("link", { name: "공고 목록으로" }).click();
  await expect(page.getByRole("heading", { name: "공고 매칭", exact: true })).toBeVisible();
  await expect(search).toHaveValue("나래");
  await expect(page.locator(".match-card")).toHaveCount(1);
});

for (const width of [390, 1440]) {
  test(`타일에는 기업 페이지 링크가 없고, 기업 페이지는 공고 상세의 회사 링크로 가며, 공고 목록으로 돌아온다 (${width}px)`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/match");
    await expect(page.locator(".match-card")).toHaveCount(5);
    await expect(page.locator(".match-card a[href*='/match/company']")).toHaveCount(0);
    await page.locator(".match-card").filter({ hasText: "다온페이" }).click();
    await page.getByRole("link", { name: "다온페이", exact: true }).click(); // 상세 머리말의 기업 링크
    await expect(page.getByRole("heading", { level: 1, name: "다온페이" })).toBeVisible();
    await expect(page.locator(".match-card").getByText("등급")).toHaveCount(0);
    expect(await overflowFree(page)).toBe(true);

    await page.getByRole("link", { name: "공고 목록으로" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "공고 매칭" })).toBeVisible();
    await expect(page.locator(".match-card")).toHaveCount(5);
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
  await expect(page.locator(".match-card .posting-company__name").filter({ hasText: "가온소프트" })).toBeVisible();
  await expect(page.getByRole("link", { name: "가온소프트", exact: true })).toHaveCount(0);
});

test("공고 카드에 근거 등급 배지와 필수·우대 줄이 없다 — 상세의 조건·근거는 그대로", async ({ page }) => {
  await page.goto("/match");
  await expect(page.locator(".match-card")).toHaveCount(5);
  await expect(page.locator(".match-card .match-fit, .match-card .match-fit-empty, .match-card .match-counts")).toHaveCount(0);
  await expect(page.locator(".match-card").filter({ hasText: /필수|우대|근거 등급/ })).toHaveCount(0);
  await page.locator(".match-card").filter({ hasText: "서버 개발자 (신입)" }).click();
  // 상세는 원문 절(자격 요건)로 시작하고, 확정 카드가 있어서 열자마자 스스로 맞춰 보기를 시작한다
  await expect(page.getByRole("heading", { name: "자격 요건", exact: true })).toBeVisible();
  await expect(page.getByRole("list", { name: "AI 가 한 일" })).toBeVisible();
});

test("기업 페이지 주소는 companyId 이고, 이름으로 만든 옛 주소도 같은 기업으로 간다", async ({ page }) => {
  await page.goto("/match");
  await page.locator(".match-card").filter({ hasText: "다온페이" }).click();
  await page.getByRole("link", { name: "다온페이", exact: true }).click(); // 상세 머리말의 기업 링크
  await expect(page).toHaveURL(/\/match\/company\/daon$/);
  await expect(page.getByRole("heading", { level: 1, name: "다온페이" })).toBeVisible();
  await page.goto(`/match/company/${encodeURIComponent("다온페이")}`); // 옛 주소
  await expect(page.getByRole("heading", { level: 1, name: "다온페이" })).toBeVisible();
  await expect(page.locator(".match-card")).toHaveCount(1);
});

const LOGO_HOST = "https://logo.example.com/**";
const SVG = `<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64"><rect width="64" height="64" rx="12" fill="#2f6f4f"/><circle cx="32" cy="32" r="14" fill="#ffffff"/></svg>`;

for (const width of [390, 1440]) {
  test(`기업 마크: https 로고는 장식 이미지로 뜨고 크기가 그대로이며, 불러오지 못하면 이니셜로 되돌아간다 (${width}px)`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const requests: Record<string, string | undefined>[] = [];
    page.on("request", (request) => {
      if (request.url().startsWith("https://logo.example.com/")) requests.push(request.headers());
    });
    // 실제 네트워크로 나가지 않게 로고 호스트를 가로챈다
    await page.route(LOGO_HOST, (route) => route.fulfill({ status: 200, contentType: "image/svg+xml", body: SVG }));
    await page.goto("/match");
    await expect(page.locator(".match-card")).toHaveCount(5);

    const logos = page.locator(".match-card .company-mark--logo");
    await expect(logos).toHaveCount(1); // 데모 목은 한 곳만 로고가 있다
    const img = logos.locator("img");
    await expect(img).toHaveAttribute("alt", "");
    await expect(img).toHaveAttribute("referrerpolicy", "no-referrer");
    await expect(img).toHaveAttribute("loading", "lazy");
    await expect(logos).toHaveAttribute("aria-hidden", "true");
    await expect.poll(() => img.evaluate((el: HTMLImageElement) => el.complete && el.naturalWidth > 0)).toBe(true);
    // 이미지가 와도 칸 크기는 공고 카드의 32px 그대로, 옆의 기업 이름은 글로 있다
    const box = (await logos.boundingBox())!;
    expect(Math.round(box.width)).toBe(32);
    expect(Math.round(box.height)).toBe(32);
    await expect(page.locator(".match-card .posting-company__name").filter({ hasText: "가온소프트" })).toBeVisible();
    expect(await overflowFree(page)).toBe(true);
    expect(requests.length).toBeGreaterThan(0);
    for (const headers of requests) expect(headers["referer"]).toBeUndefined();
  });
}

test("기업 마크: 로고를 불러오지 못하면 이니셜로 되돌아가고 레이아웃이 그대로다", async ({ page }) => {
  await page.route(LOGO_HOST, (route) => route.abort());
  await page.goto("/match");
  await expect(page.locator(".match-card")).toHaveCount(5);
  await expect(page.locator(".match-card .company-mark--logo")).toHaveCount(0);
  await expect(page.locator(".match-card img")).toHaveCount(0);
  const gaon = page.locator(".match-card").filter({ hasText: "가온소프트" }).locator(".company-mark");
  await expect(gaon).toHaveText("가");
  await expect(gaon).toHaveAttribute("aria-hidden", "true");
  const box = (await gaon.boundingBox())!;
  expect(Math.round(box.width)).toBe(32);
  expect(Math.round(box.height)).toBe(32);
});

test("공고 타일 맨 위 로고·회사 이름을 눌러도 기업 페이지가 아니라 공고 상세로 간다 (1440px 밝게)", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.addInitScript((value) => localStorage.setItem("gitory.theme", value), "light");
  await page.goto("/match");
  await expect(page.locator(".match-card")).toHaveCount(5);
  await page.screenshot({ path: "docs/screenshots/2026-11-04-match-tile-link/match-1440-light.png", animations: "disabled" });
  for (const part of [".company-mark", ".posting-company__name"]) {
    await clickCenter(page.locator(".match-card").filter({ hasText: "가온소프트" }).locator(part), page);
    await expect(page.getByRole("heading", { name: "백엔드 엔지니어 (Java·Spring)", exact: true })).toBeVisible();
    await expect(page).not.toHaveURL(/\/match\/company\//);
    await page.goBack();
    await expect(page.locator(".match-card")).toHaveCount(5);
  }
});

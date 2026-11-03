import { expect, test, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

/**
 * #755 — 공고 매칭은 한 목록이다. 맞춰 본 공고는 '나와 맞는 공고' 타일로 오르고 타일 안에 맞는 정도 한 줄이 있다.
 * 필수 조건을 하나도 못 채운 공고는 '아직 부족한 공고' 로 접고, 조작은 검색과 직무 칩뿐이다.
 * 데모의 맞춰 본 기업 5곳: 가온(3/3) · 나래(2/4) · 라온(1/3) · 다온(필수 없음) · 마루(0/2, 부족)
 */
test.beforeEach(async ({ page }) => {
  await loginAsDemo(page);
});

const overflowFree = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth);
async function matchOnce(page: Page) {
  await page.getByRole('button', { name: '내 조건으로 맞춰 보기', exact: true }).click();
  await expect(page.getByRole('button', { name: '다시 맞춰 보기', exact: true })).toBeEnabled({ timeout: 15_000 });
}

test('맞춰 보기 전: 묶음 없이 공고 N건 목록이고, 버튼은 검은 내 조건으로 맞춰 보기다', async ({ page }) => {
  await page.goto('/match');
  await expect(page.getByRole('heading', { level: 2, name: '공고 5건' })).toBeVisible();
  await expect(page.locator('.match-card')).toHaveCount(5);
  await expect(page.getByRole('region', { name: '나와 맞는 공고' })).toHaveCount(0);
  await expect(page.locator('.posting-fit')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '내 조건으로 맞춰 보기', exact: true })).toHaveClass(/btn--primary/);
});

test('맞춰 본 뒤: 맞춰 본 공고 5개가 나와 맞는 공고와 아직 부족한 공고로 오르고 타일 안에 맞는 정도 한 줄이 있다', async ({ page }) => {
  await page.goto('/match');
  await matchOnce(page);
  const fit = page.getByRole('region', { name: '나와 맞는 공고' });
  await expect(fit.locator('.match-card')).toHaveCount(4);
  await expect(fit.locator('.posting-fit')).toHaveCount(4);
  await expect(fit.locator('.match-card').filter({ hasText: '가온소프트' }).locator('.posting-fit')).toHaveText('필수 조건 3개를 모두 채웠어요, 우대 2개 중 1개가 맞아요');
  await expect(fit.locator('.match-card').filter({ hasText: '나래랩스' }).locator('.posting-fit')).toHaveText('필수 조건 4개 중 2개를 채웠어요');
  await expect(fit.locator('.match-card').filter({ hasText: '다온페이' }).locator('.posting-fit')).toHaveText('기술·경험 필수 조건이 없어요, 우대 2개 중 1개가 맞아요');

  const gap = page.getByRole('region', { name: '아직 부족한 공고' });
  await expect(gap.locator('.match-card')).toHaveCount(0);
  await gap.getByRole('button', { name: '부족한 공고 1개 보기' }).click();
  await expect(gap.locator('.match-card')).toHaveCount(1);
  await expect(gap.locator('.posting-fit')).toHaveText('필수 조건 2개 중 0개를 채웠어요');
  await expect(gap).toContainText('마루데이터');

  // 같은 공고를 두 번 보여 주던 '나와 맞는 기업' 상자는 없다
  await expect(page.getByText('나와 맞는 기업')).toHaveCount(0);
  await expect(page.locator('.match-card')).toHaveCount(5);
});

test('직무 칩을 고르면 목록이 그 직무 공고만 남고 주소에 직무가 남는다', async ({ page }) => {
  await page.goto('/match');
  await expect(page.locator('.match-card')).toHaveCount(5);
  const chips = page.getByRole('group', { name: '직무' });
  await expect(chips.getByRole('button', { name: '전체', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await chips.getByRole('button', { name: '데이터', exact: true }).click();
  await expect(chips.getByRole('button', { name: '데이터', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('heading', { level: 2, name: '공고 1건' })).toBeVisible();
  await expect(page.locator('.match-card')).toHaveCount(1);
  await expect(page.locator('.match-card')).toContainText('마루데이터');
  await expect.poll(() => new URL(page.url()).searchParams.get('category')).toBe('DATA');
});

test('공고별/기업별 전환 · 경력과 정렬 고르기 · 목록 새로고침이 없다', async ({ page }) => {
  await page.goto('/match');
  await expect(page.locator('.match-card')).toHaveCount(5);
  await expect(page.getByRole('group', { name: '보기 방식' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '기업별', exact: true })).toHaveCount(0);
  await expect(page.getByRole('combobox')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '목록 새로고침' })).toHaveCount(0);
});

test('390px: 맞춰 본 뒤에도 가로로 넘치지 않고, 칩과 버튼은 44px 이상이다', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto('/match');
  await matchOnce(page);
  await page.getByRole('button', { name: '부족한 공고 1개 보기' }).click();
  expect(await overflowFree(page)).toBe(true);
  const targets = [
    ...(await page.getByRole('group', { name: '직무' }).getByRole('button').all()),
    page.getByRole('button', { name: '다시 맞춰 보기', exact: true }),
    page.getByRole('button', { name: '부족한 공고 접기' }),
  ];
  for (const target of targets) expect((await target.boundingBox())!.height).toBeGreaterThanOrEqual(44);
});

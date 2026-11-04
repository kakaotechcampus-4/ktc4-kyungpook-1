import { expect, test, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

/**
 * #755 — 공고 매칭은 두 칸이다. 맨 위 '내 카드로 맞춰 본 공고' 상자(순위 번호가 붙은 타일, 필수 개수 줄, 맞은·모자란 조건 이름 줄)와
 * 그 아래 '모든 공고'(검색·직무 칩으로 거르는 전체 목록). 칩이 걸리면 상자는 한 줄 띠로 접힌다.
 * 데모의 맞춰 본 기업 5곳: 가온(3/3) · 나래(2/4) · 라온(1/3) · 다온(필수 없음) · 마루(0/2)
 */
test.beforeEach(async ({ page }) => {
  await loginAsDemo(page);
});

const overflowFree = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth);
async function matchOnce(page: Page) {
  await page.getByRole('button', { name: '내 조건으로 맞춰 보기', exact: true }).click();
  await expect(page.getByRole('button', { name: '다시 맞춰 보기', exact: true })).toBeEnabled({ timeout: 15_000 });
}
const BOX = '내 카드로 맞춰 본 공고';

test('맞춰 보기 전: 상자는 안내와 검은 버튼이고 모든 공고는 5건 목록이다', async ({ page }) => {
  await page.goto('/match');
  const box = page.getByRole('region', { name: BOX });
  await expect(box.getByRole('heading', { level: 2, name: '내 카드로 공고 맞춰 보기' })).toBeVisible();
  await expect(box.locator('.result-tile')).toHaveCount(0);
  const all = page.getByRole('region', { name: '모든 공고' });
  await expect(all.locator('.match-card')).toHaveCount(5);
  await expect(all.getByText('5건', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '내 조건으로 맞춰 보기', exact: true })).toHaveClass(/btn--primary/);
});

test('맞춰 본 뒤: 상자에 순위 번호가 붙은 타일 5개가 오르고 타일마다 개수 줄과 이름 줄이 있다', async ({ page }) => {
  await page.goto('/match');
  await matchOnce(page);
  const box = page.getByRole('region', { name: BOX });
  const tiles = box.locator('.result-tile');
  await expect(tiles).toHaveCount(5);
  await expect(box.getByRole('heading', { level: 2, name: `${BOX} 5곳` })).toBeVisible();
  await expect(box).toContainText(/확정 카드 \d+장과 인적사항으로 기술이 겹치는 15곳을 확인했어요/);
  await expect(tiles.locator('.star-key')).toHaveText(['1', '2', '3', '4', '5']);

  const tile = (company: string) => tiles.filter({ hasText: company });
  await expect(tile('가온소프트').locator('.result-tile__count')).toHaveText('필수 3개 모두 맞아요 · 우대 1개');
  await expect(tile('가온소프트').locator('.result-tile__names')).toHaveText('Java·Spring, PostgreSQL 등 3개가 맞아요');
  await expect(tile('나래랩스').locator('.result-tile__count')).toHaveText('필수 4개 중 2개 맞아요');
  await expect(tile('나래랩스').locator('.result-tile__names')).toHaveText('Kafka, 메시지 큐 운영 경험이 아직 없어요');
  await expect(tile('다온페이').locator('.result-tile__count')).toHaveText('기술과 경험 필수 조건이 없어요, 우대 2개 중 1개가 맞아요');
  await expect(tile('다온페이').locator('.result-tile__names')).toHaveCount(0);
  await expect(tile('마루데이터').locator('.result-tile__names')).toHaveText('Spark, Airflow 경험이 아직 없어요');

  // 모든 공고는 상자에 오른 공고까지 포함한 전체 목록 그대로다
  await expect(page.getByRole('region', { name: '모든 공고' }).locator('.match-card')).toHaveCount(5);
});

test('직무 칩을 고르면 모든 공고가 그 직무 공고만 남고 주소에 직무가 남는다', async ({ page }) => {
  await page.goto('/match');
  await expect(page.locator('.match-card')).toHaveCount(5);
  const chips = page.getByRole('group', { name: '직무' });
  await expect(chips.getByRole('button', { name: '전체', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await chips.getByRole('button', { name: '데이터', exact: true }).click();
  await expect(chips.getByRole('button', { name: '데이터', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('region', { name: '모든 공고' }).getByText('1건', { exact: true })).toBeVisible();
  await expect(page.locator('.match-card')).toHaveCount(1);
  await expect(page.locator('.match-card')).toContainText('마루데이터');
  await expect.poll(() => new URL(page.url()).searchParams.get('category')).toBe('DATA');
});

test('칩이 걸리면 상자는 한 줄 띠로 접히고, 펼치면 타일이 그대로이며, 칩을 풀면 다시 펼쳐진다', async ({ page }) => {
  await page.goto('/match');
  await matchOnce(page);
  const chips = page.getByRole('group', { name: '직무' });
  const box = page.getByRole('region', { name: BOX });
  await chips.getByRole('button', { name: '데이터', exact: true }).click();
  await expect(box.getByText(`${BOX} 5곳`)).toBeVisible();
  await expect(box.locator('.result-tile')).toHaveCount(0);

  await box.getByRole('button', { name: '펼치기' }).click();
  await expect(box.locator('.result-tile')).toHaveCount(5);
  await expect(box.getByRole('button', { name: '접기' })).toHaveAttribute('aria-expanded', 'true');

  await chips.getByRole('button', { name: '전체', exact: true }).click();
  await expect(box.getByRole('button', { name: '접기' })).toHaveCount(0);
  await expect(box.locator('.result-tile')).toHaveCount(5);
});

test('공고별/기업별 전환 · 경력과 정렬 고르기 · 목록 새로고침이 없다', async ({ page }) => {
  await page.goto('/match');
  await expect(page.locator('.match-card')).toHaveCount(5);
  await expect(page.getByRole('group', { name: '보기 방식' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '기업별', exact: true })).toHaveCount(0);
  await expect(page.getByRole('combobox')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '목록 새로고침' })).toHaveCount(0);
});

test('390px: 맞춰 본 뒤에도 접어도 가로로 넘치지 않고, 칩과 버튼은 44px 이상이다', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto('/match');
  await matchOnce(page);
  expect(await overflowFree(page)).toBe(true);
  await page.getByRole('group', { name: '직무' }).getByRole('button', { name: '데이터', exact: true }).click();
  await expect(page.getByRole('button', { name: '펼치기' })).toBeVisible();
  expect(await overflowFree(page)).toBe(true);
  const targets = [
    ...(await page.getByRole('group', { name: '직무' }).getByRole('button').all()),
    page.getByRole('button', { name: '펼치기' }),
  ];
  for (const target of targets) expect((await target.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  await page.getByRole('button', { name: '펼치기' }).click();
  expect(await overflowFree(page)).toBe(true);
  expect((await page.getByRole('button', { name: '다시 맞춰 보기', exact: true }).boundingBox())!.height).toBeGreaterThanOrEqual(44);
});

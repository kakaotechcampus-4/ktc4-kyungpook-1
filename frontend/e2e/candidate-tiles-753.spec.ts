import { expect, test, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

/** #753 후보 보드 — 후보 하나를 타일 하나로, 추천 5개만 먼저 */
type Cand = { id: string; title: string; status: string; weak: boolean; score: number; type: string; commits: unknown[] | null };
const board = (page: Page) => page.evaluate(async () => (await (await fetch('/api/repos/r_auth/candidates')).json()).data.candidates as Cand[]);
const tiles = (page: Page) => page.locator('.cand-tiles .cand-tile__pick');

test.beforeEach(async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/repos/r_auth/candidates');
  await expect(tiles(page).first()).toBeVisible();
});

test('처음엔 점수 높은 추천 5개만 보이고, 나머지 N개 더 보기를 누르면 나머지가 펼쳐진다', async ({ page }) => {
  const open = (await board(page)).filter((c) => c.status === 'NEW');
  const top5 = open.filter((c) => !c.weak).sort((a, b) => b.score - a.score).slice(0, 5).map((c) => c.title);
  await expect(tiles(page)).toHaveCount(5);
  expect(await page.locator('.cand-tiles .cand-tile__title').allInnerTexts()).toEqual(top5);
  await expect(page.locator('.cand-grp__meta').first()).toHaveText(`5개, 전체 ${open.length}개`);
  const more = page.getByRole('button', { name: `나머지 ${open.length - 5}개 더 보기` });
  await expect(more).toHaveAttribute('aria-expanded', 'false');
  await more.click();
  await expect(tiles(page)).toHaveCount(open.length);
  const fold = page.getByRole('button', { name: '처음 5개만 보기' });
  await expect(fold).toHaveAttribute('aria-expanded', 'true');
  await fold.click();
  await expect(tiles(page)).toHaveCount(5);
});

test('타일 전체가 고르기 버튼이고, 10개를 고르면 열한 번째는 골라지지 않고 안내가 뜬다', async ({ page }) => {
  await page.getByRole('button', { name: /^나머지 \d+개 더 보기$/ }).click();
  const first = tiles(page).first();
  await first.locator('.cand-tile__reason').click();
  await expect(first).toHaveAttribute('aria-pressed', 'true');
  await expect(first).toContainText('골랐어요');
  await first.click();
  await expect(first).toHaveAttribute('aria-pressed', 'false');
  for (let i = 0; i < 10; i += 1) await tiles(page).nth(i).click();
  const eleventh = tiles(page).nth(10);
  await eleventh.click();
  await expect(eleventh).toHaveAttribute('aria-pressed', 'false');
  await expect(page.locator('.toast')).toContainText('카드는 한 번에 10개까지 만들 수 있어요');
  await expect(page.locator('.cand-tiles [aria-pressed="true"]')).toHaveCount(10);
});

test('아래 막대는 고른 수와 걸리는 시간을 말하고, 카드 만들기 하나만 둔다', async ({ page }) => {
  const footer = page.locator('.sticky-footer');
  await expect(footer.locator('.sticky-footer__text')).toHaveText('카드로 남길 일을 고르면 초안을 써요10개까지 고를 수 있어요');
  await expect(footer.getByRole('button')).toHaveCount(1);
  await expect(footer.getByRole('button', { name: '카드 만들기' })).toBeDisabled();
  await tiles(page).nth(0).click();
  await tiles(page).nth(1).click();
  await expect(footer.locator('.sticky-footer__text')).toHaveText('2개 골랐어요10개까지 고를 수 있고, 하나에 1~2분쯤 걸려요');
  await expect(footer.getByRole('button', { name: '카드 만들기' })).toBeEnabled();
});

test('커밋 묶음의 커밋 목록은 처음엔 접혀 있고, 커밋 N개 보기를 눌러도 그 후보가 골라지지 않는다', async ({ page }) => {
  const cluster = (await board(page)).find((c) => c.type === 'COMMIT_CLUSTER' && c.commits && c.commits.length > 0)!;
  const tile = page.locator('.cand-tile').filter({ hasText: cluster.title });
  await expect(tile.locator('.commit-row')).toHaveCount(0);
  await tile.getByRole('button', { name: `커밋 ${cluster.commits!.length}개 보기` }).click();
  await expect(tile.locator('.commit-row')).toHaveCount(cluster.commits!.length);
  await expect(tile.locator('.cand-tile__pick')).toHaveAttribute('aria-pressed', 'false');
  await expect(page.locator('.sticky-footer__text')).toContainText('카드로 남길 일을 고르면 초안을 써요');
});

test('390px 에서 나머지를 펼치고 커밋 목록을 열어도 가로로 넘치지 않는다', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: /^나머지 \d+개 더 보기$/ }).click();
  await page.getByRole('button', { name: /^커밋 \d+개 보기$/ }).first().click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  for (const box of await page.locator('.cand-tile').evaluateAll((els) => els.map((el) => el.getBoundingClientRect().right))) expect(box).toBeLessThanOrEqual(390);
});

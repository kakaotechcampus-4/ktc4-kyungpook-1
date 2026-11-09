import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

test('reading and toggling an expanded commit never selects its parent candidate', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/repos/r_auth/candidates');
  const cluster = page.locator('.cand-tile').filter({ has: page.locator('.cand-tile__commits') }).first();
  const parent = cluster.locator('.cand-tile__pick');
  await expect(parent).toHaveAttribute('aria-pressed','false');
  await cluster.getByRole('button',{name:/^커밋 \d+개 보기$/}).click();
  await cluster.locator('.commit-row__msg').first().click();
  await expect(parent).toHaveAttribute('aria-pressed','false');
  const commitCheck = cluster.locator('.commit-row').first().getByRole('checkbox');
  await commitCheck.click(); await expect(commitCheck).toHaveAttribute('aria-checked','false');
  await expect(parent).toHaveAttribute('aria-pressed','false');
  await expect(page.locator('.sticky-footer__text')).toContainText('카드로 남길 일을 고르면 초안을 써요');
});

for (const width of [1440, 390, 320]) test(`${width}px candidate tiles and commit controls remain usable`, async ({ page }) => {
  await page.setViewportSize({ width, height: 900 });
  await loginAsDemo(page); await page.goto('/repos/r_auth/candidates');
  const first = page.locator('.cand-tile__pick').first(); await expect(first).toBeVisible();
  const box = await first.boundingBox(); expect(box!.height).toBeGreaterThanOrEqual(44);
  await first.focus(); await page.keyboard.press('Space'); await expect(first).toHaveAttribute('aria-pressed','true');
  const open = page.getByRole('button',{name:/^커밋 \d+개 보기$/}).first();
  expect((await open.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  await open.click();
  const commit = page.locator('.commit-row').first(); const commitCheck = commit.getByRole('checkbox');
  const commitBox = await commitCheck.boundingBox(); expect(commitBox!.width).toBeGreaterThanOrEqual(44);
  const textWidth = await commit.locator('.commit-row__msg').evaluate(e => e.getBoundingClientRect().width);
  expect(textWidth).toBeGreaterThanOrEqual(width === 320 ? 130 : 170);
  await expect(first).toHaveAttribute('aria-pressed','true');
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
});

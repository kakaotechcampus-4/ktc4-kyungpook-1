import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

test('candidate checkbox shares the heading center instead of floating above the title', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/repos/r_auth/candidates');
  const row = page.locator('.cand').first();
  const check = await row.getByRole('checkbox').boundingBox(); const title = await row.locator('.cand__title').boundingBox();
  expect(Math.abs(check!.y + check!.height / 2 - title!.y - title!.height / 2)).toBeLessThanOrEqual(2);
});

test('reading and toggling an expanded commit never selects its parent candidate', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/repos/r_auth/candidates');
  const cluster = page.locator('.cand').filter({ has: page.locator('.cand__expand') }).first();
  const parentCheck = cluster.locator(':scope > .check');
  await expect(parentCheck).toHaveAttribute('aria-checked','false');
  await cluster.getByRole('button',{name:/펼치기/}).click();
  await cluster.locator('.commit-row__msg').first().click();
  await expect(parentCheck).toHaveAttribute('aria-checked','false');
  const commitCheck = cluster.locator('.commit-row').first().getByRole('checkbox');
  await commitCheck.click(); await expect(commitCheck).toHaveAttribute('aria-checked','false');
  await expect(parentCheck).toHaveAttribute('aria-checked','false');
  await expect(page.locator('.sticky-footer__text')).toContainText('0개 선택');
});

test('recommendation respects server eligibility and leaves used and weak candidates unselected', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/repos/r_auth/candidates');
  await expect(page.locator('.cand').first()).toBeVisible();
  const candidates = await page.evaluate(async () => (await (await fetch('/api/repos/r_auth/candidates')).json()).data.candidates as {title:string;status:string;weak:boolean}[]);
  await page.getByRole('button',{name:'추천 후보 선택',exact:true}).click();
  for(const candidate of candidates.filter(c=>c.status!=='EXCLUDED')) {
    const check=page.getByRole('checkbox',{name:`${candidate.title} 선택`,exact:true});
    await expect(check).toHaveAttribute('aria-checked',String(candidate.status==='NEW'&&!candidate.weak));
    if(candidate.status==='USED')await expect(check).toBeDisabled();
  }
});

for (const width of [1440, 390, 320]) test(`${width}px candidate selection and commit controls align and remain usable`, async ({ page }) => {
  await page.setViewportSize({ width, height: 900 });
  await loginAsDemo(page); await page.goto('/repos/r_auth/candidates');
  const first = page.locator('.cand').first();
  const check = first.getByRole('checkbox'); await expect(check).toBeVisible();
  const box = await check.boundingBox(); expect(box!.width).toBeGreaterThanOrEqual(44); expect(box!.height).toBeGreaterThanOrEqual(44);
  const title = await first.locator('.cand__title').boundingBox(); expect(Math.abs(box!.y + box!.height / 2 - title!.y - title!.height / 2)).toBeLessThanOrEqual(2);
  await check.focus(); await page.keyboard.press('Space'); await expect(check).toHaveAttribute('aria-checked','true');
  await page.getByRole('button',{name:/커밋 .*펼치기/}).first().click();
  const commit = page.locator('.commit-row').first(); const commitCheck = commit.getByRole('checkbox');
  const commitBox = await commitCheck.boundingBox(); expect(commitBox!.width).toBeGreaterThanOrEqual(44);
  const textWidth = await commit.locator('.commit-row__msg').evaluate(e => e.getBoundingClientRect().width);
  expect(textWidth).toBeGreaterThanOrEqual(width === 320 ? 130 : 170);
  await expect(first.getByRole('checkbox')).toHaveAttribute('aria-checked','true');
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
});

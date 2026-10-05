import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

test('desktop initial save action does not stretch across the writing canvas', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await loginAsDemo(page); await page.goto('/cards/new');
  const save = await page.getByRole('button', { name: '임시 저장 시작' }).boundingBox();
  expect(save!.width).toBeLessThan(250);
});

test('mobile metadata keeps period and repository on the same aligned row', async ({ page }) => {
  await loginAsDemo(page);
  for (const width of [320, 390, 768]) {
    await page.setViewportSize({ width, height: 900 }); await page.goto('/cards/new');
    const period = await page.getByRole('textbox', { name: '기간' }).boundingBox();
    const repo = await page.getByRole('combobox', { name: '관련 레포', exact: true }).boundingBox();
    expect(Math.abs(period!.y - repo!.y)).toBeLessThan(2);
    expect(Math.abs(period!.width - repo!.width)).toBeLessThan(2);
    expect(repo!.x + repo!.width).toBeLessThanOrEqual(width - 12);
  }
});

test('mobile explanatory notes use full reading width below their heading', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await loginAsDemo(page); await page.goto('/repos/r_auth/recall');
  const note = page.locator('.note').first();
  await expect(note).toBeVisible();
  const layout = await note.evaluate((el) => {
    const title = el.querySelector('strong')!.getBoundingClientRect();
    const content = el.querySelector('span')!.getBoundingClientRect();
    return { titleBottom: title.bottom, contentTop: content.top, contentWidth: content.width, width: el.getBoundingClientRect().width };
  });
  expect(layout.contentTop).toBeGreaterThanOrEqual(layout.titleBottom);
  expect(layout.contentWidth).toBeGreaterThan(layout.width * .75);
});

test('dialog actions stay visible on a short landscape viewport', async ({ page }) => {
  await page.setViewportSize({ width: 844, height: 390 });
  await loginAsDemo(page); await page.goto('/repos?select=r_auth&disclose=1');
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('button', { name: '정리 시작' })).toBeEnabled();
  const box = await dialog.boundingBox();
  expect(box!.y).toBeGreaterThanOrEqual(8);
  expect(box!.y + box!.height).toBeLessThanOrEqual(390);
  const action = await dialog.getByRole('button', { name: '정리 시작' }).boundingBox();
  expect(action!.y + action!.height).toBeLessThanOrEqual(390);
});

test('last mobile writing field remains reachable above footer and navigation', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 });
  await loginAsDemo(page); await page.goto('/cards/new');
  const field = page.getByRole('textbox', { name: '결과 (Result)' });
  await field.scrollIntoViewIfNeeded();
  const fieldBox = await field.boundingBox();
  const footer = await page.locator('.sticky-footer').boundingBox();
  const nav = await page.getByRole('navigation', { name: '모바일 주 메뉴' }).boundingBox();
  expect(fieldBox!.y + fieldBox!.height).toBeLessThanOrEqual(footer!.y);
  expect(footer!.y + footer!.height).toBeLessThanOrEqual(nav!.y + 1);
});

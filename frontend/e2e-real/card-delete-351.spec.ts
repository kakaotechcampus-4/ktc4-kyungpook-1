import { expect, test } from '@playwright/test';
import { CARD_ID, cardFixture, installContractFixture } from './contract-fixture';

for (const status of [200, 404, 409, 503]) test(`DELETE contract ${status} preserves safe navigation and request count`, async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await installContractFixture(page, { card: cardFixture() });
  let deletes = 0;
  await page.route('**/api/cards', route => deletes > 0 && (status === 200 || status === 404)
    ? route.fulfill({ json: { data: [], error: null } }) : route.fallback());
  await page.route(`**/api/cards/${CARD_ID}`, async route => {
    if (route.request().method() !== 'DELETE') return route.fallback();
    deletes++;
    await route.fulfill({ status, json: status === 200 ? { data: { ok: true }, error: null } : {
      data: null, error: { code: status === 409 ? 'INVALID_STATE' : status === 404 ? 'NOT_FOUND' : 'INTERNAL_ERROR', message: 'internal detail' },
    } });
  });
  // Empty server list after success: neither cached detail nor sidebar may resurrect the card.
  await page.goto(`/cards/${CARD_ID}`);
  await page.getByRole('button', { name: '카드 삭제', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: '삭제', exact: true }).click();
  if (status === 200 || status === 404) {
    await expect(page).toHaveURL(/\/cards$/);
    await expect(dialog).toHaveCount(0);
    await expect(page.locator('.experience-item')).toHaveCount(0);
  } else {
    await expect(dialog.getByRole('alert')).toBeVisible();
    if (status === 409) await expect(dialog).toContainText('초안을 만드는 중이에요');
    await expect(page).toHaveURL(new RegExp(`/cards/${CARD_ID}$`));
    await expect(page.getByText('internal detail')).toHaveCount(0);
    await dialog.getByRole('button', { name: '취소', exact: true }).click();
    await expect(page.getByRole('heading', { name: '서버 카드', exact: true })).toBeVisible();
  }
  await page.waitForTimeout(1200);
  expect(deletes).toBe(1);
});

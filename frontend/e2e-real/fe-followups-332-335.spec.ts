import { test, expect } from '@playwright/test';
import { CARD_ID, cardFixture, installContractFixture } from './contract-fixture';

for (const seconds of [30, 32400]) test(`429 (${seconds}s) keeps the confirmation dialog and card content without automatic retransmission`, async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const card = cardFixture({ version: { ...cardFixture().version, situation: '직접 쓴 상황', action: '직접 쓴 행동' } });
  await installContractFixture(page, { card });
  let requests = 0;
  await page.route(`**/api/cards/${CARD_ID}/confirm`, route => {
    requests++;
    return route.fulfill({ status: 429, headers: { 'Retry-After': String(seconds) }, json: { data: null, error: { code: 'RATE_LIMITED', message: 'server internal text', details: { retryAfterSec: seconds } } } });
  });
  await page.goto(`/cards/${CARD_ID}`);
  await page.getByRole('button', { name: '확정', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: '확정하기', exact: true }).click();
  await expect(dialog).toContainText(`${seconds > 60 ? '내일' : `${seconds}초 뒤`} 다시 시도해 주세요. 입력한 내용은 그대로 남아 있어요.`);
  await expect(dialog).not.toContainText('32400초');
  expect(await page.locator('.toaster .toast').count()).toBe(0);
  const message = `${seconds > 60 ? '내일' : `${seconds}초 뒤`} 다시 시도해 주세요. 입력한 내용은 그대로 남아 있어요.`;
  await expect(page.getByText(message, { exact: true })).toHaveCount(1);
  if (seconds > 60) {
    await expect(dialog).toContainText('오늘 사용 한도를 다 썼어요');
    await expect(dialog.getByRole('button', { name: '확정하기', exact: true })).toBeDisabled();
  } else await expect(dialog.getByRole('button', { name: '확정하기', exact: true })).toBeEnabled();
  await expect(page.getByText('server internal text')).toHaveCount(0);
  await page.waitForTimeout(2500); // Observe past normal retry delay; costly writes must not repeat.
  expect(requests).toBe(1);
  await page.screenshot({ path: `docs/screenshots/2026-10-21-followups/rate-limit-${seconds > 60 ? 'daily' : 'minute'}-390.png` });
  await dialog.getByRole('button', { name: '취소', exact: true }).click();
  await expect(page.locator('.star__text').first()).toContainText('직접 쓴 상황');
  await expect(page.locator('.star__text').nth(1)).toContainText('직접 쓴 행동');
});

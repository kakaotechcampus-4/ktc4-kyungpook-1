import { test, expect } from '@playwright/test';
import {
  CARD_ID,
  cardFixture,
  installContractFixture,
} from './contract-fixture';
test('DRAFT response renders card counts instead of four analysis phases', async ({
  page,
}) => {
  const card = cardFixture({
    generation: { jobId: 'draft-326', partial: false },
  });
  await installContractFixture(page, { card });
  await page.route('**/api/jobs/draft-326', (route) =>
    route.fulfill({
      json: {
        data: {
          jobId: 'draft-326',
          type: 'DRAFT',
          state: 'RUNNING',
          partial: false,
          steps: [
            { key: 'COMMITS', state: 'RUNNING', done: 11, total: 40 },
            { key: 'PR_REVIEW', state: 'SKIPPED', done: 0, total: 0 },
            { key: 'COMPRESS', state: 'RUNNING', done: 10, total: 40 },
            { key: 'REASON', state: 'RUNNING', done: 10, total: 40 },
          ],
          errorCode: null,
          retryable: null,
          retryAfterSec: null,
          startedAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
          finishedAt: null,
          result: { cardIds: [CARD_ID] },
          pollAfterMs: 2000,
        },
        error: null,
      },
    }),
  );
  await page.goto(`/cards/${CARD_ID}`);
  await expect(
    page.getByRole('heading', { name: '카드 초안을 쓰고 있어요' }),
  ).toBeVisible({ timeout: 20000 });
  await expect(page.getByText('40장 중 11번째')).toBeVisible();
  await expect(
    page.getByRole('progressbar', { name: '초안 생성 진행률' }),
  ).toHaveAttribute('aria-valuenow', '25');
  await expect(page.locator('.stage')).toHaveCount(0);
  await expect(page.locator('.star-key')).toHaveCount(0);
  await expect(page.locator('.draft-line__pulse')).toBeVisible();
  await expect(
    page.getByRole('link', { name: '홈으로', exact: true }),
  ).toBeVisible();
});

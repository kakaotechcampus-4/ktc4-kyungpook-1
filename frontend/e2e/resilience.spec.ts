import { expect, test } from '@playwright/test';
import { loginAsDemo, freshSeed } from './helpers';

test('demo starts in one action without repeating mode explanations', async ({ page }) => {
  await freshSeed(page); await page.goto('/login');
  const trigger = page.locator('.landing__actions').getByRole('button', { name: '샘플로 체험하기' });
  await trigger.click();
  await expect(page.getByRole('heading', { name: /정리해 볼까요/ })).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(0);
});

test('failed list requests show a recovery action instead of empty results', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/cards?demoScenario=read-error');
  await expect(page.getByRole('heading', { name: '서버에 연결하지 못했어요' })).toBeVisible({ timeout: 15000 });
  await expect(page.getByRole('button', { name: '다시 불러오기' })).toBeVisible();
  await expect(page.getByText('아직 카드가 없어요')).toHaveCount(0);
});

test('missing Job is not treated as successful analysis', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/repos/r_auth/run?job=missing');
  await expect(page.getByRole('heading', { name: '요청한 항목을 찾을 수 없어요' })).toBeVisible();
  await expect(page).toHaveURL(/job=missing/);
  await expect(page.getByRole('button', { name: '정리 시작' })).toHaveCount(0);
});

test('lost analysis response retries the same request without another job', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/repos?select=r_auth&disclose=1&demoScenario=lost-response');
  await page.getByRole('button', { name: '정리 시작' }).click();
  await expect(page.locator('.toast')).toBeVisible();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByRole('button', { name: '정리 시작' }).click();
  await expect(page).toHaveURL(/run\?job=/);
  const jobs = await page.evaluate(async () => (await (await fetch('/api/jobs?active=true')).json()).data.jobs);
  expect(jobs).toHaveLength(1);
});

test('409 leaves the analysis action in place and creates no job', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/repos?select=r_auth&disclose=1&demoScenario=conflict');
  await page.getByRole('button', { name: '정리 시작' }).click();
  await expect(page.locator('.toast')).toContainText('다른 저장소에 사용된 요청');
  await expect(page).toHaveURL(/disclose=1/);
  const jobs = await page.evaluate(async () => (await (await fetch('/api/jobs?active=true')).json()).data.jobs);
  expect(jobs).toHaveLength(0);
});

test('failed save retains text and only successful retry permits leaving', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/cards/card_01?mode=edit&demoScenario=save-error');
  const text = '  실제 입력한 공백과 줄바꿈도\n그대로 보존합니다.  ';
  await page.getByLabel('행동 (Action)').fill(text);
  await page.getByRole('button', { name: '저장하고 닫기', exact: true }).click();
  await expect(page.locator('main').getByRole('status')).toContainText('저장이 안 됐어요');
  await expect(page).toHaveURL(/mode=edit/);
  await expect(page.getByLabel('행동 (Action)')).toHaveValue(text);
  await page.getByRole('button', { name: '다시 저장하고 닫기' }).click();
  await expect(page).not.toHaveURL(/mode=edit/);
  const card = await page.evaluate(async () => (await (await fetch('/api/cards/card_01')).json()).data);
  expect(card.version.action).toBe(text);
});

test('unsent interview answer is guarded during navigation', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/cards/card_01/interview?field=T');
  await page.getByLabel('답변', { exact: true }).fill('사용자 답변');
  await page.getByRole('link', { name: '카드로', exact: true }).click();
  await expect(page.getByRole('dialog')).toContainText('아직 저장되지 않은');
  await page.getByRole('button', { name: '계속 작성' }).click();
  await expect(page.getByLabel('답변', { exact: true })).toHaveValue('사용자 답변');
});

test('long sparse data remains readable at mobile width', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 740 }); await loginAsDemo(page);
  await page.goto('/cards?demoScenario=sparse');
  await expect(page.locator('.experience-item').first()).toContainText('긴 제목');
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth);
  expect(overflow).toBe(false);
  await expect(page.getByRole('img', { name: 'hong-dev 프로필 기본 이미지' }).first()).toBeAttached();
});

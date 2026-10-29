import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

test('minimal API summaries never imply a manual source or invented evidence count', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/cards?demoScenario=minimal-summary');
  const item = page.locator('.experience-item').first(); await expect(item).toBeVisible();
  await expect(item.locator('.experience-item__meta')).toHaveText('기술 카드');
  await expect(page.locator('.experience-item__footer').filter({ hasText: /근거 \d+개|내가 쓴 문장/ })).toHaveCount(0);
});

test('active analysis is visible on home and can be reopened after refresh', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/');
  const jobId = await page.evaluate(async () => {
    const response = await fetch('/api/repos/r_auth/analyze', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() } });
    return (await response.json()).data.jobId as string;
  });
  await page.reload();
  const jobs = page.getByRole('region', { name: '진행 중인 작업' }); await expect(jobs).toBeVisible();
  await jobs.getByRole('link', { name: '진행 상황 보기', exact: true }).click();
  await expect(page).toHaveURL(new RegExp('/jobs/' + jobId + '$'));
  await expect(page.getByRole('heading', { name: '작업 진행', exact: true })).toBeVisible();
});

test('review and missing-field filters use the current server STAR states', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/cards');
  // 보완 상태 선택 칸 대신 "보완 필요" 탭 하나 — 빈칸 있음, 확인 필요인 작성 중 카드를 함께 보여 준다
  await page.getByRole('button', { name: /^보완 필요/ }).click();
  await expect(page.getByRole('heading', { name: '로그인 세션 처리', exact: true })).toBeVisible(); // 확인 필요
  await expect(page.getByRole('heading', { name: '결제 롤백 대응 경험', exact: true })).toBeVisible(); // 빈칸 있음
  await expect(page.locator('.experience-item')).toHaveCount(2);
  await expect(page.getByRole('heading', { name: '토큰 만료 자동 재발급', exact: true })).toHaveCount(0); // 확정 카드는 빠진다
});

test('copy preview offers plain STAR and evidence formats without modifying saved text', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/cards/card_03');
  await page.getByRole('button', { name: '복사', exact: true }).click();
  const preview = page.getByRole('textbox', { name: '복사할 내용', exact: true });
  const plain = await preview.inputValue(); expect(plain).not.toContain('##'); expect(plain).not.toContain('CONFIRMED');
  await page.getByRole('radio', { name: 'STAR 형식', exact: true }).click();
  await expect(preview).toHaveValue(/상황\n/);
  await page.getByRole('radio', { name: '근거 포함', exact: true }).click();
  await expect(preview).toHaveValue(/https:\/\/github.com/);
  const saved = await page.evaluate(async () => (await (await fetch('/api/cards/card_03')).json()).data);
  expect(plain).toContain(saved.version.situation);
  expect(saved.version.versionNo).toBe(3);
});

test('manual drafts can change title and period after the first save and clear the period explicitly', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/cards/new');
  await page.getByRole('textbox', { name: '카드 제목', exact: true }).fill('메타 수정 경험');
  const period = page.getByRole('textbox', { name: '기간', exact: true }); await period.fill('2024.04');
  await page.getByRole('button', { name: '임시 저장 시작', exact: true }).click();
  await expect(page.getByRole('textbox', { name: '카드 제목', exact: true })).toBeEnabled();
  await page.getByRole('textbox', { name: '카드 제목', exact: true }).fill('수정한 경험'); await period.fill('2025.02');
  await expect(page.getByRole('status').filter({ hasText: '저장됨' })).toBeVisible({ timeout: 15000 });
  await page.getByRole('button', { name: '저장 후 종료', exact: true }).click();
  await page.getByRole('button', { name: '기본 정보 수정', exact: true }).click();
  const dialog = page.getByRole('dialog'); await expect(dialog.getByRole('textbox', { name: '기간', exact: true })).toHaveValue('2025.02');
  await dialog.getByRole('textbox', { name: '기간', exact: true }).fill('');
  await dialog.getByRole('button', { name: '저장', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await page.reload(); await page.getByRole('button', { name: '기본 정보 수정', exact: true }).click();
  await expect(page.getByRole('dialog').getByRole('textbox', { name: '기간', exact: true })).toHaveValue('');
});

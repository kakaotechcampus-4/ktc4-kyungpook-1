import { test, expect } from '@playwright/test';
import { loginAsDemo as login } from './helpers';

/** 백엔드 계약: 개수를 못 센 레포(countedAt null)의 0 은 실제 0 이 아니다 / 수집 상한은 실패가 아닌 부분 결과다. */

test('개수를 못 센 레포는 숫자 대신 "세는 중"이고, 0 으로 오해할 경고를 띄우지 않는다', async ({ page }) => {
  await login(page);
  await page.goto('/repos');
  const counting = page.getByRole('radio', { name: /side-project-new/ });
  await expect(counting).toBeVisible();
  await expect(counting).toContainText('세는 중');
  await expect(counting).toContainText('집계 전이에요');
  await expect(counting).not.toContainText('내 기여');
  await expect(counting).not.toContainText('PR 0건');
  // 실제로 센 0 은 그대로 보인다 — 구분이 사라지면 안 된다
  await expect(page.getByRole('radio', { name: /algorithm-study/ })).toContainText('PR 0건');
  expect(await page.getByText('PR 0건', { exact: true }).count()).toBe(2);   // algorithm-study · team-board, 못 센 레포는 세지 않는다

  await counting.click();
  await page.getByRole('button', { name: '다음 →' }).click();
  await expect(page.getByRole('dialog').getByText(/기여 개수를 세는 중이에요/)).toBeVisible();
});

test('수집 상한에 걸리면 이유를 말하고, 서버가 열지 않은 "이어 읽기"는 두지 않는다', async ({ page }) => {
  await login(page);
  await page.goto('/repos?select=r_capped&disclose=1');
  await page.getByRole('button', { name: '정리 시작' }).click();
  await expect(page.getByRole('heading', { name: '읽은 데까지 정리했어요' })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText('한 번에 읽는 상한까지만 읽었어요')).toBeVisible();
  await expect(page.getByText('일부 기록만 분석했어요')).toHaveCount(0);
  await expect(page.getByRole('button', { name: /이어 읽기/ })).toHaveCount(0);   // retryable: false
  await page.getByRole('button', { name: '읽은 범위의 후보 보기' }).click();
  await expect(page.getByRole('heading', { name: /후보 \d+개 \(부분 결과\)/ })).toBeVisible();
  await page.getByRole('link', { name: '작업 상태 보기' }).click();
  await expect(page.getByText('한 번에 읽는 상한까지만 읽었어요')).toBeVisible();
});

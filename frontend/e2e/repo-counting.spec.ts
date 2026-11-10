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
  await expect(counting).not.toContainText('PR 없이');
  // 실제로 센 0 은 그대로 보인다 — 구분이 사라지면 안 된다
  await expect(page.getByRole('radio', { name: /algorithm-study/ })).toContainText(/PR 없이 커밋 \d+개를 올린 레포라/);

  await counting.click();
  await page.getByRole('button', { name: '다음', exact: true }).click();
  await expect(page.getByRole('dialog').getByText(/기여 개수를 세는 중이에요/)).toBeVisible();
});

test('수집 상한에 걸리면 안내 한 줄로 이유를 말하고, 서버가 열지 않은 "이어 읽기"는 두지 않으며 카드까지 이어 간다', async ({ page }) => {
  // 일부만 정리한 화면은 1초 안에 카드로 넘어가므로 가짜 시계로 멈춰 두고 본다
  await page.clock.install();
  await login(page);
  await page.goto('/repos?select=r_capped&disclose=1');
  await page.getByRole('button', { name: /정리 시작|다시 정리/ }).click();
  const heading = page.getByRole('heading', { name: '읽은 데까지 정리했어요' });
  for (let i = 0; i < 150 && !(await heading.isVisible()); i += 1) await page.clock.runFor(200); // 끝난 화면은 1초 남짓이라 잘게 돌린다
  await expect(heading).toBeVisible();
  const note = page.locator('.run-capnote');
  await expect(note).toContainText('한 번에 읽는 상한까지만 읽었어요');
  await expect(note).not.toContainText('일부 기록만 정리했어요');
  await expect(note.getByRole('button', { name: /이어 읽기/ })).toHaveCount(0); // retryable: false
  for (let i = 0; i < 10 && !/\/cards\//.test(page.url()); i += 1) await page.clock.runFor(1_000);
  await expect(page).toHaveURL(/\/cards\//); // 일부만 읽어도 같은 길로 카드 초안까지 간다
});

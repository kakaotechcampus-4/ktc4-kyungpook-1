import { test, expect } from '@playwright/test';
import { freshSeed, loginAsDemo } from './helpers';

/** S-1 핵심 경로: 로그인 → 레포 선택 → 사전 고지 → 분석 → 후보 보드 → 카드 생성 → 초안 → 확정. */
test('레포 선택 → 카드 확정 1개 경로', async ({ page }) => {
  await freshSeed(page);
  await page.goto('/');
  await expect(page).toHaveURL(/\/login/);
  await page.locator('.landing__actions').getByRole('button', { name: '샘플로 체험하기' }).click();
  await expect(page.getByRole('heading', { name: '경험 정리', exact: true })).toBeVisible();

  await page.getByRole('link', { name: '레포 고르기', exact: true }).click();
  await page.getByRole('radio', { name: /algorithm-study/ }).click(); // PR 0건 레포 — 커밋 묶음 경로
  await page.getByRole('button', { name: '다음' }).click();
  await expect(page.getByRole('heading', { name: '이건 안 읽어요' })).toBeVisible(); // 사전 고지
  await page.getByRole('button', { name: '정리 시작' }).click();

  await expect(page.getByRole('heading', { name: '읽고 있습니다' })).toBeVisible();
  await expect(page).toHaveURL(/\/candidates/, { timeout: 30_000 });
  await expect(page.getByText('PR이 아닙니다').first()).toBeVisible(); // COMMIT_CLUSTER 라벨

  await page.getByRole('checkbox', { disabled: false }).first().click(); // 사용된 후보는 비활성이라 건너뛴다
  await page.getByRole('button', { name: /선택한 1개로 카드 만들기/ }).click();
  await expect(page.getByRole('heading', { name: '고른 후보의 코드를 읽고 있습니다' })).toBeVisible();
  await expect(page.getByText('근거를 찾지 못해 비워 두었습니다')).toBeVisible({ timeout: 30_000 }); // 빈 칸이 증거

  await page.getByRole('button', { name: '확정', exact: true }).click();
  await expect(page.getByRole('dialog').getByRole('checkbox')).toHaveCount(0);
  await page.getByRole('button', { name: '확정하기' }).click();
  await expect(page.getByText('확정됨').first()).toBeVisible();
  await expect(page.getByRole('button', { name: /남은 후보 \d+개 보기/ })).toBeVisible();
});

test('후보 0개는 에러가 아니라 판정이다 (verdict EMPTY)', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/repos?select=r_board&disclose=1');
  await page.getByRole('button', { name: '정리 시작' }).click();
  await expect(page.getByRole('heading', { name: '자동으로 찾은 경험이 없어요' })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText('PR 참여 없음')).toBeVisible();
  await expect(page.getByRole('link', { name: '파일 보며 찾기' })).toBeVisible();
});

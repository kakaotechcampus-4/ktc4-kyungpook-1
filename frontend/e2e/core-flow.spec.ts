import { test, expect } from '@playwright/test';
import { freshSeed, loginAsDemo } from './helpers';

/** S-1 핵심 경로: 로그인 → 레포 선택 → 사전 고지 → 분석 → 추천 후보로 카드 초안(후보를 고르지 않는다) → 카드 폴더 → 확정. */
test('레포 선택 → 카드 확정 1개 경로', async ({ page }) => {
  test.setTimeout(120_000); // 분석 12초 뒤 추천 후보 네 장을 한 장씩 차례로 쓴다(#617)
  await freshSeed(page);
  await page.goto('/');
  await expect(page).toHaveURL(/\/login/);
  await page.locator('.landing__actions').getByRole('button', { name: '샘플로 체험하기' }).click();
  await expect(page.getByRole('heading', { name: /^지원할 곳/ })).toBeVisible();

  await page.getByRole('link', { name: '레포 정리하기', exact: true }).click();
  await page.getByRole('radio', { name: /algorithm-study/ }).click(); // PR 0건 레포 — 커밋 묶음 경로
  await page.getByRole('button', { name: '다음' }).click();
  await expect(page.getByRole('heading', { name: '이건 안 읽어요' })).toBeVisible(); // 사전 고지
  await page.getByRole('button', { name: '정리 시작' }).click();

  await expect(page.getByRole('heading', { name: '레포를 읽고 있어요' })).toBeVisible();
  // 이 화면에서 끝나는 것을 봤고 이 레포에는 카드가 없으니 후보 보드를 거치지 않고 추천 후보로 바로 초안을 쓴다
  await expect(page.getByRole('heading', { name: '카드 초안을 쓰고 있어요' })).toBeVisible({ timeout: 30_000 });
  // 여러 장을 다 쓰면 그 레포 카드로 맞는 기업을 고르는 화면으로 옮겨 간다. 거기서 카드 폴더로 간다(고르는 중이면 '카드 먼저 보기', 끝났으면 '폴더로 가기')
  await expect(page).toHaveURL(/\/cards\/repo\/r_algo\/match$/, { timeout: 60_000 });
  await page.getByRole('link', { name: /^(카드 먼저 보기|폴더로 가기|카드 보러 가기)$/ }).click();
  await expect(page).toHaveURL(/\/cards\/repo\/r_algo$/);
  await page.locator('.experience-item', { hasText: '/auth 아래 3커밋 묶음' }).click(); // 점수가 가장 높은 후보의 카드
  await expect(page.getByText('근거를 찾지 못해 비워 뒀어요')).toBeVisible(); // 빈 칸이 증거

  await page.getByRole('button', { name: '확정', exact: true }).click();
  await expect(page.getByRole('dialog').getByRole('checkbox')).toHaveCount(0);
  await page.getByRole('button', { name: '확정하기' }).click();
  // 확정하면 카드가 든 폴더로 가고, 맨 위 줄이 다음에 할 일을 말하며 방금 확정한 카드를 짚는다
  await expect(page).toHaveURL(/\/cards\/repo\//);
  await expect(page.locator('.next-step')).toContainText('카드를 확정했어요');
  await expect(page.locator('.experience-item--just')).toHaveCount(1);
});

test('후보 0개는 에러가 아니라 판정이다 (verdict EMPTY)', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/repos?select=r_board&disclose=1');
  await page.getByRole('button', { name: '정리 시작' }).click();
  await expect(page.getByRole('heading', { name: '자동으로 찾은 후보가 없어요' })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText('PR 참여 없음')).toBeVisible();
  await expect(page.getByRole('link', { name: '파일 보며 찾기' })).toBeVisible();
});

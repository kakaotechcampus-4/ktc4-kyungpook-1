import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

/** 확정 카드는 위 '수정' 버튼 없이 제목 옆 연필(제목·기간)과 칸 이름 옆 연필(그 칸)로 고친다 */
test('확정 카드 머리에는 제목 옆 연필과 복사 버튼만 있고, 제목은 카드를 다시 열지 않고 고친다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/cards/card_03');
  await expect(page.getByRole('button', { name: '기본 정보 수정' })).toBeVisible();
  await expect(page.getByRole('button', { name: '수정', exact: true })).toHaveCount(0);
  await page.screenshot({ path: 'docs/screenshots/2026-11-04-card-title-pencil/confirmed-1440-light.png', animations: 'disabled' });
  await page.getByRole('button', { name: '기본 정보 수정' }).click();
  const dialog = page.getByRole('dialog', { name: '기본 정보 수정' });
  await dialog.getByRole('textbox', { name: '카드 제목' }).fill('토큰 만료 처리 정리');
  await dialog.getByRole('button', { name: '저장' }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.getByRole('heading', { level: 1, name: '토큰 만료 처리 정리' })).toBeVisible();
  await expect(page.getByText('확정됨').or(page.getByText(/확정$/)).first()).toBeVisible();
});

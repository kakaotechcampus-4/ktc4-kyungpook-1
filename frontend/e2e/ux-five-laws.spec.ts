import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

for (const width of [320, 390, 1440]) {
  test(`UX entry, writing and recall flows at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await loginAsDemo(page);
    await page.goto('/');
    await expect(page.locator('.experience-item')).toHaveCount(3);
    await expect(page.getByRole('link', { name: '모든 카드 보기', exact: true })).toBeVisible();
    await page.goto('/repos');
    await page.getByRole('textbox', { name: '레포 이름 검색' }).fill('not-a-repo');
    await expect(page.getByText('검색 결과가 없어요', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '검색과 필터 초기화' }).click();
    await page.getByRole('radio', { name: /auth-service/ }).click();
    await page.getByRole('button', { name: '다음', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('list', { name: '진행 단계' })).toHaveCount(0);
    await dialog.getByRole('button', { name: '취소', exact: true }).click();
    await page.goto('/cards/new');
    await expect(page.getByRole('heading', { name: '직접 작성', exact: true })).toBeVisible({ timeout: 15000 });
    await expect(page.getByRole('button', { name: '작성 시작' })).toHaveCount(1);
    await expect(page.getByRole('button', { name: '확정', exact: true })).toHaveCount(0);
    await page.getByRole('textbox', { name: '카드 제목', exact: true }).fill('UX 확인 카드');
    await page.getByRole('textbox', { name: '상황', exact: true }).fill('작성한 상황');
    await page.getByRole('button', { name: '작성 시작' }).click();
    await expect(page.getByRole('button', { name: '저장 후 종료' })).toBeVisible();
    await expect(page.getByRole('status').filter({ hasText: '저장됨' })).toBeVisible();
    await page.getByRole('button', { name: '저장 후 종료' }).click();
    await expect(page.getByRole('heading', { name: 'UX 확인 카드', exact: true })).toBeVisible();
    await page.goto('/repos/r_auth/recall');
    const answer = page.getByRole('textbox');
    await expect(answer).toHaveAccessibleName(/무엇을/);
    await answer.fill('이 디렉터리에서 하던 일');
    await page.getByRole('button', { name: '펼치기', exact: true }).first().click();
    await page.getByRole('textbox').fill('다른 디렉터리 메모');
    await page.getByRole('button', { name: '펼치기', exact: true }).first().click();
    await expect(page.getByRole('textbox')).toHaveValue('이 디렉터리에서 하던 일');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  });
}

test('version restore waits for explicit confirmation and keeps history unchanged on cancel', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/cards/card_03');
  await page.getByRole('button', { name: '작성 이력', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: '이 버전으로 되돌리기' }).first().click();
  // Choosing a historical summary must never be a mutation by itself.
  const current = await page.evaluate(async () => (await (await fetch('/api/cards/card_03')).json()).data.version.versionNo);
  expect(current).toBe(3);
  await expect(dialog.getByRole('button', { name: '이 버전으로 되돌리기' })).toBeVisible();
});

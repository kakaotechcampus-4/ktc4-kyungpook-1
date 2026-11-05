import { expect, test, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

/**
 * 자소서 지우기 — 서버에는 있었지만 화면이 없었다. 개인정보처리방침이 "카드와 자소서는 각 화면에서 지울 수 있다"고 적는다.
 * 목록 행 옆과 자소서 화면 위(경로 줄)에 삭제가 있고, 누르면 되돌릴 수 없다는 확인 창이 먼저 열린다.
 */
async function createLetter(page: Page) {
  await page.goto('/cover-letters/new');
  await page.getByPlaceholder('예: 백엔드 개발').fill('백엔드 개발');
  await page.getByLabel('지원 이유', { exact: true }).fill('결제 서비스의 오류를 줄이는 일을 직접 해 보고 싶어 지원했어요');
  await page.getByLabel('1번 문항', { exact: true }).fill('어려운 문제를 해결한 경험을 적어 주세요');
  await page.getByRole('button', { name: '카드 고르기', exact: true }).click();
  // /cover-letters/new 도 아래 모양에 맞으니 new 는 뺀다(만들기가 끝나 자소서 주소로 바뀐 뒤에야 다음으로 간다)
  await expect(page).toHaveURL(/\/cover-letters\/(?!new$)[0-9a-z_-]+$/);
}

test('목록에서 삭제하면 확인 창이 먼저 열리고, 지운 뒤에는 새로고침해도 없다', async ({ page }) => {
  await loginAsDemo(page);
  await createLetter(page);
  await page.goto('/cover-letters');
  const row = page.getByRole('listitem').filter({ hasText: '백엔드 개발' });
  await expect(row).toHaveCount(1);
  await row.getByRole('button', { name: '백엔드 개발 자소서 삭제' }).click();
  const dialog = page.getByRole('dialog', { name: '자소서를 삭제할까요?' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText('삭제한 자소서는 되돌릴 수 없어요')).toBeVisible();
  // 만든 지 얼마 안 된 자소서는 AI 가 도는 단계라 그 결과를 버린다고 알린다
  await expect(dialog.getByText('AI가 쓰는 중이면 그 결과는 저장하지 않고 버려요.')).toBeVisible();
  await dialog.getByRole('button', { name: '취소' }).click();
  await expect(dialog).toBeHidden();
  await expect(row).toHaveCount(1);
  await row.getByRole('button', { name: '백엔드 개발 자소서 삭제' }).click();
  await dialog.getByRole('button', { name: '삭제', exact: true }).click();
  await expect(page.getByText('아직 쓴 자소서가 없어요')).toBeVisible();
  await page.reload();
  await expect(page.getByText('아직 쓴 자소서가 없어요')).toBeVisible();
});

test('자소서 화면 위의 자소서 삭제는 지운 뒤 목록으로 돌아간다', async ({ page }) => {
  await loginAsDemo(page);
  await createLetter(page);
  await page.getByRole('button', { name: '자소서 삭제', exact: true }).click();
  await page.getByRole('dialog', { name: '자소서를 삭제할까요?' }).getByRole('button', { name: '삭제', exact: true }).click();
  await expect(page).toHaveURL(/\/cover-letters$/);
  await expect(page.getByText('아직 쓴 자소서가 없어요')).toBeVisible();
});

for (const width of [390, 320]) {
  test(`${width}px: 삭제 버튼은 44px 이상이고 행의 글자와 겹치지 않으며 옆으로 넘치지 않는다`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await loginAsDemo(page);
    await createLetter(page);
    await page.goto('/cover-letters');
    const row = page.getByRole('listitem').filter({ hasText: '백엔드 개발' });
    const button = row.getByRole('button', { name: '백엔드 개발 자소서 삭제' });
    const box = (await button.boundingBox())!;
    expect(box.height).toBeGreaterThanOrEqual(44);
    expect(box.width).toBeGreaterThanOrEqual(44);
    // 링크 안의 단계 표시와 날짜 칸이 삭제 버튼 자리를 피한다
    const badge = (await row.locator('.badge, [class*="badge"]').first().boundingBox())!;
    expect(badge.x + badge.width).toBeLessThanOrEqual(box.x + 1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.goto(new URL((await row.getByRole('link').getAttribute('href'))!, page.url()).toString());
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    expect((await page.getByRole('button', { name: '자소서 삭제', exact: true }).boundingBox())!.height).toBeGreaterThanOrEqual(44);
  });
}

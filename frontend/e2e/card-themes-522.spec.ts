import { test, expect, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

/**
 * 이슈 #522 — 경험 카드 상세에서 문항 유형(themes)을 보이고, 확정 카드도 다시 열지 않고 바로 고친다.
 * 목에서는 card_04(확정, 갈등 해결과 협업과 소통), card_02(작성 중, 유형 없음), card_03(확정 기술 카드)을 쓴다.
 */
const group = (page: Page) => page.getByRole('group', { name: '자소서 문항 유형' });
const chips = (page: Page) => page.getByRole('list', { name: '자소서 문항 유형' }).getByRole('listitem');
const box = (page: Page, name: string) => group(page).getByRole('checkbox', { name: new RegExp(name) });

test('고르면 바로 저장되고, 확정 카드도 다시 열지 않으며, 새로고침해도 남는다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/cards/card_04');
  await expect(page.getByRole('heading', { name: '팀원과 토큰 저장 위치로 갈린 경험' })).toBeVisible();
  await expect(chips(page)).toHaveText(['갈등 해결', '협업과 소통']);

  await page.getByRole('button', { name: '자소서 문항 유형 수정' }).click();
  await expect(group(page).getByRole('checkbox')).toHaveCount(7);
  await box(page, '갈등 해결').uncheck();
  await expect(group(page).getByText('저장됨')).toBeVisible();
  await box(page, '문제 해결').check();
  await expect(group(page).getByText('저장됨')).toBeVisible();
  // 2개를 다 고르면 나머지는 막히고, 이유를 글로 알린다
  await expect(box(page, '리더십')).toBeDisabled();
  await expect(group(page)).toContainText('2개를 골랐어요');

  // 되돌리기 확인창도 없고, 카드는 확정 그대로다
  await page.getByRole('button', { name: '완료' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(chips(page)).toHaveText(['협업과 소통', '문제 해결']);
  // 목의 확정일은 오늘 기준 며칠 전(daysAgo)이라 날짜를 적어 두면 날이 바뀔 때 깨진다 — 확정 날짜가 그대로 보이는지만 본다
  await expect(page.getByText(/\d{4}\.\d{2}\.\d{2} 확정/)).toBeVisible();
  await expect(page.getByRole('button', { name: /^수정(하기)?$/ })).toBeVisible();

  await page.reload();
  await expect(chips(page)).toHaveText(['협업과 소통', '문제 해결']);
});

test('유형이 없는 작성 중 카드는 "정해지지 않았어요"로 알리고 고르기로 정한다. 기술 카드에는 자리가 없다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/cards/card_02');
  await expect(page.getByText('정해지지 않았어요')).toBeVisible();
  await page.getByRole('button', { name: '자소서 문항 유형 고르기' }).click();
  await box(page, '리더십').check();
  await expect(group(page).getByText('저장됨')).toBeVisible();
  await page.getByRole('button', { name: '완료' }).click();
  await expect(chips(page)).toHaveText(['리더십']);

  await page.goto('/cards/card_03');
  await expect(page.getByRole('heading', { name: '토큰 만료 자동 재발급' })).toBeVisible();
  await expect(page.getByText('자소서 문항 유형')).toHaveCount(0);
});

test('키보드만으로 고를 수 있고 선택 상태가 글과 모양으로 드러난다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/cards/card_02');
  await page.getByRole('button', { name: '자소서 문항 유형 고르기' }).click();
  await box(page, '협업과 소통').focus();
  await page.keyboard.press('Space');
  await expect(box(page, '협업과 소통')).toBeChecked();
  await expect(group(page).getByText('저장됨')).toBeVisible();
  // 체크 표시(그림)가 보이고, 포커스 테두리가 그려진다
  const row = group(page).locator('label', { hasText: '협업과 소통' });
  await expect(row.locator('svg')).toBeVisible();
  const outline = await row.evaluate((el) => getComputedStyle(el).outlineStyle);
  expect(outline).toBe('solid');
});

for (const width of [1440, 390, 320]) for (const theme of ['light', 'dark']) {
  test(`${width}px ${theme}: 가로로 넘치지 않고 고르는 칸은 44px 이상이다`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.addInitScript((value) => localStorage.setItem('gitory.theme', value), theme);
    await loginAsDemo(page);
    await page.goto('/cards/card_04');
    await expect(chips(page)).toHaveCount(2);
    const overflow = () => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(await overflow()).toBeLessThanOrEqual(0);

    const edit = page.getByRole('button', { name: '자소서 문항 유형 수정' });
    expect((await edit.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    await edit.click();
    await expect(group(page)).toBeVisible();
    expect(await overflow()).toBeLessThanOrEqual(0);
    expect((await page.getByRole('button', { name: '완료' }).boundingBox())!.height).toBeGreaterThanOrEqual(44);
    for (const label of await group(page).locator('label').all()) {
      const rect = (await label.boundingBox())!;
      expect(rect.height).toBeGreaterThanOrEqual(44);
      expect(rect.x + rect.width).toBeLessThanOrEqual(width);
      // 글이 잘리지 않는다
      expect(await label.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true);
    }
    // 선택된 칸은 색만이 아니라 체크 그림으로도 드러난다
    await expect(group(page).locator('[data-checked] svg')).toHaveCount(2);
    for (const svg of await group(page).locator('[data-checked] svg').all()) expect(await svg.isVisible()).toBe(true);
    // 2개를 고르면 나머지는 점선 테두리로도 막혀 있다고 보인다
    expect(await box(page, '리더십').locator('xpath=..').evaluate((el) => getComputedStyle(el).borderStyle)).toBe('dashed');
    await page.getByRole('button', { name: '완료' }).click();
    expect(await overflow()).toBeLessThanOrEqual(0);
  });
}

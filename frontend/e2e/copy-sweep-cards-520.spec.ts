import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

/** #520 화면 점검 — 모바일 터치 44px, 위험 색, 숫자 일치. 문구는 단위 시험(copy-sweep-cards-520.test.tsx)이 본다. */
test.use({ viewport: { width: 390, height: 844 } });

const box = async (locator: import('@playwright/test').Locator) => (await locator.boundingBox())!;

test('경험 카드 목록의 묶어 보기 전환과 상태 탭은 44px 이상이다 (CL-03)', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/cards');
  await expect(page.getByRole('radiogroup', { name: '카드 묶어 보기' })).toBeVisible();
  for (const item of await page.locator('.view-switch__item').all()) {
    const rect = await box(item);
    expect(rect.height).toBeGreaterThanOrEqual(44);
    expect(rect.width).toBeGreaterThanOrEqual(44);
  }
  for (const tab of await page.locator('.status-tab').all()) {
    const rect = await box(tab);
    expect(rect.height).toBeGreaterThanOrEqual(44);
    expect(rect.width).toBeGreaterThanOrEqual(44);
  }
});

test('파일 보며 찾기의 펼치기와 접기 토글은 44px 이상이다 (RC-02)', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/repos/r_web/recall');
  await expect(page.getByRole('heading', { name: '파일 보며 찾기', level: 1 })).toBeVisible();
  const toggles = page.locator('.dir-card .btn.right');
  await expect(toggles).toHaveCount(3);
  for (const toggle of await toggles.all()) {
    const rect = await box(toggle);
    expect(rect.height).toBeGreaterThanOrEqual(44);
    expect(rect.width).toBeGreaterThanOrEqual(44);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('카드 삭제 진입 버튼은 빨간 글자이고, 확인 창의 마지막 삭제도 빨강이다 (CD-07, 멘토 의견)', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/cards/card_03');
  const trigger = page.getByRole('button', { name: '카드 삭제', exact: true });
  await expect(trigger).toBeVisible();
  const tokenColor = (name: string) => page.evaluate((token) => {
    const probe = document.createElement('span');
    probe.style.color = getComputedStyle(document.documentElement).getPropertyValue(token);
    document.body.append(probe);
    const color = getComputedStyle(probe).color;
    probe.remove();
    return color;
  }, name);
  expect(await trigger.evaluate((element) => getComputedStyle(element).color)).toBe(await tokenColor('--state-failed-text'));
  // 색만으로 구분하지 않는다: 글자가 이름이고, 누르면 취소할 수 있는 확인 창이 먼저 열린다
  await trigger.click();
  const dialog = page.getByRole('dialog', { name: '카드를 삭제할까요?' });
  await expect(dialog).toBeVisible();
  expect(await dialog.getByRole('button', { name: '삭제', exact: true }).evaluate((element) => getComputedStyle(element).color)).toBe(await tokenColor('--state-failed-text'));
  await expect(dialog.getByRole('button', { name: '취소' })).toBeVisible();
  await dialog.getByRole('button', { name: '취소' }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
});

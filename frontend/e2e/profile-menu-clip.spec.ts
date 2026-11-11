import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

/**
 * 프로필 메뉴(마이페이지·다크 모드·로그아웃)가 사이드바에 잘리지 않고 본문 위에 뜬다.
 * 사이드바가 가로로 넘치는 것을 가려서(overflow-x: hidden), 메뉴를 그 안에 두면 접힌 사이드바(폭 72px)에서 오른쪽이 잘렸다.
 */
const HEIGHT = 900;
const CASES = [
  { name: '접힌 사이드바 1440', width: 1440, rail: true, trigger: '.sidebar' },
  { name: '펼친 사이드바 1440', width: 1440, rail: false, trigger: '.sidebar' },
  { name: '태블릿 820', width: 820, rail: false, trigger: '.sidebar' },
  { name: '휴대폰 390', width: 390, rail: false, trigger: '.mobile-brand' },
] as const;

for (const c of CASES) {
  test(`프로필 메뉴가 화면 안에 다 보이고 항목이 눌린다 (${c.name})`, async ({ page }) => {
    await page.setViewportSize({ width: c.width, height: HEIGHT });
    await page.addInitScript((rail) => { try { localStorage.setItem('gitory.rail', rail ? '1' : '0'); } catch { /* 저장 못 하면 기본 모양 */ } }, c.rail);
    await loginAsDemo(page);
    await page.goto('/');
    await page.locator(c.trigger).getByRole('button', { name: /계정 메뉴/ }).click();

    const menu = page.getByRole('menu');
    await expect(menu).toBeVisible();
    const box = (await menu.boundingBox())!;
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.y).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(c.width);
    expect(box.y + box.height).toBeLessThanOrEqual(HEIGHT);
    expect(box.width).toBeGreaterThanOrEqual(190); // 항목 글자가 잘리지 않는 폭

    // 항목 가운데를 눌렀을 때 사이드바나 본문이 아니라 메뉴가 받는다 — 잘리거나 가려지지 않았다
    for (const item of await menu.getByRole('menuitem').all()) {
      const b = (await item.boundingBox())!;
      const covered = await page.evaluate(([x, y]) => !document.elementFromPoint(x, y)?.closest('[role="menu"]'), [b.x + b.width / 2, b.y + b.height / 2]);
      expect(covered).toBe(false);
    }

    // 접힌 사이드바에서는 사이드바 바깥(오른쪽)에 뜬다
    if (c.rail) {
      const side = (await page.locator('.sidebar').boundingBox())!;
      expect(box.x).toBeGreaterThanOrEqual(side.x + side.width);
    }

    await page.keyboard.press('Escape');
    await expect(menu).toHaveCount(0);
    await expect(page.locator(c.trigger).getByRole('button', { name: /계정 메뉴/ })).toBeFocused();
  });
}

test('프로필 메뉴의 로그아웃이 접힌 사이드바에서도 동작한다', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: HEIGHT });
  await page.addInitScript(() => { try { localStorage.setItem('gitory.rail', '1'); } catch { /* 기본 모양 */ } });
  await loginAsDemo(page);
  await page.goto('/');
  await page.locator('.sidebar').getByRole('button', { name: /계정 메뉴/ }).click();
  await page.getByRole('menuitem', { name: '로그아웃' }).click();
  await expect(page).toHaveURL(/\/login/);
});

import { expect, test, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

/** 글자가 시작하는 왼쪽 끝(첫 글자 또는 새 공고 점)을 잰다 */
const inkLeft = (page: Page, selector: string) => page.locator(selector).first().evaluate((el) => {
  const dot = el.querySelector('.home3-dot');
  if (dot) return Math.round(dot.getBoundingClientRect().left * 10) / 10;
  const text = Array.from(el.childNodes).find((node) => node.nodeType === Node.TEXT_NODE && node.textContent?.trim());
  const range = document.createRange();
  range.selectNodeContents(text ?? el);
  return Math.round(range.getBoundingClientRect().left * 10) / 10;
});
const left = (page: Page, selector: string) => page.locator(selector).first().evaluate((el) => Math.round(el.getBoundingClientRect().left * 10) / 10);

for (const width of [1440, 1280, 390]) {
  test(`홈 지원할 곳: 탭을 눌러 넘기고, 제목과 카드 가장자리 · 탭 글자와 카드 안 글자의 시작선이 맞는다 ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    await loginAsDemo(page);
    await page.goto('/');

    await expect(page.getByRole('heading', { level: 1, name: '지원할 곳' })).toBeVisible();
    const tabs = page.getByRole('tab');
    await expect(tabs.first()).toHaveAttribute('aria-selected', 'true');
    expect(await tabs.count()).toBeGreaterThan(1);

    // 제목 · 맡기기 줄 · 카드 가장자리가 같은 선에서 시작하고, 탭 글자와 카드 안 글자도 같은 선에서 시작한다
    const edge = await left(page, '.home3-panel');
    expect(await left(page, '.home3-ask, .home3-quick')).toBe(edge);
    expect(await inkLeft(page, '.home3-hd h1')).toBe(edge);
    expect(await inkLeft(page, '.home3-tab')).toBe(await inkLeft(page, '.home3-lead .home3-meta span'));
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);

    // 탭 → 다음 공고, 키보드 좌우 화살표로 오가기
    const second = tabs.nth(1);
    await second.click();
    await expect(second).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByRole('tabpanel')).toHaveCount(1);
    await second.press('ArrowLeft');
    await expect(tabs.first()).toHaveAttribute('aria-selected', 'true');
    await expect(tabs.first()).toBeFocused();
    await expect(page.getByRole('tabpanel').getByRole('link', { name: /자소서 쓰기|초안 보기|이어 쓰기/ })).toHaveCount(1);
  });
}

import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

/**
 * #520 점검표 "토스트 닫기 24px": 글자 크기와 토스트 모양은 그대로 두고 누를 수 있는 영역만 44px 안팎으로 넓혔다.
 * 영역은 ::after 로 넓히므로 getBoundingClientRect 로는 잴 수 없다 — 실제로 그 자리를 눌렀을 때 어느 요소가 받는지(elementFromPoint)로 잰다.
 */
for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
  test(`토스트의 닫기·실행 취소는 눈에 보이는 글자보다 넓은 영역을 눌러도 받는다 (${viewport.width}px)`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await loginAsDemo(page);
    await page.goto('/repos/r_auth/candidates');
    await page.locator('.cand').first().getByRole('button', { name: '제외' }).click();
    const toast = page.locator('.toast');
    await expect(toast).toBeVisible();
    // 나타나는 움직임(위로 올라옴)이 끝난 뒤에 잰다
    await toast.evaluate((el) => Promise.all(el.getAnimations().map((a) => a.finished)));

    const extents = await page.evaluate(() => {
      const measure = (selector: string) => {
        const el = document.querySelector<HTMLElement>(selector)!;
        const box = el.getBoundingClientRect();
        let x0 = Infinity, x1 = -Infinity, y0 = Infinity, y1 = -Infinity;
        for (let x = Math.floor(box.left) - 30; x <= Math.ceil(box.right) + 30; x++) {
          for (let y = Math.floor(box.top) - 30; y <= Math.ceil(box.bottom) + 30; y++) {
            if (document.elementFromPoint(x, y) === el) { x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
          }
        }
        return { box: { w: box.width, h: box.height }, hit: { w: x1 - x0 + 1, h: y1 - y0 + 1 } };
      };
      return { close: measure('.toast__close'), action: measure('.toast__action') };
    });

    // 보이는 글자 영역은 작다(그대로)
    expect(extents.close.box.h).toBeLessThan(30);
    expect(extents.action.box.h).toBeLessThan(30);
    // 누를 수 있는 영역은 44px 안팎
    expect(extents.close.hit.w).toBeGreaterThanOrEqual(44);
    expect(extents.close.hit.h).toBeGreaterThanOrEqual(44);
    expect(extents.action.hit.w).toBeGreaterThanOrEqual(44);
    expect(extents.action.hit.h).toBeGreaterThanOrEqual(44);

    // 글자 바로 아래(토스트 안쪽 여백)를 눌러도 닫힌다
    const close = await toast.getByRole('button', { name: '닫기' }).boundingBox();
    await page.mouse.click(close!.x + close!.width / 2, close!.y + close!.height + 8);
    await expect(toast).toHaveCount(0);
  });
}

import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

/** #756 레포 줄 — 숫자는 문장 한 줄에, 칩은 정리 상태 하나, 고른 줄은 파랑 */

test('먼저 정리해 보기 좋은 레포가 맨 위에 모이고, 검정 추천 배지 대신 회색 한 줄이 붙는다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/repos');
  const rows = page.getByRole('radio');
  await expect(rows.first()).toBeVisible();
  const picks = await rows.evaluateAll((els) => els.map((el) => !!el.querySelector('.repo-row__pick')));
  const firstOther = picks.indexOf(false);
  expect(firstOther).toBeGreaterThan(0);
  expect(picks.slice(firstOther)).not.toContain(true);
  await expect(rows.first().getByText('먼저 정리해 보기 좋아요', { exact: true })).toBeVisible();
  await expect(page.getByText('추천', { exact: true })).toHaveCount(0);
});

test('칩은 줄마다 많아야 하나이고, 정리한 레포에만 카드 수와 함께 붙는다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/repos');
  const rows = page.getByRole('radio');
  await expect(rows.first()).toBeVisible();
  const counts = await rows.evaluateAll((els) => els.map((el) => el.querySelectorAll('.badge').length));
  expect(Math.max(...counts)).toBeLessThanOrEqual(1);
  await expect(page.getByRole('radio', { name: /auth-service/ }).locator('.badge')).toHaveText(/^정리 완료 · 카드 \d+장$/);
  await expect(page.getByText('PR 0건', { exact: true })).toHaveCount(0);
});

test('고른 줄은 파랑 테두리로 보이고, 아래 막대가 고른 레포 이름을 말한다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/repos');
  await expect(page.getByText('레포를 하나 골라 주세요', { exact: true })).toBeVisible();
  const row = page.getByRole('radio', { name: /web-client/ });
  await row.click();
  await expect(row).toHaveAttribute('aria-checked', 'true');
  const look = await row.evaluate((el) => {
    const accent = getComputedStyle(document.documentElement).getPropertyValue('--border-accent').trim();
    const probe = document.createElement('span');
    probe.style.color = accent;
    document.body.append(probe);
    const want = getComputedStyle(probe).color;
    probe.remove();
    return { ring: getComputedStyle(el, '::before').borderTopColor, dot: getComputedStyle(el.querySelector('.radio')!).backgroundColor, want };
  });
  expect(look.ring).toBe(look.want);
  expect(look.dot).toBe(look.want);
  await expect(page.getByText('web-client 을 골랐어요', { exact: true })).toBeVisible();
  await expect(page.getByText('다음은 읽는 범위 확인이에요', { exact: true })).toBeVisible();
});

test('390px 에서 가로로 밀리지 않고, 줄은 누르기 좋은 높이다', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await loginAsDemo(page);
  await page.goto('/repos');
  const rows = page.getByRole('radio');
  await expect(rows.first()).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  const heights = await rows.evaluateAll((els) => els.map((el) => el.getBoundingClientRect().height));
  expect(Math.min(...heights)).toBeGreaterThanOrEqual(44);
});

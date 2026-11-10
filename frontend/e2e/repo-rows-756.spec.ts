import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

/** #756 레포 줄 — 숫자는 문장 한 줄에, 고른 줄은 파랑, 정리 상태는 정리한 레포 줄의 글(#778 세 묶음) */

test('내 기여가 많은 레포 묶음이 맨 위에 있고, 줄마다 붙던 추천 글은 없다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/repos');
  await expect(page.getByRole('heading', { level: 2 }).first()).toHaveText('내 기여가 많은 레포');
  await expect(page.getByRole('region', { name: '내 기여가 많은 레포' }).getByRole('radio')).toHaveCount(3);
  await expect(page.getByText('먼저 정리해 보기 좋아요', { exact: true })).toHaveCount(0);
  await expect(page.getByText('추천', { exact: true })).toHaveCount(0);
});

test('고르는 줄에는 칩이 없고, 정리한 레포는 줄 오른쪽에 카드 수가 글로 붙는다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/repos');
  const rows = page.getByRole('radio');
  await expect(rows.first()).toBeVisible();
  const counts = await rows.evaluateAll((els) => els.map((el) => el.querySelectorAll('.badge').length));
  expect(Math.max(...counts)).toBe(0);
  await expect(page.getByRole('region', { name: '이미 정리한 레포' }).locator('li', { hasText: 'auth-service' }).getByText(/^카드 \d+장$/)).toBeVisible();
  await expect(page.getByText('PR 0건', { exact: true })).toHaveCount(0);
});

test('고른 줄은 파랑 테두리로 보이고, 아래 막대가 고른 레포 이름을 말한다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/repos');
  await expect(page.getByText('정리할 레포를 고르면 다음으로 넘어가요', { exact: true })).toBeVisible();
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
  await expect(page.getByText('다음은 읽는 범위 확인이에요', { exact: true })).toHaveCount(0); // 다음 단계 이름은 단계 줄이 말한다
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

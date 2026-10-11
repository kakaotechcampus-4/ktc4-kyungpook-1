import { expect, test } from '@playwright/test';
import { freshSeed, loginAsDemo } from './helpers';

test('landing explains GitHub records and card output with a compact session notice', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await freshSeed(page); await page.goto('/login?reason=expired');
  await expect(page.getByLabel('GitHub 기록 예시')).toBeVisible();
  await expect(page.getByLabel('경험 카드 예시')).toBeVisible();
  const notice = await page.getByRole('status').boundingBox();
  expect(notice!.height).toBeLessThan(55);
});

test('first, middle and last selected repository rows retain geometry and rounded end corners', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/repos');
  const rows = page.getByRole('radio');
  await expect(rows.first()).toBeVisible();
  for (const index of [0, 1, (await rows.count()) - 1]) {
    const row = rows.nth(index);
    const before = await row.boundingBox();
    await row.click();
    await expect(row).toHaveAttribute('aria-checked', 'true');
    const after = await row.boundingBox();
    expect(after!.height).toBe(before!.height);
    const ring = await row.evaluate((el) => {
      const pseudo = getComputedStyle(el, '::before');
      const style = getComputedStyle(el);
      return { width: parseFloat(pseudo.borderTopWidth), color: pseudo.borderTopColor, expected: style.getPropertyValue('--border-selected').trim(),
        top: parseFloat(style.borderTopLeftRadius), bottom: parseFloat(style.borderBottomLeftRadius) };
    });
    expect(ring.width).toBeGreaterThanOrEqual(2);
    if (index === 0) expect(ring.top).toBeGreaterThan(10);
    if (index === (await rows.count()) - 1) expect(ring.bottom).toBeGreaterThan(10);
  }
});

test('desktop experiences have individual card boundaries and gaps', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await loginAsDemo(page); await page.goto('/cards');
  const first = await page.locator('.experience-item').nth(0).boundingBox();
  const second = await page.locator('.experience-item').nth(1).boundingBox();
  expect(second!.x - (first!.x + first!.width)).toBeGreaterThanOrEqual(16);
});

test('large and short desktop dialogs remain centered with visible actions', async ({ page }) => {
  await loginAsDemo(page);
  for (const viewport of [{ width: 1440, height: 1000 }, { width: 844, height: 390 }]) {
    await page.setViewportSize(viewport);
    await page.goto('/repos?select=r_auth&disclose=1');
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('button', { name: '정리 시작' })).toBeEnabled();
    const box = await dialog.boundingBox();
    expect(Math.abs(box!.y + box!.height / 2 - viewport.height / 2)).toBeLessThan(3);
    expect(box!.y + box!.height).toBeLessThan(viewport.height);
  }
});

test('dark logo is visible on mobile, expanded and collapsed navigation', async ({ page }) => {
  await loginAsDemo(page);
  await page.addInitScript(() => localStorage.setItem('gitory.theme', 'dark'));
  await page.setViewportSize({ width: 1440, height: 900 }); await page.goto('/');
  const dark = page.locator('.sidebar .brand-logo__dark');
  await expect(dark).toBeVisible();
  await page.getByRole('button', { name: '사이드바 접기' }).click();
  await expect(page.locator('.sidebar .brand-logo__dark')).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator('.mobile-brand .brand-logo__dark')).toBeVisible();
});

test('suggested selections add to previously selected hidden candidates', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/repos/r_auth/candidates');
  const first = page.getByRole('checkbox', { name: '로그인 세션 처리 선택' });
  await first.check();
  await page.getByRole('textbox', { name: '후보 검색 (제목·추천 이유)' }).fill('로그인 API');
  await page.getByRole('button', { name: '추천 후보 선택' }).click();
  await page.getByRole('button', { name: '검색 지우기' }).click();
  await expect(first).toBeChecked();
  await expect(page.getByRole('button', { name: '선택한 2개로 카드 만들기' })).toBeVisible();
});

test('bulk restore attempts every excluded candidate despite request failures', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/repos/r_auth/candidates');
  await page.evaluate(async () => {
    const board = (await (await fetch('/api/repos/r_auth/candidates')).json()).data;
    for (const candidate of board.candidates.filter((c: { status: string }) => c.status === 'NEW').slice(0, 3)) {
      await fetch('/api/candidates/' + candidate.id, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ status: 'EXCLUDED' }) });
    }
  });
  await page.goto('/repos/r_auth/candidates?demoScenario=candidate-error');
  await page.getByRole('button', { name: '제외 3개 복원' }).click();
  await expect(page.locator('.toast').filter({ hasText: '0개 복원 · 3개는 다시 시도해 주세요' })).toBeVisible();
  await page.getByRole('button', { name: '제외 3개 복원' }).click();
  await expect(page.getByRole('button', { name: /제외 \d+개 복원/ })).toHaveCount(0);
});

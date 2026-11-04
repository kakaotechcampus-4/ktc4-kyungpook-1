import { expect, test, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

const SHOTS = 'docs/screenshots/2026-11-04-analyze-timeline';

/** 정리 진행 화면(#789) — 단계 4개를 점과 선으로 그리고 지금 단계만 펼친다. 목의 정리는 12초 동안 돈다 */
async function startAnalysis(page: Page, repoId: string) {
  await page.goto(`/repos?select=${repoId}&disclose=1`);
  await page.getByRole('button', { name: '정리 시작' }).click();
  await expect(page.getByRole('heading', { name: '레포를 읽고 있어요' })).toBeVisible();
}

for (const width of [1440, 390]) for (const theme of ['light', 'dark'] as const) {
  test(`정리가 도는 동안 지금 단계만 상자로 펼쳐져 큰 숫자가 보이고 끝난 단계는 한 줄로 접힌다 ${width} ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.addInitScript((value) => localStorage.setItem('gitory.theme', value), theme);
    await loginAsDemo(page);
    await startAnalysis(page, 'r_auth');
    await expect(page.locator('.tl__item')).toHaveCount(4);
    await expect(page.locator('.tl__item--done')).toHaveCount(1, { timeout: 15_000 });   // PR 읽기가 끝나면 커밋 읽기가 펼쳐진다
    const now = page.locator('.tl__item--now');
    await expect(now).toHaveCount(1);
    await expect(now.locator('.tl__count')).toBeVisible();
    await expect(now.locator('.sline').first()).toBeVisible();
    await expect(page.locator('.tl__item--done .tl__stream')).toHaveCount(0);
    await expect(page.getByRole('progressbar')).toHaveCount(0);
    await expect(page.locator('.tl .badge')).toHaveCount(0);
    await expect(page.getByText('떠나도 돼요')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await page.screenshot({ path: `${SHOTS}/running-${width}-${theme}.png`, fullPage: true, animations: 'disabled' });
  });
}

test('다 읽으면 네 점이 모두 검정이 되고 고른 이유가 메모로 남은 뒤 후보 보드로 간다', async ({ page }) => {
  await loginAsDemo(page);
  await startAnalysis(page, 'r_auth');
  await expect(page.getByRole('heading', { name: '레포를 다 읽었어요' })).toBeVisible({ timeout: 30_000 });
  await expect(page.locator('.tl__item--done')).toHaveCount(4);
  await expect(page.locator('.tl-note').first()).toBeVisible();
  await expect(page.getByRole('link', { name: '후보 보드로 바로 가기' })).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/finished-1440-light.png`, fullPage: true, animations: 'disabled' });
  await expect(page).toHaveURL(/\/repos\/r_auth\/candidates/, { timeout: 30_000 });
});

test('읽다가 멈추면 같은 타임라인에서 읽던 단계가 빨간 점이 되고 할 수 있는 일이 아래에 남는다', async ({ page }) => {
  await loginAsDemo(page);
  await startAnalysis(page, 'r_fail');
  await expect(page.getByRole('heading', { name: 'GitHub에서 답이 오지 않았어요' })).toBeVisible({ timeout: 30_000 });
  await expect(page.locator('.tl__item--halt')).toHaveCount(1);
  await expect(page.locator('.tl__item--halt .tl__line')).toHaveText('여기서 멈췄어요');
  await expect(page.locator('.tl__item--now')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '다시 시도' })).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/failed-1440-light.png`, fullPage: true, animations: 'disabled' });
});

test('상한까지만 읽었으면 그 단계가 호박색 점으로 어디까지 읽었는지 보인다', async ({ page }) => {
  await loginAsDemo(page);
  await startAnalysis(page, 'r_capped');
  await expect(page.getByRole('heading', { name: '읽은 데까지 정리했어요' })).toBeVisible({ timeout: 30_000 });
  await expect(page.locator('.tl__item--cap')).toHaveCount(1);
  await expect(page.getByRole('button', { name: '읽은 범위의 후보 보기' })).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/partial-1440-light.png`, fullPage: true, animations: 'disabled' });
});

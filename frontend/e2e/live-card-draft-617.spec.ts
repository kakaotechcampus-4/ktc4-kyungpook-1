import { test, expect, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

/**
 * #617 카드 만들기 — STAR 칸이 AI 가 쓰는 순서대로 채워지는 화면.
 * 데모(목)의 카드 초안 Job 이 실서버처럼 카드를 한 장씩 차례로 쓴다: 장마다 8초쯤 읽고 생각한 뒤 네 칸을 몰아 쓴다.
 */
/** 지금 쓰는 카드의 칸. 앞서 쓴 카드(접힌 목록)의 칸은 빠진다 */
const slot = (page: Page, field: 'S' | 'T' | 'A' | 'R') => page.locator(`.live-card .live-slot[data-field="${field}"]`);
const typedLength = (page: Page) => page.locator('.live-card [data-live-text]').evaluateAll((nodes) => nodes.reduce((sum, node) => sum + Array.from(node.textContent ?? '').length, 0));
const overflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
const generationHeading = (page: Page) => page.getByRole('heading', { name: '카드 초안을 쓰고 있어요' });

async function startCards(page: Page, count: number) {
  await loginAsDemo(page);
  await page.goto('/repos/r_auth/candidates');
  for (let index = 0; index < count; index += 1) await page.locator('.cand-tile__pick').nth(index).click();
  await page.getByRole('button', { name: '카드 만들기', exact: true }).click();
}

test('카드가 차례로 채워지고, 읽는 동안은 커서만, 글은 일정한 속도로 펼쳐지며, 마지막 카드까지 펼친 뒤에 완성된 카드로 넘어간다', async ({ page }) => {
  test.setTimeout(120_000);
  await startCards(page, 2);

  // '2장 중 1번째' 한 줄과 얇은 막대 아래에 지금 쓰는 카드 한 장이 네 칸 틀로 나온다
  await expect(generationHeading(page)).toBeVisible();
  await expect(page.getByText('2장 중 1번째')).toBeVisible();
  await expect(page.getByRole('progressbar', { name: '초안 생성 진행률' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '지금 쓰는 카드' })).toBeVisible();
  await expect(page.locator('.live-card .live-slot__name')).toHaveText(['상황', '과제', '행동', '결과']);

  // 생각하는 동안(8초쯤)은 상황 칸에 커서만 있고 글이 없다
  await expect(slot(page, 'S')).toHaveAttribute('data-phase', 'NEXT');
  await expect(slot(page, 'S').locator('.live-cursor')).toBeVisible();
  await expect(page.locator('.live-cursor')).toHaveCount(1);
  expect(await typedLength(page)).toBe(0);
  await expect(page.locator('.live-card__note')).toHaveText('코드를 읽고 있어요');

  // 몰아서 오는 글을 초당 20자쯤으로 펼친다(문장 하나가 2초 안팎, 카드 하나는 6초 넘게 걸리고 다 쓴 뒤 1.5초 쉰다)
  await expect.poll(() => typedLength(page), { timeout: 20_000, intervals: [50] }).toBeGreaterThan(0);
  const before = await typedLength(page);
  await page.waitForTimeout(1000);
  const grown = (await typedLength(page)) - before;
  expect(grown).toBeGreaterThanOrEqual(12);
  expect(grown).toBeLessThanOrEqual(30);
  await expect(page.locator('.live-cursor')).toHaveCount(1); // 커서는 지금 쓰는 칸 하나에만 있다
  await expect(page.locator('.live-card [aria-live], .live-card [role="status"]')).toHaveCount(0); // 글이 늘어나는 영역은 낭독하지 않는다

  // 좁은 화면에서도 네 칸 틀이 옆으로 넘치지 않고 세로로 쌓인다
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await overflow(page)).toBeLessThanOrEqual(0);
    const tops = await page.locator('.live-card .live-slot').evaluateAll((nodes) => nodes.map((node) => Math.round(node.getBoundingClientRect().top)));
    expect(new Set(tops).size).toBe(4);
  }
  await page.setViewportSize({ width: 1440, height: 1024 });

  // 다 쓴 칸은 체크, 근거가 없어 비운 칸은 이유와 함께, 다시 쓴 칸은 옆에 과정이 붙는다
  await expect(slot(page, 'T')).toHaveAttribute('data-phase', 'BLANK', { timeout: 20_000 });
  await expect(slot(page, 'T')).toContainText('근거가 부족해 비워 둠');
  await expect(slot(page, 'S')).toHaveAttribute('data-phase', 'DONE');
  await expect(slot(page, 'S').locator('.live-slot__status')).toHaveText('완료');
  await expect(slot(page, 'R')).toHaveAttribute('data-phase', 'DONE', { timeout: 30_000 });
  // 과정은 '과정 N단계 보기' 로 접혀 있고 누르면 펼쳐진다. 결과 칸은 첫 글을 쓴 뒤와 다시 쓴 뒤 두 번 '완료' 가 되고 다시 쓰는 동안은 목록이 접히므로,
  // 여기서는 두 경우 모두 들어 있는 검사 단계만 보고 다시 쓴 과정은 아래에서 끝난 카드를 펼쳐 본다
  await slot(page, 'R').locator('.live-steps-fold__summary').click();
  await expect(page.getByRole('list', { name: '결과 칸에서 AI 가 한 일' })).toContainText('근거 없는 숫자(50%)를 찾았어요');

  // 첫 카드를 다 쓰면 다음 카드로 넘어가고, 앞 카드는 접힌 목록에 남는다
  await expect(page.getByText('2장 중 2번째')).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole('heading', { name: '앞서 쓴 카드 1장' })).toBeVisible();
  const finished = page.locator('.live-finished__item');
  await expect(finished).toHaveCount(1);
  await expect(page.locator('.live-card__note')).toHaveText('코드를 읽고 있어요');
  // 끝난 카드를 펼치면 다시 쓴 칸의 과정(이슈를 열어 본 뒤 다시 씀)과 비운 칸의 이유가 그대로 남아 있다
  await finished.locator('.live-finished__summary').click();
  const finishedSlot = (field: 'T' | 'R') => finished.locator(`.live-slot[data-field="${field}"]`);
  await finishedSlot('R').locator('.live-steps-fold__summary').click();
  const process = finished.getByRole('list', { name: '결과 칸에서 AI 가 한 일' });
  await expect(process).toContainText('이슈를 열어 봤어요');
  await expect(process).toContainText('다시 썼어요');
  await finishedSlot('T').locator('.live-steps-fold__summary').click();
  await expect(finished.getByRole('list', { name: '과제 칸에서 AI 가 한 일' })).toContainText('그대로 두기로 했어요');

  // Job 이 끝나도 마지막 카드를 다 펼친 뒤에야 완성된 카드로 넘어간다
  await expect(slot(page, 'R')).toHaveAttribute('data-phase', 'DONE', { timeout: 30_000 });
  await expect(page.locator('.live-draft [role="status"]')).toHaveText('2번째 카드를 다 썼어요', { timeout: 15_000 }); // 서버가 끝났다고 알린 뒤에야 바뀌므로 R 칸이 다 보인 뒤에도 몇 초 걸린다
  await expect(page.getByText('2장 중 2번째')).toBeVisible();
  await expect(generationHeading(page)).toBeVisible(); // 다 쓴 모습을 잠깐 보여 준다
  await expect(generationHeading(page)).toHaveCount(0, { timeout: 10_000 });
  // 여러 장을 다 쓰면 그 레포 카드로 맞는 기업을 고르는 화면으로 옮겨 간다 (#704)
  await expect(page).toHaveURL(/\/cards\/repo\/r_auth\/match$/);
  await expect(page.locator('.live-draft')).toHaveCount(0);
});

test('동작 줄이기를 켜면 글을 펼치지 않고 오는 대로 한 번에 보이고, 끝나면 바로 완성된 카드로 넘어간다', async ({ page }) => {
  test.setTimeout(90_000);
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await startCards(page, 1);
  await expect(generationHeading(page)).toBeVisible();
  await expect(page.getByRole('heading', { name: '지금 쓰는 카드' })).toBeVisible();
  await page.evaluate(() => {
    const flags = window as unknown as { __partial: boolean };
    flags.__partial = false;
    setInterval(() => {
      // 보이는 글이 서버가 준 글보다 모자란 순간이 없어야 한다
      document.querySelectorAll('.live-card .live-slot').forEach((li) => {
        const typed = li.querySelector('[data-live-text]')?.textContent;
        if (typed != null && typed !== li.querySelector('.live-slot__ghost')?.textContent) flags.__partial = true;
      });
    }, 25);
  });
  await expect(slot(page, 'S')).toHaveAttribute('data-phase', 'DONE', { timeout: 30_000 });
  await expect(generationHeading(page)).toHaveCount(0, { timeout: 30_000 });
  expect(await page.evaluate(() => (window as unknown as { __partial: boolean }).__partial)).toBe(false);
  // 한 장만 썼으면 그 카드에 머물고, 쓴 과정이 카드 상세 오른쪽 '만든 과정'에도 남아 있다
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await expect(page.getByRole('region', { name: '만든 과정' }).getByRole('button', { name: '자세히 보기' })).toBeVisible();
});

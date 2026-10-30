import { test, expect } from '@playwright/test';
import { loginAsDemo } from './helpers';

/** #521 카드 상세의 'AI 가 한 일' — AI 초안이 거친 단계와 AI 의 판단이 카드 문장과 따로 보인다. */
test("카드 상세에 AI 가 한 일이 순서대로 보이고, AI 의 판단은 이름표가 붙어 따로 보이며, 기록 없는 카드에는 없다", async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/cards/card_01');
  const section = page.getByRole('region', { name: 'AI 가 한 일' });
  await expect(section).toBeVisible();
  const steps = section.getByRole('listitem');
  await expect(steps).toHaveCount(8);
  await expect(steps.first()).toContainText('GitHub 기록으로 초안을 썼어요');
  await expect(steps.nth(2)).toContainText('결과 칸: 근거 없는 숫자(50%)를 찾았어요');
  await expect(steps.nth(3)).toContainText('과제 칸: 이슈를 열어 봤어요');
  await expect(steps.last()).toContainText('결과 칸: 다시 검사해서 통과했어요');
  await expect(section.getByRole('heading', { level: 3 })).toHaveText(['첫 초안과 검사', '1번째 고쳐 쓰기']);
  // AI 가 고른 행동(자료 열기·그대로 두기·자료 없이 고치기)에만 'AI 의 판단' 이름표와 이유가 붙는다
  await expect(section.getByText('AI 의 판단', { exact: true })).toHaveCount(3);
  await expect(steps.nth(3)).toContainText('AI 의 판단');
  await expect(steps.nth(3)).toContainText('과제가 이슈 본문에 적혀 있을 것 같음');
  // 카드 문장 칸 안에는 AI 의 판단이 섞이지 않는다
  await expect(page.locator('.star').filter({ hasText: 'AI 의 판단' })).toHaveCount(0);
  // 좁은 화면에서도 옆으로 넘치지 않는다
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await expect(section).toBeVisible();
  }
  // 확정한 카드에도 그대로 있고, 실측 카드(기록 없음)에는 구역이 없다
  await page.goto('/cards/card_03');
  await expect(page.getByRole('region', { name: 'AI 가 한 일' }).getByRole('listitem')).toHaveCount(3);
  await page.goto('/cards/card_05');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('FreeTierSleep_Unity');
  await expect(page.getByRole('region', { name: 'AI 가 한 일' })).toHaveCount(0);
});

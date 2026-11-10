import { test, expect } from '@playwright/test';
import { loginAsDemo } from './helpers';

/** #521·#746 카드 상세의 '만든 과정' — 오른쪽 세 줄 요약과, '자세히 보기' 창의 단계 전부. 판단 이유는 카드 문장과 따로 보인다. */
test("카드 상세 오른쪽에 만든 과정 세 줄이 있고, 자세히 보기 창에 단계가 순서대로 보이며, 기록 없는 카드에는 자세히 보기가 없다", async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/cards/card_01');
  const rail = page.getByRole('region', { name: '만든 과정' });
  await expect(rail.getByRole('listitem')).toHaveText(['GitHub 기록으로 초안을 썼어요', '결과 칸을 다시 썼어요', '과제 칸은 검사에서 문제가 남았어요']);
  await rail.getByRole('button', { name: '자세히 보기' }).click();
  const dialog = page.getByRole('dialog', { name: '만든 과정' });
  const steps = dialog.getByRole('listitem');
  await expect(steps).toHaveCount(8);
  await expect(steps.first()).toContainText('GitHub 기록으로 초안을 썼어요');
  await expect(steps.nth(2)).toContainText('결과 칸: 근거 없는 숫자(50%)를 찾았어요');
  await expect(steps.nth(3)).toContainText('과제 칸: 이슈를 열어 봤어요');
  await expect(steps.last()).toContainText('결과 칸: 다시 검사해서 통과했어요');
  await expect(dialog.getByRole('heading', { level: 3 })).toHaveText(['첫 초안과 검사', '1번째 고쳐 쓰기']);
  // AI 가 고른 행동(자료 열기·그대로 두기·자료 없이 고치기)에만 '판단 이유' 이름표와 이유가 붙는다
  await expect(dialog.getByText('판단 이유', { exact: true })).toHaveCount(3);
  await expect(steps.nth(3)).toContainText('과제가 이슈 본문에 적혀 있을 것 같음');
  // 좁은 화면에서도 옆으로 넘치지 않는다
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await expect(dialog).toBeVisible();
  }
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(rail.getByRole('button', { name: '자세히 보기' })).toBeFocused();
  // 카드 문장 칸 안에는 판단 이유가 섞이지 않고, 아래쪽 단계 목록은 없다
  await expect(page.getByText('판단 이유', { exact: true })).toHaveCount(0);
  await expect(page.getByText(/AI 가 한 일/)).toHaveCount(0);
  // 확정한 카드에도 있고, 실측 카드(후보는 있지만 기록 없음)에는 자세히 보기가 없다
  await page.goto('/cards/card_03');
  await expect(page.getByRole('region', { name: '만든 과정' }).getByRole('listitem')).toHaveCount(4); // 세 줄 + 처음 내용에서 2번 바뀌었어요
  await page.goto('/cards/card_05');
  await expect(page.getByRole('heading', { level: 1 })).toContainText('FreeTierSleep_Unity');
  await expect(page.getByRole('region', { name: '만든 과정' }).getByRole('button', { name: '자세히 보기' })).toHaveCount(0);
});

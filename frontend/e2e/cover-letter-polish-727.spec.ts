import { expect, test } from '@playwright/test';
import { loginAsDemo, runToDraft } from './helpers';

/**
 * #727 초안 옆 패널의 진단·첨삭 — 목 서버가 문항 하나를 결정적으로 첨삭한다(약 1.2초).
 * 첨삭 받기 → 살펴보는 중 → 고치는 중 → 결과(종이는 그대로) → 포인트를 누르면 고친 글의 그 문장이 칠해짐 → 적용하기 → 종이 글이 바뀌고 '2판'.
 */
test('초안에서 첨삭을 받아 포인트를 보고 적용하면 종이 글이 바뀌고 2판이 된다', async ({ page }) => {
  await loginAsDemo(page);
  await page.clock.install();
  await page.goto('/cover-letters/new');
  await page.getByPlaceholder('예: 백엔드 개발').fill('백엔드 개발');
  await page.getByLabel('1번 문항', { exact: true }).fill('어려운 문제를 해결한 경험을 적어 주세요');
  await page.getByRole('button', { name: '초안 쓰기', exact: true }).click();
  // 카드 고르기 → 재료 확인 → 초안 쓰기를 화면이 멈추지 않고 이어 가므로 누를 것 없이 시간만 흘려 보낸다
  await runToDraft(page);

  const panel = page.getByRole('complementary', { name: '다듬기 패널' });
  const paper = page.getByLabel('1번 문항 초안');
  await expect(panel.getByText('Q1에 적용')).toBeVisible();
  await expect(panel.getByRole('tab', { name: '대화' })).toHaveAttribute('aria-selected', 'true');
  for (const tab of await panel.getByRole('tab').all()) expect((await tab.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(44);
  await panel.getByRole('tab', { name: '진단과 첨삭' }).click();
  await expect(panel.getByRole('tab', { name: '진단과 첨삭' })).toHaveAttribute('aria-selected', 'true');
  const before = await paper.inputValue();

  await panel.getByRole('button', { name: '첨삭 받기' }).click();
  await expect(panel.getByText('살펴보는 중')).toBeVisible();
  await expect(panel.getByText('Q1 첨삭 중')).toBeVisible();
  await page.clock.runFor(700);
  await expect(panel.locator('.clp-steps li.is-now')).toContainText('고치는 중');
  await page.clock.runFor(2_000);
  await expect(panel.getByText('종합 평가')).toBeVisible();
  expect(await paper.inputValue()).toBe(before);

  await panel.getByRole('button', { name: /결과가 얼마나 나아졌는지/ }).click();
  const lit = panel.locator('.clp-rs--lit');
  await expect(lit).toHaveCount(1);
  await expect(lit).toHaveText('그 결과 같은 문제가 다시 생기지 않았습니다.');
  await expect(lit).not.toHaveCSS('background-color', 'rgba(0, 0, 0, 0)');

  const apply = panel.getByRole('button', { name: '적용하기' });
  expect((await apply.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(44);
  await apply.click();
  await expect(panel.getByText('적용했어요')).toBeVisible();
  await expect(paper).toHaveValue(/그 결과 같은 문제가 다시 생기지 않았습니다\./);
  await expect(page.getByText('2판', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '원본 보기' }).click();
  await expect(page.locator('.cl-original p')).toHaveText(before);
});

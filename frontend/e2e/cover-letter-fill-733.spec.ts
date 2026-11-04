import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

/**
 * #733 채우기 — 목 서버가 대화 요청마다 0.8초 기다리고 결정적으로 답한다.
 * 초안 → 채우기 탭 → Q1 얘기하기 → 질문 → 답 보내기 → 생각하는 중 → 다시 쓰는 중 → 종이의 빈칸 자리가 다시 쓴 문장으로 → 'Q1에 넣었어요'.
 */
test('초안의 채우기에서 답을 보내면 종이의 빈칸 자리가 다시 쓴 문장으로 바뀐다', async ({ page }) => {
  await loginAsDemo(page);
  await page.clock.install();
  await page.goto('/cover-letters/new');
  await page.getByPlaceholder('예: 백엔드 개발').fill('백엔드 개발');
  await page.getByLabel('지원 이유', { exact: true }).fill('결제 서비스의 오류를 줄이는 일을 직접 해 보고 싶어 지원했어요');
  await page.getByLabel('1번 문항', { exact: true }).fill('어려운 문제를 해결한 경험을 적어 주세요');
  await page.getByRole('button', { name: '카드 고르기', exact: true }).click();
  await page.clock.runFor(8_000);
  await page.getByRole('button', { name: '다음: 질문 받기' }).click();
  await page.clock.runFor(6_000);
  await page.getByRole('button', { name: '초안 쓰기' }).click();
  await page.clock.runFor(30_000);
  await expect(page.getByText('초안이 나왔어요')).toBeVisible();

  const panel = page.getByRole('complementary', { name: '다듬기 패널' });
  const paper = page.getByLabel('1번 문항 초안');
  await expect(paper).toHaveValue(/\[더 쓸 거리: /);
  await panel.getByRole('tab', { name: '채우기' }).click();
  await panel.getByRole('button', { name: 'Q1 얘기하기' }).click();
  await expect(panel.getByText('생각하는 중')).toBeVisible();
  await page.clock.runFor(1_000);
  const question = panel.locator('.clp-bub--ai').last();
  await expect(question).toContainText('빈칸이 하나 있어요');
  await expect(question.locator('.clp-qchip')).toHaveText('Q1, 행동');

  const input = panel.getByLabel('한 줄로 답하기');
  await input.fill('백엔드를 맡아 AI 담당 팀원과 로그를 같이 보며 원인을 좁혔어요');
  const send = panel.getByRole('button', { name: '보내기' });
  expect((await send.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(44);
  await input.press('Enter');
  await expect(panel.locator('.clp-bub--me').last()).toHaveText('백엔드를 맡아 AI 담당 팀원과 로그를 같이 보며 원인을 좁혔어요');
  await expect(panel.getByText('생각하는 중')).toBeVisible();
  await page.clock.runFor(1_000);
  await expect(panel.getByText('다시 쓰는 중')).toBeVisible();
  await expect(paper).toHaveValue(/\[더 쓸 거리: /);
  await page.clock.runFor(700);
  await expect(panel.getByText('Q1에 넣었어요')).toBeVisible();
  await expect(paper).toHaveValue(/백엔드를 맡아 AI 담당 팀원과 로그를 같이 보며 원인을 좁혔습니다\./);
  await expect(paper).not.toHaveValue(/\[더 쓸 거리: /);

  await panel.getByRole('button', { name: '보기' }).click();
  const lit = page.locator('mark.phl--flash');
  await expect(lit).toHaveText('백엔드를 맡아 AI 담당 팀원과 로그를 같이 보며 원인을 좁혔습니다.');
  await expect(lit).not.toHaveCSS('background-color', 'rgba(0, 0, 0, 0)');
});

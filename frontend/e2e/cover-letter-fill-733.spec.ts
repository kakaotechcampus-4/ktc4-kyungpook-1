import { expect, test } from '@playwright/test';
import { loginAsDemo, runToDraft } from './helpers';

/**
 * #733 대화 — 목 서버가 대화 요청마다 0.8초 기다리고 결정적으로 답한다.
 * 초안 → 먼저 열려 있는 대화 탭(목 초안에는 항목 빈칸이 없어 스스로 묻지 않는다) → Q1 얘기하기 → 질문 → 답 보내기 → 생각하는 중 → 다시 쓰는 중 → 종이의 빈칸 자리가 다시 쓴 문장으로 → 'Q1에 넣었어요'.
 */
test('초안의 대화에서 답을 보내면 종이의 빈칸 자리가 다시 쓴 문장으로 바뀐다', async ({ page }) => {
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
  await expect(paper).toHaveValue(/\[더 쓸 거리: /);
  await expect(panel.getByRole('tab', { name: '대화' })).toHaveAttribute('aria-selected', 'true');
  await panel.getByRole('button', { name: 'Q1 얘기하기' }).click();
  await expect(panel.getByText('생각하는 중')).toBeVisible();
  await page.clock.runFor(1_000);
  const question = panel.locator('.chat-bub--it').last();
  await expect(question).toContainText('빈칸이 하나 있어요');
  await expect(question.locator('.chat-tag')).toHaveText('Q1, 행동');

  const input = panel.getByLabel('한 줄로 답하기');
  await input.fill('백엔드를 맡아 AI 담당 팀원과 로그를 같이 보며 원인을 좁혔어요');
  const send = panel.getByRole('button', { name: '보내기' });
  expect((await send.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(44);
  await input.press('Enter');
  await expect(panel.locator('.chat-bub--me').last()).toHaveText('백엔드를 맡아 AI 담당 팀원과 로그를 같이 보며 원인을 좁혔어요');
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

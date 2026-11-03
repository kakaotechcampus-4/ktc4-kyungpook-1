import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

/**
 * #719 ② AI 추론 표시 — 목 서버가 배운 점 답이 없는 문항에 추론 문장 하나를 넣는다.
 * 고치는 화면에서 그 문장은 노란 형광펜, 메모는 'AI 추론 · 확인 필요' 이고 확인 · 고치기 · 빼기 버튼은 44px 이상이다.
 * 확인하면 형광펜과 메모가 사라지고 점검표 추론 칸이 통과로 바뀐다.
 */
test('AI 추론 문장은 노란 형광펜과 메모로 보이고, 확인하면 표시가 사라진다', async ({ page }) => {
  await loginAsDemo(page);
  await page.clock.install();
  await page.goto('/cover-letters/new');
  await page.getByPlaceholder('예: 백엔드 개발').fill('백엔드 개발');
  await page.getByLabel('1번 문항', { exact: true }).fill('팀으로 함께 성과를 낸 경험을 적어 주세요');
  await page.getByRole('button', { name: '카드 고르기 시작' }).click();
  await page.clock.runFor(8_000);
  await page.getByRole('button', { name: '다음: 질문 받기' }).click();
  await page.clock.runFor(6_000);
  await page.getByRole('button', { name: '초안 쓰기' }).click();
  await page.clock.runFor(30_000);
  await expect(page.getByText('초안이 나왔어요')).toBeVisible();

  const mark = page.locator('.pedit__mirror mark.phl--inferred');
  await expect(mark).toHaveCount(1);
  const note = page.locator('[data-note-card^="i:"]');
  await expect(note).toContainText('AI 추론 · 확인 필요');
  const background = await mark.evaluate((el) => getComputedStyle(el).backgroundColor);
  expect(background).not.toBe('rgba(0, 0, 0, 0)');
  for (const name of ['확인', '고치기', '빼기']) {
    const box = await note.getByRole('button', { name, exact: true }).boundingBox();
    expect(box?.height ?? 0).toBeGreaterThanOrEqual(44);
  }
  await expect(page.getByRole('button', { name: /^추론\s?확인 필요 1$/ })).toBeEnabled();

  await note.getByRole('button', { name: '확인', exact: true }).click();
  await expect(mark).toHaveCount(0);
  await expect(note).toHaveCount(0);
  await expect(page.getByRole('button', { name: /^추론\s?통과$/ })).toBeVisible();
});

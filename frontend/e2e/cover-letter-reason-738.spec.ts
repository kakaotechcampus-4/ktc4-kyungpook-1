import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

/**
 * #738 만들기 화면 — 지원 이유를 규칙대로 적어야 시작 버튼이 켜지고, 강점 칩 둘을 골라 초안까지 간다.
 */
test('지원 이유를 적고 강점 둘을 고르면 초안까지 간다', async ({ page }) => {
  await loginAsDemo(page);
  await page.clock.install();
  await page.goto('/cover-letters/new');
  await page.getByPlaceholder('예: 백엔드 개발').fill('백엔드 개발');
  const reason = page.getByLabel('지원 이유', { exact: true });
  const start = page.getByRole('button', { name: '카드 고르기', exact: true });
  await reason.fill('결제가 좋아서');
  await expect(page.locator('#cl-reason-hint')).toHaveText('20자 이상 적어 주세요 · 지금 6자');
  await expect(start).toBeDisabled();
  await reason.fill('결제 서비스의 오류를 줄이는 일을 직접 해 보고 싶어 지원했어요');
  await expect(page.locator('#cl-reason-hint')).toBeEmpty();
  await expect(start).toBeEnabled();

  for (const name of ['꼼꼼함', '사용자 관점']) {
    const chip = page.getByRole('button', { name, exact: true });
    await chip.click();
    await expect(chip).toHaveAttribute('aria-pressed', 'true');
    expect((await chip.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(44);
  }
  await page.getByLabel('1번 문항', { exact: true }).fill('지원 동기와 입사 후 포부를 적어 주세요');
  await start.click();
  await page.clock.runFor(8_000);
  await page.getByRole('button', { name: '다음: 질문 받기' }).click();
  await page.clock.runFor(6_000);
  await page.getByRole('button', { name: '초안 쓰기' }).click();
  await page.clock.runFor(30_000);
  await expect(page.getByText('초안이 나왔어요')).toBeVisible();
});

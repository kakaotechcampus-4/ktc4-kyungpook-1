import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

test('공고 상세에서 자소서 초안을 만들고 고치면 새로고침해도 남는다', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await loginAsDemo(page);
  await page.goto('/match');
  await page.getByRole('link', { name: /가온소프트/ }).click();
  await page.getByRole('link', { name: '이 공고로 자소서 초안 만들기' }).click();
  await expect(page.getByRole('combobox', { name: '지원할 공고', exact: true })).toContainText('가온소프트');
  await expect(page.getByRole('checkbox', { name: '토큰 만료 자동 재발급 사용' })).toBeChecked();
  await page.getByRole('button', { name: '초안 만들기' }).click();
  await expect(page).toHaveURL(/\/cover-letter\/cl_/);
  await expect(page.getByText('확정한 카드 문장')).toHaveCount(1);
  await expect(page.getByText('근거가 없어 초안에 넣지 않은 공고 조건 1개')).toBeVisible();
  await expect(page.getByText('직접 쓸 칸 · 지원 동기')).toBeVisible();

  await page.getByRole('button', { name: '초안 고치기' }).click();
  await page.getByRole('textbox', { name: '자소서 초안' }).fill('제가 직접 다듬은 자소서입니다.');
  await page.getByRole('button', { name: '저장하고 닫기' }).click();
  await expect(page.getByText('직접 고침').first()).toBeVisible(); // 저장이 끝나 편집이 닫힌 뒤에 새로고침한다
  await page.reload();
  await expect(page.getByText('제가 직접 다듬은 자소서입니다.')).toBeVisible();

  await page.goto('/cover-letter');
  await expect(page.getByRole('link', { name: /가온소프트.*지원 동기/ })).toContainText('직접 고침');
  expect(errors).toEqual([]);
});

test('글자 수 제한을 넘으면 알려 주고, 지원 동기 칸을 직접 채우면 안내가 사라진다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/cover-letter');
  await expect(page.getByRole('checkbox', { name: '토큰 만료 자동 재발급 사용' })).toBeVisible();
  const limit = page.getByRole('combobox', { name: '글자 수 제한', exact: true });
  await limit.click();
  await page.getByRole('option', { name: '500자', exact: true }).click();
  await page.getByRole('button', { name: '초안 만들기' }).click();
  await expect(page).toHaveURL(/\/cover-letter\/cl_/);
  await expect(page.getByText(/글자 수 제한을 [\d,]+자 넘었어요/)).toBeVisible();

  await page.getByRole('button', { name: '직접 쓰러 가기' }).click();
  await page.getByRole('textbox', { name: '자소서 초안' }).fill('인증 흐름을 끝까지 책임져 보고 싶어 지원했습니다.');
  await expect(page.getByText('지원 동기 칸이 아직 비어 있어요')).toHaveCount(0);
  await expect(page.getByText(/글자 수 제한을 [\d,]+자 넘었어요/)).toHaveCount(0);
  await expect(page.getByText('28자 / 500자')).toBeVisible();
});

for (const width of [320, 390, 768, 1440]) {
  test(`${width}px 에서 자소서 화면이 가로로 넘치지 않는다`, async ({ page }) => {
    await loginAsDemo(page);
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/cover-letter');
    await expect(page.getByRole('heading', { name: '자소서 초안 만들기' })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.getByRole('button', { name: '초안 만들기' }).click();
    await expect(page).toHaveURL(/\/cover-letter\/cl_/);
    await expect(page.getByText('AI 초안')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  });
}

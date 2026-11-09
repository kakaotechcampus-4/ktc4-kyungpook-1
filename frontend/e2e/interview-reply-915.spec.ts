import { expect, test, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

/** #915: 되묻기 답을 AI 가 먼저 읽는다. 목 서버가 서버처럼 카드에 넣거나(APPLIED) 넣지 않는다(NOT_USED) */

const panel = (page: Page) => page.locator('.iv__panel');
const task = (page: Page) => page.evaluate(async () => (await (await fetch('/api/cards/card_01')).json()).data.version.task as string | null);
const overflows = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);

test('E1 답을 보내면 다듬은 문장이 카드에 들어가고, 고치기로 고친 글이 저장된다', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await loginAsDemo(page);
  await page.goto('/cards/card_01/interview?field=T');
  await page.getByRole('textbox', { name: '답변', exact: true }).fill('배포 시간을 줄여야 했어요');
  await page.getByRole('button', { name: '답 보내기', exact: true }).click();

  await expect(panel(page).getByText('과제 칸에 이렇게 넣었어요')).toBeVisible();
  await expect(panel(page).locator('.iv-put__box')).toContainText('배포 시간을 줄여야 했다');
  await expect(page.getByLabel('카드 미리보기').getByText('방금 넣었어요')).toBeVisible();
  expect(await task(page)).toBe('배포 시간을 줄여야 했다');

  await panel(page).getByRole('button', { name: '고치기', exact: true }).click();
  await panel(page).getByRole('textbox', { name: '과제 칸 문장' }).fill('배포 시간을 줄여 하루 여러 번 올려야 했다');
  await panel(page).getByRole('button', { name: '저장', exact: true }).click();
  await expect(panel(page).getByText('직접 고친 글로 바꿨어요')).toBeVisible();
  expect(await task(page)).toBe('배포 시간을 줄여 하루 여러 번 올려야 했다');
  await expect(page.getByLabel('카드 미리보기').getByText('직접 고쳤어요')).toBeVisible();

  await page.setViewportSize({ width: 390, height: 844 });
  expect(await overflows(page)).toBe(false);
});

test("E2 '하하하하하' 는 카드에 들어가지 않고 같은 질문이 열려 있다", async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/cards/card_01/interview?field=T');
  const before = await task(page);
  await page.getByRole('textbox', { name: '답변', exact: true }).fill('하하하하하');
  await page.getByRole('button', { name: '답 보내기', exact: true }).click();

  await expect(panel(page).getByText('이 답은 카드에 넣지 않았어요.')).toBeVisible();
  await expect(panel(page).getByRole('heading', { level: 2, name: /^Q1\./ })).toBeVisible();
  expect(await task(page)).toBe(before);
});

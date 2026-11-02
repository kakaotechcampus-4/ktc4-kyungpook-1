import { test, expect, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

/** #430 후속: 되묻기에서 AI 가 고른 칸과 이유, 다시 묻는 이유, 끝내는 이유가 카드 문장과 구분되는 'AI 판단' 으로 보인다. 목 서버가 서버처럼 reason 과 null 을 섞어 준다 */

const panel = (page: Page) => page.locator('.iv__panel');
async function answerWith(page: Page, text: string) {
  await page.getByRole('textbox', { name: '답변', exact: true }).fill(text);
  await page.getByRole('button', { name: '답변 저장', exact: true }).click();
}
const overflows = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);

test('되묻기 — AI 가 고른 칸과 다시 묻는 이유가 AI 판단으로 보이고, 사용자가 고른 칸에는 없으며, 낭독 영역은 한 곳이다', async ({ page }) => {
  await loginAsDemo(page);
  // 사용자가 칸을 골라 들어오면 AI 판단이 없다(USER_REQUEST, 서버 reason 이 null)
  await page.goto('/cards/card_02/interview?field=R');
  await expect(page.getByRole('heading', { level: 2, name: /작업을 마친 뒤/ })).toBeVisible();
  await expect(panel(page).locator('.ai-judgement')).toHaveCount(0);

  // 수치 없는 답이면 AI 가 같은 칸을 다시 묻고, 앞 답에서 모자란 점을 AI 판단으로 말한다
  await answerWith(page, '결제 중복 오류가 눈에 띄게 줄었다');
  const again = page.getByRole('heading', { level: 2, name: /숫자로 말하면/ });
  await expect(again).toBeVisible();
  const judgement = panel(page).locator('.ai-judgement');
  await expect(judgement).toContainText('AI 판단');
  await expect(judgement).toContainText('결과 칸 다시 묻기');
  await expect(judgement).toContainText('앞 답에 결과 수치가 빠져 있어요');
  expect((await judgement.boundingBox())!.y).toBeLessThan((await again.boundingBox())!.y);
  // 카드 문장 쪽에는 섞이지 않는다
  await expect(page.locator('.iv__canvas').getByText('AI 판단')).toHaveCount(0);

  // 모바일 폭에서 서버 글이 길고 HTML 처럼 생겨도 글로만 보이고 가로로 넘치지 않는다(목 DB 의 열린 질문 이유를 바꿔 다시 연다)
  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => {
    const db = JSON.parse(sessionStorage.getItem('gitory.demo.db')!);
    const turns = db.interview.card_02;
    turns[turns.length - 1].reason = `<b>굵게</b>\n앞 답에 ${'결과가 '.repeat(30)}https://example.com/${'a'.repeat(100)}`;
    sessionStorage.setItem('gitory.demo.db', JSON.stringify(db));
  });
  await page.goto('/cards/card_02/interview');
  await expect(judgement.locator('q')).toContainText('<b>굵게</b> 앞 답에');
  await expect(judgement.locator('b')).toHaveCount(0);
  expect(await overflows(page)).toBe(false);

  // 숫자가 있는 답이면 같은 칸을 또 묻지 않고, AI 가 빈 칸(행동)을 골라 이유와 함께 묻는다
  await answerWith(page, '결제 중복 오류 제보가 주 12건에서 2건으로 줄었다');
  await expect(page.getByRole('heading', { level: 2, name: /문제를 해결하기 위해/ })).toBeVisible();
  await expect(judgement).toContainText('행동 칸');
  await expect(judgement).not.toContainText('다시 묻기');
  await expect(judgement).toContainText('행동 칸이 비어 있어 먼저 물어볼게요');

  // 마지막 칸을 채우면 서버가 카드를 확정한다. AI 가 끝낸 것이 아니므로 끝낸 이유를 지어내지 않는다
  await answerWith(page, '테스트를 먼저 붙이고 고쳤다');
  await expect(page.getByText('네 칸이 모두 차서 카드를 확정했어요')).toBeVisible();
  await expect(panel(page).locator('.ai-judgement')).toHaveCount(0);
  // 저장하는 동안만 보이는 대기 문장(AiWait)이 사라진 뒤에는 낭독 영역이 이 한 곳뿐이고, 확정 안내가 그 안에 있다
  const live = panel(page).locator('[aria-live]');
  await expect(live).toHaveCount(1);
  await expect(live).toHaveAttribute('aria-live', 'polite');
  await expect(live).toContainText('네 칸이 모두 차서 카드를 확정했어요');
});

test('되묻기 — AI 가 끝내며 남긴 이유가 AI 판단으로 보이고, 화면을 다시 열어도 같은 끝 화면이다', async ({ page }) => {
  await loginAsDemo(page);
  await page.setViewportSize({ width: 390, height: 844 });
  // 이 카드는 T 가 마지막 빈 칸이지만 확인 필요 칸이 있어 확정되지 않고, AI 가 질문을 끝낸다
  await page.goto('/cards/card_01/interview?field=T');
  await expect(page.getByRole('heading', { level: 2 }).first()).toBeVisible();
  await expect(panel(page).locator('.ai-judgement')).toHaveCount(0);
  await answerWith(page, '코드 리뷰 때 합의한 대로 만료 처리를 맡았다');

  await expect(page.getByText('AI 가 더 물을 것이 없다고 판단했어요')).toBeVisible();
  const live = panel(page).locator('[aria-live]');
  await expect(live).toHaveCount(1);
  await expect(live).toContainText('AI 가 더 물을 것이 없다고 판단했어요');
  await expect(live.locator('.ai-judgement')).toContainText('AI 판단');
  await expect(live.locator('.ai-judgement')).toContainText('더 물을 빈 칸이 없어 질문을 마칠게요');

  // 새로 고쳐도 카드에 남은 이유로 같은 끝 화면이 나오고, 눌러야 하는 것은 44px 이상이며 가로로 넘치지 않는다
  await page.goto('/cards/card_01/interview');
  await expect(panel(page).locator('.ai-live .ai-judgement')).toContainText('더 물을 빈 칸이 없어 질문을 마칠게요');
  await expect(page.getByRole('button', { name: '카드 확인하고 확정하기' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'AI 가 고르게 하기' })).toHaveCount(0);
  expect(await overflows(page)).toBe(false);
  for (const control of await panel(page).locator('button, a').all()) {
    if (!(await control.isVisible())) continue;
    expect((await control.boundingBox())!.height, await control.innerText()).toBeGreaterThanOrEqual(44);
  }
});

import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

/** #916: 새 말이 와도 그 말의 첫 줄이 가려지지 않는다. 대화 칸이 작아지는 낮은 창에서 잰다 */

test('C3 되묻기에서 새 질문이 오면 질문의 첫 줄이 대화 칸 안에 보인다', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 620 });
  await loginAsDemo(page);
  await page.goto('/cards/card_02/interview?field=R');
  const box = page.getByRole('textbox', { name: '답변', exact: true });
  const startVisible = () => page.evaluate(() => {
    const thread = document.querySelector('.iv__thread')!.getBoundingClientRect();
    // 이유 줄이 있으면 그 줄, 없으면 열린 질문의 이름표가 질문의 첫 줄이다
    const start = document.querySelector('.iv__thread .ai-judgement') ?? Array.from(document.querySelectorAll('.iv__thread .chat-from')).pop()!;
    const top = start.getBoundingClientRect().top;
    return top >= thread.top - 1 && top < thread.bottom;
  });
  // 수치 없는 답이면 AI 가 이유 줄과 함께 같은 칸을 다시 묻는다 — 이유 줄 없는 질문과 있는 질문을 차례로 본다
  for (const [round, answer] of [[1, '결제 중복 오류가 눈에 띄게 줄었다'], [2, '']] as const) {
    await expect(page.getByRole('heading', { level: 2, name: round === 1 ? /작업을 마친 뒤/ : /숫자로 말하면/ })).toBeVisible();
    await expect.poll(startVisible, { message: `질문 ${round} 의 첫 줄`, timeout: 5_000 }).toBe(true);
    if (!answer) break;
    await box.fill(answer);
    await page.getByRole('button', { name: '답변 저장', exact: true }).click();
  }
});

test('C5 홈 대화에서 화면보다 긴 답이 오면 그 답의 첫 줄이 화면 안에 보이고 입력 줄에 가리지 않는다', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 520 });
  await loginAsDemo(page);
  await page.goto('/ask');
  const box = page.getByRole('textbox', { name: '할 일이나 답' });
  // 꺼진 빌드는 화면이 그려진 뒤에 홈으로 돌려보내서 주소만 보면 늦다. 입력 칸이 뜨는지로 켜진 빌드인지 본다
  const opened = await box.waitFor({ state: 'visible', timeout: 8_000 }).then(() => true, () => false);
  test.skip(!opened, '홈 대화가 꺼진 빌드(VITE_ASSISTANT_ENABLED)에서는 열리지 않는다');
  await box.fill('토스 공고로 자소서 써 줘');
  await box.press('Enter');
  await expect(page.getByText('답하면 이어서 해 드려요')).toBeVisible({ timeout: 40_000 });
  const measure = () => page.evaluate(() => {
    const thread = document.querySelector('.ask__thread')!;
    const marks = Array.from(thread.querySelectorAll('.chat-bub--me, .chat-from'));
    const lastMe = marks.map((el) => el.classList.contains('chat-bub--me')).lastIndexOf(true);
    const start = marks.slice(lastMe + 1)[0].getBoundingClientRect();
    const dock = document.querySelector('.ask__dock')!.getBoundingClientRect();
    return { top: start.top, dockTop: dock.top, longer: thread.getBoundingClientRect().bottom - start.top > dock.top };
  });
  await expect.poll(async () => (await measure()).longer, { message: '이 창에서 새 답이 화면보다 길다' }).toBe(true);
  await expect.poll(async () => { const m = await measure(); return m.top >= 0 && m.top < m.dockTop; }, { message: '답의 첫 줄' }).toBe(true);
});

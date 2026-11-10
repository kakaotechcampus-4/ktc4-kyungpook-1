import { expect, test } from '@playwright/test';
import { FAKE, dataOf, jobEnds, loginAndSetUp, newUser, startAnalysis } from './support';

// LLM 이 응답하지 않으면 정리가 AI_UNAVAILABLE 로 끝나고, 복구된 뒤 다시 시도하면 새 Job 이 끝까지 간다
test('시나리오 4: 정리 중 AI 가 응답하지 않으면 안내를 보여 주고 다시 시도해 이어 간다', async ({ page, request }) => {
  const login = await newUser(request);

  await test.step('1. 로그인', async () => {
    await loginAndSetUp(page, login);
    expect((await dataOf(await page.request.get('/api/me'))).login).toBe(login);
  });

  let jobId: string;
  await test.step('2. LLM 이 실패하는 동안 정리를 시작하면 AI_UNAVAILABLE 로 끝난다', async () => {
    expect((await request.post(`${FAKE}/__control/fail`, { data: { pattern: 'chat/completions$', status: 503 } })).ok()).toBeTruthy();
    jobId = await startAnalysis(page, login);
    await jobEnds(page, jobId, { state: 'FAILED', errorCode: 'AI_UNAVAILABLE' });
  });

  await test.step('3. 실패 화면이 AI 응답 없음을 알리고 다시 시도 버튼을 보여 준다', async () => {
    await expect(page.getByRole('heading', { name: 'AI가 잠시 응답하지 않아요' })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: '다시 시도' })).toBeVisible();
  });

  await test.step('4. 실패를 풀고 다시 시도하면 새 Job 이 끝까지 가고 화면이 이어진다', async () => {
    expect((await request.delete(`${FAKE}/__control/fail`)).ok()).toBeTruthy();
    const started = page.waitForResponse((r) => /\/api\/repos\/[^/]+\/analyze$/.test(r.url()) && r.request().method() === 'POST');
    await page.getByRole('button', { name: '다시 시도' }).click();
    const again: string = (await dataOf(await started)).jobId;
    expect(again).not.toBe(jobId);
    await jobEnds(page, again, { state: 'SUCCEEDED', errorCode: null });
    await expect(page.getByRole('heading', { name: 'AI가 잠시 응답하지 않아요' })).toHaveCount(0);
  });
});

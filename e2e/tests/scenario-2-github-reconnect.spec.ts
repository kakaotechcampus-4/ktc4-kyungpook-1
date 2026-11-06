import { expect, test } from '@playwright/test';
import { FAKE, dataOf, jobEnds, loginAndSetUp, newUser, queueLogin, startAnalysis } from './support';

// GitHub 이 토큰을 거절하면(만료·취소) 정리가 GITHUB_UNAUTHORIZED 로 끝나고 서버가 연결을 끊음으로 표시한다.
// 다시 연결하면 새 토큰으로 같은 레포를 정리할 수 있어야 한다
test('시나리오 2: 정리 중 GitHub 연결이 끊기면 다시 연결해 이어 간다', async ({ page, request }) => {
  const login = await newUser(request);

  await test.step('1. 로그인', async () => {
    await loginAndSetUp(page, login);
    expect((await dataOf(await page.request.get('/api/me'))).github.connected).toBe(true);
  });

  let jobId: string;
  await test.step('2. GitHub 이 토큰을 거절하는 동안 정리를 시작한다', async () => {
    // 레포 목록은 이미 센 뒤라 저장소 읽기만 막는다. 로그인(/login/oauth)은 막지 않는다
    expect((await request.post(`${FAKE}/__control/fail`, { data: { pattern: '^/(repos/|graphql)', status: 401 } })).ok()).toBeTruthy();
    jobId = await startAnalysis(page, login);
    await jobEnds(page, jobId, { state: 'FAILED', errorCode: 'GITHUB_UNAUTHORIZED' });
  });

  await test.step('3. 실패 화면이 다시 연결을 권하고 연결이 끊김으로 바뀐다', async () => {
    await expect(page.getByText('GitHub 연결 새로 하기')).toBeVisible({ timeout: 15_000 });
    expect((await dataOf(await page.request.get('/api/me'))).github.connected).toBe(false);
  });

  await test.step('4. 다시 연결하면 정리 화면으로 돌아온다', async () => {
    expect((await request.delete(`${FAKE}/__control/fail`)).ok()).toBeTruthy();
    await queueLogin(request, login);
    const row = page.locator('.card--paper', { hasText: 'GitHub 연결 새로 하기' });
    await row.getByRole('button', { name: '다시 연결' }).click();
    await expect(page).toHaveURL(/\/repos\/[^/]+\/run/, { timeout: 15_000 });
    const me = await dataOf(await page.request.get('/api/me'));
    expect(me.login).toBe(login);
    expect(me.github.connected).toBe(true);
  });

  await test.step('5. 같은 레포를 다시 정리하면 끝까지 간다', async () => {
    const again = await startAnalysis(page, login);
    expect(again).not.toBe(jobId);
    await jobEnds(page, again, { state: 'SUCCEEDED', errorCode: null });
  });

  await test.step('6. 다시 연결한 뒤 가짜 GitHub 가 받은 토큰은 로그인 사용자의 것뿐이다', async () => {
    const log: { path: string; authorization: string | null }[] = await (await request.get(`${FAKE}/__control/requests`)).json();
    const github = log.filter((r) => !/chat\/completions$|^\/login\/oauth|^\/avatars\//.test(r.path));
    expect(github.filter((r) => r.authorization !== `Bearer gho_E2E_${login}`)).toEqual([]);
  });
});

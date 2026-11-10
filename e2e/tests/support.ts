import { expect, type APIRequestContext, type APIResponse, type Page } from '@playwright/test';

// 시나리오 2·3 이 함께 쓰는 단계. 시나리오 1 은 같은 단계를 더 자세히 확인해서 따로 둔다
export const FAKE = 'http://localhost:9000';
export const SLOW = 90_000;

// 상태 코드가 먼저 보이도록 응답을 확인한 뒤 data 를 꺼낸다
export async function dataOf(res: APIResponse) {
  expect(res.ok(), `${res.url()} ${res.status()} ${await res.text()}`).toBeTruthy();
  return (await res.json()).data;
}

/** 시험마다 새 사용자라 이전 실행이 남긴 저장소·카드와 섞이지 않는다. 가짜 서버의 실패 주입·요청 기록도 비운다 */
export async function newUser(request: APIRequestContext): Promise<string> {
  const login = `e2e-${Date.now().toString(36)}`;
  expect((await request.post(`${FAKE}/__control/reset`)).ok()).toBeTruthy();
  await queueLogin(request, login);
  return login;
}

/** 가짜 GitHub 가 다음 OAuth 로그인을 이 사용자로 돌려보내게 한다. 다시 연결할 때도 같은 사용자를 넣는다 */
export async function queueLogin(request: APIRequestContext, login: string) {
  expect((await request.post(`${FAKE}/__control/next-user`, { data: { login } })).ok()).toBeTruthy();
}

/** 랜딩에서 GitHub 로 로그인하고 처음 입력 창을 가장 짧게 끝낸다 */
export async function loginAndSetUp(page: Page, login: string) {
  await page.goto('/');
  await expect(page).toHaveURL(/\/login/);
  await page.locator('.landing__hero .landing__actions').getByRole('button', { name: 'GitHub로 시작' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'GitHub으로 이동' }).click();
  await expect(page).toHaveURL('/', { timeout: 15_000 });
  expect((await dataOf(await page.request.get('/api/me'))).login).toBe(login);

  const setup = page.getByRole('dialog');
  await expect(setup).toHaveAccessibleName('관심 분야');
  await setup.getByRole('checkbox', { name: '백엔드', exact: true }).check();
  await setup.getByRole('button', { name: '다음', exact: true }).click();
  await setup.getByRole('button', { name: '경력 없음', exact: true }).click();
  await setup.getByRole('radio', { name: '대학교', exact: true }).check();
  await setup.getByRole('button', { name: '다음', exact: true }).click();
  await setup.getByRole('radio', { name: '졸업', exact: true }).check();
  await setup.getByRole('button', { name: '다음', exact: true }).click();
  await setup.getByRole('button', { name: '어학 없음', exact: true }).click();
  await setup.getByRole('button', { name: '자격증 없음', exact: true }).click();
  await expect(setup).toHaveCount(0);
}

/** 레포 목록에서 sample-app 을 고르고 안내 창에서 정리를 시작한다. 시작한 Job id 를 돌려준다 */
export async function startAnalysis(page: Page, login: string): Promise<string> {
  await page.goto('/repos');
  await page.getByRole('radio', { name: new RegExp(`${login} / sample-app`) }).click();
  await page.getByRole('button', { name: '다음' }).click();
  const started = page.waitForResponse((r) => /\/api\/repos\/[^/]+\/analyze$/.test(r.url()) && r.request().method() === 'POST');
  await page.getByRole('dialog').getByRole('button', { name: '정리 시작' }).click();
  const jobId: string = (await dataOf(await started)).jobId;
  await expect(page).toHaveURL(/\/repos\/[^/]+\/run\?job=/);
  return jobId;
}

/** 화면이 넘어가기만 기다리면 Job 이 다른 코드로 끝나도 원인이 안 남아 상태를 직접 본다 */
export async function jobEnds(page: Page, jobId: string, end: { state: string; errorCode: string | null }) {
  await expect
    .poll(async () => {
      const j = await dataOf(await page.request.get(`/api/jobs/${jobId}`));
      return { state: j.state, errorCode: j.errorCode };
    }, { timeout: SLOW })
    .toEqual(end);
}

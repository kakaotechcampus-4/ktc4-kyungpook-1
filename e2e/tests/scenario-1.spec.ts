import { expect, test, type APIResponse } from '@playwright/test';

const FAKE = 'http://localhost:9000';
const SLOW = 90_000;

const SITUATION = '로그인 실패 때 사용자에게 원인이 보이지 않았다.';
const ACTION = '실패 코드별 메시지를 정리하고 테스트를 추가했다.';
const ANSWER = '로그인 실패 원인을 사용자가 알 수 있게 하는 것이 목표였다.';
const CANDIDATE = 'E2E 후보 pr:3';

// 상태 코드가 먼저 보이도록 응답을 확인한 뒤 data 를 꺼낸다
async function dataOf(res: APIResponse) {
  expect(res.ok(), `${res.url()} ${res.status()} ${await res.text()}`).toBeTruthy();
  return (await res.json()).data;
}

test.describe('시나리오 1: 로그인부터 카드 확정까지', () => {
  // 시험마다 새 사용자라 이전 실행이 남긴 저장소·카드와 섞이지 않는다
  let login: string;
  let token: string;

  test.beforeEach(async ({ request }) => {
    login = `e2e-${Date.now().toString(36)}`;
    token = `Bearer gho_E2E_${login}`;
    expect((await request.post(`${FAKE}/__control/reset`)).ok()).toBeTruthy();
    expect((await request.post(`${FAKE}/__control/next-user`, { data: { login } })).ok()).toBeTruthy();
  });

  test('GitHub 로그인 → 분석 → 후보 → 카드 작성 → 되묻기 → 확정', async ({ page, request }) => {
    await test.step('1. 로그인', async () => {
      await page.goto('/');
      await expect(page).toHaveURL(/\/login/);
      await page.locator('.landing__actions').getByRole('button', { name: 'GitHub로 시작' }).click();
      await page.getByRole('dialog').getByRole('button', { name: 'GitHub으로 이동' }).click();
      await expect(page).toHaveURL('/', { timeout: 15_000 });

      const me = await dataOf(await page.request.get('/api/me'));
      expect(me.login).toBe(login);
      const session = (await page.context().cookies()).find((c) => c.name === 'SESSION');
      expect(session?.httpOnly).toBe(true);
    });

    await test.step('2. 저장소 고르기와 개수', async () => {
      await page.goto('/repos');
      const row = page.getByRole('radio', { name: new RegExp(`${login} / sample-app`) });
      await row.click();
      await expect(row).toContainText('내 커밋 10');
      await expect(row).toContainText('PR 2');
      await expect(row).toContainText('추천');

      const repos = await dataOf(await page.request.get('/api/repos'));
      expect(repos).toHaveLength(1);
      expect(repos[0]).toMatchObject({
        name: 'sample-app',
        prCount: 2,
        reviewCount: 0,
        recommended: true,
        contribution: { mine: 10, team: 0, level: 'MAJOR' },
      });
      expect(repos[0].countedAt).toBeTruthy();
    });

    let jobId: string;
    await test.step('3. 안내 후 분석 시작', async () => {
      await page.getByRole('button', { name: '다음' }).click();
      const notice = page.getByRole('dialog');
      await expect(notice.getByText('이건 읽어요')).toBeVisible();
      await expect(notice.getByText('이건 안 읽어요')).toBeVisible();
      await expect(notice.getByText('카드에는 내 커밋 10개만 써요')).toBeVisible();

      const started = page.waitForResponse((r) => /\/api\/repos\/[^/]+\/analyze$/.test(r.url()) && r.request().method() === 'POST');
      await notice.getByRole('button', { name: '정리 시작' }).click();
      jobId = (await dataOf(await started)).jobId;
      await expect(page).toHaveURL(/\/repos\/[^/]+\/run\?job=/);
    });

    await test.step('4. 후보 보드', async () => {
      // 화면이 넘어가기만 기다리면 Job 이 FAILED 로 끝나도 원인 코드가 안 남아 상태를 직접 본다
      await expect
        .poll(
          async () => {
            const j = await dataOf(await page.request.get(`/api/jobs/${jobId}`));
            return { state: j.state, partial: j.partial, errorCode: j.errorCode };
          },
          { timeout: SLOW },
        )
        .toEqual({ state: 'SUCCEEDED', partial: false, errorCode: null });
      await expect(page).toHaveURL(/\/candidates/, { timeout: SLOW });
      await expect(page.getByRole('heading', { name: /^후보 \d+개/ })).toBeVisible();
      // 가짜 LLM 이 만든 제목이 보이면 LLM 경로를 탄 것이다
      await expect(page.getByText(CANDIDATE).first()).toBeVisible();
    });

    let cardId: string;
    await test.step('5. 후보 하나로 카드 만들기', async () => {
      // 후보 순서는 AI 점수에 기대므로 이름으로 고른다
      await page.getByRole('checkbox', { name: `${CANDIDATE} 선택` }).check();
      await page.getByRole('button', { name: '선택한 1개로 카드 만들기' }).click();
      await expect(page).toHaveURL(/\/cards\/[^/?]+$/, { timeout: SLOW });
      cardId = new URL(page.url()).pathname.split('/').pop()!;
    });

    await test.step('6. 상황·행동 직접 쓰기', async () => {
      // 과제는 비워 둬서 되묻기에 쓴다
      // 하단 "직접 수정"은 없앴다(#452). 칸마다 있는 버튼으로 편집에 들어간다 — 상황이 채워져 있으면 "상황 수정", 비어 있으면 "상황 직접 작성"
      await page.getByRole('button', { name: /^상황 (수정|직접 작성)$/ }).click();
      await page.getByRole('textbox', { name: '상황', exact: true }).fill(SITUATION);
      await page.getByRole('textbox', { name: '행동', exact: true }).fill(ACTION);
      await expect(page.getByText('저장됨', { exact: true })).toBeVisible({ timeout: 15_000 });
      await page.getByRole('button', { name: '저장하고 닫기' }).click();
    });

    await test.step('7. 되묻기', async () => {
      await page.getByRole('button', { name: '과제 답변하기' }).first().click();
      await expect(page).toHaveURL(/\/interview\?field=T/);
      await expect(page.getByRole('heading', { name: /^Q1\. \S/ })).toBeVisible({ timeout: SLOW });
      await page.getByRole('textbox', { name: '답변' }).fill(ANSWER);
      await page.getByRole('button', { name: '답변 저장' }).click();
      await expect(page.getByText('내 답변, 저장됨')).toBeVisible({ timeout: 15_000 });
      // 질문이 끝나면 "카드로" 링크를 두지 않아서(AI 가 더 묻지 않기로 하면 열린 질문이 없다) 카드 주소로 바로 간다
      await page.goto(`/cards/${cardId}`);
      await expect(page.getByRole('region', { name: '과제', exact: true })).toContainText(ANSWER);
    });

    await test.step('8. 확정', async () => {
      await page.getByRole('button', { name: '확정', exact: true }).click();
      await page.getByRole('dialog').getByRole('button', { name: '확정하기' }).click();
      await expect(page.getByText('확정됨').first()).toBeVisible({ timeout: 15_000 });

      const card = await dataOf(await page.request.get(`/api/cards/${cardId}`));
      expect(card.status).toBe('CONFIRMED');
      expect(card.version.versionNo).toBeGreaterThan(1);
      expect(card.version).toMatchObject({ situation: SITUATION, task: ANSWER, action: ACTION, result: null });
    });

    await test.step('9. 가짜 GitHub 가 받은 토큰', async () => {
      // 다른 토큰·빈 토큰이 한 번도 없어야 한다
      const log: { path: string; authorization: string | null }[] = await (await request.get(`${FAKE}/__control/requests`)).json();
      const github = log.filter((r) => !/chat\/completions$|^\/login\/oauth|^\/avatars\//.test(r.path));
      expect(github.some((r) => r.path.startsWith('/repos/'))).toBe(true);
      expect(github.filter((r) => r.authorization !== token)).toEqual([]);
    });
  });
});

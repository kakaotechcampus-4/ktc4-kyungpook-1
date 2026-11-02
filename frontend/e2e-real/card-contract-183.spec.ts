import { expect, test } from '@playwright/test';
import { CARD_ID, REPO_ID, cardFixture, installContractFixture } from './contract-fixture';

const uuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const labels = ['상황', '과제', '행동', '결과'];

test('lost manual response retries one UUID and adopts current server metadata while retaining local STAR', async ({ page }) => {
  const http = await installContractFixture(page, { loseFirstManualResponse: true });
  await page.goto('/cards/new');
  await page.getByLabel('카드 제목', { exact: true }).fill('첫 요청 제목');
  await page.getByPlaceholder('2024.04').fill('첫 요청 기간');
  const situation = '  내가 작성한 상황\n원문을 유지합니다.  ';
  const action = '  직접 작성한 행동\n그대로 저장합니다.  ';
  await page.getByLabel(labels[0], { exact: true }).fill(situation);
  await page.getByLabel(labels[2], { exact: true }).fill(action);
  await page.getByLabel(labels[3], { exact: true }).fill(' \n\t\u001c\u001d\u001e\u001f ');
  await page.getByRole('button', { name: '작성 시작', exact: true }).click();
  await expect(page.locator('main').getByRole('status')).toContainText('저장이 안 됐어요');
  expect(http.manual()).toHaveLength(1);
  expect(http.patches()).toHaveLength(0);

  // Another session has edited the first card after the successful server write / lost reply.
  http.replace({ ...http.current(), title: '서버의 현재 제목', period: '서버의 현재 기간',
    repo: { id: REPO_ID, owner: 'contract-user', name: 'server-repo' },
    version: { ...http.current().version, situation: '서버 상황', task: '서버의 현재 과제', action: '서버 행동', result: '서버 결과' },
  });
  await page.getByLabel('카드 제목', { exact: true }).fill('재요청에서 바꾼 제목');
  await page.getByPlaceholder('2024.04').fill('재요청에서 바꾼 기간');
  await page.getByRole('button', { name: '다시 저장', exact: true }).click();
  await expect(page.locator('main').getByRole('status')).toHaveText('저장됨');
  expect(http.manual()).toHaveLength(2);
  const [first, retry] = http.manual();
  expect(first.headers['idempotency-key']).toMatch(uuidV4);
  expect(retry.headers['idempotency-key']).toBe(first.headers['idempotency-key']);
  expect(first.body).toEqual({ title: '첫 요청 제목', period: '첫 요청 기간', repoId: null });
  expect(retry.body).toEqual({ title: '재요청에서 바꾼 제목', period: '재요청에서 바꾼 기간', repoId: null });
  for (const request of http.manual()) {
    expect(request.headers['content-type']).toContain('application/json');
    expect(request.headers['x-xsrf-token']).toBe('contract csrf');
  }
  await expect(page.getByLabel('카드 제목', { exact: true })).toHaveValue('서버의 현재 제목');
  await expect(page.getByLabel('카드 제목', { exact: true })).toBeDisabled();
  await expect(page.getByPlaceholder('2024.04')).toHaveValue('서버의 현재 기간');
  await expect(page.getByRole('combobox', { name: '관련 레포', exact: true })).toContainText('contract-user/server-repo');
  await expect(page.getByLabel(labels[0], { exact: true })).toHaveValue(situation);
  await expect(page.getByLabel(labels[1], { exact: true })).toHaveValue('서버의 현재 과제');
  await expect(page.getByLabel(labels[2], { exact: true })).toHaveValue(action);
  expect(http.patches()).toHaveLength(1);
  expect(http.patches()[0].body).toEqual({ situation, task: '서버의 현재 과제', action, result: null });
  expect(http.patches()[0].headers['x-xsrf-token']).toBe('contract csrf');
  expect(http.requests.some(request => request.path.endsWith('/metadata'))).toBe(false);
  expect(http.unexpected).toEqual([]);

  // Finish the first entry and enter a new form through navigation (same app session).
  await page.getByRole('button', { name: '저장 후 종료', exact: true }).click();
  await expect(page).toHaveURL(`/cards/${CARD_ID}`);
  await page.getByRole('navigation', { name: '커리어 관리', exact: true }).getByRole('link', { name: '경험 카드', exact: true }).click();
  await page.getByRole('link', { name: /직접 작성/ }).click();
  await page.getByLabel('카드 제목', { exact: true }).fill('새 진입 제목');
  await page.getByRole('button', { name: '작성 시작', exact: true }).click();
  await expect(page.locator('main').getByRole('status')).toHaveText('저장됨');
  expect(http.manual()).toHaveLength(3);
  expect(http.manual()[2].headers['idempotency-key']).toMatch(uuidV4);
  expect(http.manual()[2].headers['idempotency-key']).not.toBe(first.headers['idempotency-key']);
  expect(http.unexpected).toEqual([]);
});

test('manual contract limits stay within desktop and mobile bounds in both themes', async ({ page }, testInfo) => {
  const http = await installContractFixture(page);
  await page.goto('/cards/new');
  await page.getByLabel('카드 제목', { exact: true }).fill('제'.repeat(256));
  await page.getByPlaceholder('2024.04').fill('기'.repeat(50));
  await page.getByLabel(labels[0], { exact: true }).fill('상'.repeat(1000));
  await page.getByLabel(labels[2], { exact: true }).fill('행'.repeat(1000));
  await expect(page.getByLabel('카드 제목', { exact: true })).toHaveAttribute('maxlength', '256');
  await expect(page.getByPlaceholder('2024.04')).toHaveAttribute('maxlength', '50');
  for (const label of labels) await expect(page.getByLabel(label, { exact: true })).toHaveAttribute('maxlength', '1000');
  for (const view of [
    { width: 1440, height: 1024, theme: 'light' },
    { width: 320, height: 740, theme: 'light' },
    { width: 390, height: 844, theme: 'dark' },
  ]) {
    await page.setViewportSize({ width: view.width, height: view.height });
    await page.evaluate(theme => { document.documentElement.dataset.theme = theme; }, view.theme);
    await expect(page.getByLabel(labels[0], { exact: true })).toHaveValue('상'.repeat(1000));
    const bounds = await page.evaluate(() => ({ page: document.documentElement.scrollWidth, viewport: innerWidth,
      fields: Array.from(document.querySelectorAll('main input, main textarea')).map(field => ({ left: field.getBoundingClientRect().left, right: field.getBoundingClientRect().right })),
    }));
    expect(bounds.page).toBeLessThanOrEqual(bounds.viewport);
    for (const field of bounds.fields) { expect(field.left).toBeGreaterThanOrEqual(0); expect(field.right).toBeLessThanOrEqual(bounds.viewport); }
    const screenshot = testInfo.outputPath(`manual-${view.width}-${view.theme}.png`);
    await page.screenshot({ path: screenshot, fullPage: true });
    await testInfo.attach(`manual-${view.width}-${view.theme}`, { path: screenshot, contentType: 'image/png' });
  }
  expect(http.manual()).toHaveLength(0);
  expect(http.unexpected).toEqual([]);
});

test('real edit sends all four keys with null blanks, preserves writing, and enforces 1000 characters', async ({ page }) => {
  const initial = cardFixture({ version: { ...cardFixture().version, situation: '기존 상황', task: '기존 과제', action: '기존 행동', result: '기존 결과' } });
  const http = await installContractFixture(page, { card: initial });
  await page.goto(`/cards/${CARD_ID}?mode=edit`);
  await page.getByLabel(labels[0], { exact: true }).waitFor({ state: 'visible' });
  for (const label of labels) await expect(page.getByLabel(label, { exact: true })).toHaveAttribute('maxlength', '1000');
  const situation = '  의미 있는 공백\n줄바꿈도 유지합니다.  ';
  const action = '가'.repeat(1000);
  await page.getByLabel(labels[0], { exact: true }).fill(situation);
  await page.getByLabel(labels[1], { exact: true }).fill(' \n\t\u001c\u001d\u001e\u001f ');
  await page.getByLabel(labels[2], { exact: true }).fill(action);
  await page.getByLabel(labels[2], { exact: true }).press('End');
  await page.getByLabel(labels[2], { exact: true }).press('x');
  await expect(page.getByLabel(labels[2], { exact: true })).toHaveValue(action);
  await page.getByLabel(labels[3], { exact: true }).fill('');
  await page.getByRole('button', { name: '저장하고 닫기', exact: true }).click();
  await expect(page).toHaveURL(`/cards/${CARD_ID}`);
  expect(http.patches().length).toBeGreaterThan(0);
  expect(http.patches().at(-1)?.body).toEqual({ situation, task: null, action, result: null });
  for (const request of http.patches()) {
    expect(Object.keys(request.body as object).sort()).toEqual(['action', 'result', 'situation', 'task']);
    expect(request.headers['content-type']).toContain('application/json');
    expect(request.headers['x-xsrf-token']).toBe('contract csrf');
  }
  await expect(page.locator('main .star__text').first()).toHaveText(situation);
  expect(http.current().version.action).toHaveLength(1000);
  expect(http.requests.some(request => request.path.startsWith('/cards?'))).toBe(false);
  expect(http.unexpected).toEqual([]);
});

test('real partial timeout card offers writing and blocks unsupported regeneration before HTTP', async ({ page }) => {
  // generation/droppedFields are public response fields; extend the partial-state fixture only.
  const partial = cardFixture({ generation: { jobId: 'partial-contract-job', partial: true },
    version: { ...cardFixture().version, source: 'AI_DRAFT', situation: '부분 생성 상황', action: '부분 생성 행동' },
    droppedFields: [{ field: 'T', reason: 'TIMEOUT' }, { field: 'R', reason: 'TIMEOUT' }],
  });
  const http = await installContractFixture(page, { card: partial });
  await page.goto(`/cards/${CARD_ID}`);
  await page.getByRole('heading', { level: 1 }).waitFor({ state: 'visible' });
  await expect(page.getByText('빈칸은 답변하거나 직접 작성해 주세요.')).toBeVisible();
  await expect(page.getByRole('button', { name: /다시 생성/ })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '결과 답변하기', exact: true })).toBeVisible();
  // 하단 "수정" 대신 빈 칸마다 직접 작성이 있다
  await expect(page.getByRole('button', { name: '결과 직접 작성', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '기본 정보 수정', exact: true })).toHaveCount(0);
  const guarded = await page.evaluate(async id => {
    // Use the browser-loaded endpoint to prove the capability guard also prevents direct callers.
    const source = '/src/api/endpoints.ts';
    const { endpoints } = await import(/* @vite-ignore */ source);
    try { await endpoints.regenerateField(id, 'R'); return 'unexpected-success'; }
    catch (error) { return (error as { code: string }).code; }
  }, CARD_ID);
  expect(guarded).toBe('FEATURE_UNAVAILABLE');
  expect(http.requests.filter(request => request.path.endsWith('/regenerate'))).toEqual([]);
  expect(http.unexpected).toEqual([]);
});

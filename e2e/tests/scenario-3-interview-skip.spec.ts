import { expect, test } from '@playwright/test';
import { SLOW, dataOf, jobEnds, loginAndSetUp, newUser, startAnalysis } from './support';

// 되묻기 질문을 건너뛰면 그 칸은 넘긴 질문으로 남고 다시 묻지 않는다. 카드 칸은 비운 채 그대로다
test('시나리오 3: 되묻기 질문을 건너뛰면 그 칸은 닫힌다', async ({ page, request }) => {
  const login = await newUser(request);

  let cardId: string;
  await test.step('1. 로그인하고 정리하면 추천 후보로 카드 초안이 만들어진다', async () => {
    await loginAndSetUp(page, login);
    const created = page.waitForResponse(
      (r) => /\/api\/repos\/[^/]+\/cards$/.test(r.url()) && r.request().method() === 'POST',
      { timeout: SLOW },
    );
    const jobId = await startAnalysis(page, login);
    await jobEnds(page, jobId, { state: 'SUCCEEDED', errorCode: null });
    cardId = (await dataOf(await created)).cardIds[0];
    // 초안을 다 쓰면 레포 폴더로 간다
    await expect(page).toHaveURL(/\/cards\/repo\//, { timeout: SLOW });
  });

  await test.step('2. 과제 칸 질문을 받는다', async () => {
    await page.goto(`/cards/${cardId}/interview?field=T`);
    await expect(page.getByRole('heading', { name: /^Q1\. \S/ })).toBeVisible({ timeout: SLOW });
  });

  await test.step('3. 건너뛰면 넘긴 질문으로 남는다', async () => {
    await page.getByRole('button', { name: '건너뛰기' }).click();
    await expect(page.getByText('넘긴 질문')).toBeVisible({ timeout: 15_000 });
    // 넘긴 칸은 다시 고를 칸에서 빠진다
    await expect(page.getByRole('button', { name: /^과제 질문 받기/ })).toHaveCount(0);

    // 건너뛰면 화면이 남은 칸에서 다음 질문을 이어서 받는다
    await expect(page.getByRole('heading', { name: /^Q2\. \S/ })).toBeVisible({ timeout: SLOW });
  });

  await test.step('4. 서버도 그 칸을 다시 묻지 않고 카드 칸은 비어 있다', async () => {
    type Turn = { turnNo: number; field: string; outcome: string | null; answer: unknown };
    const turns: Turn[] = await dataOf(await page.request.get(`/api/cards/${cardId}/interview`));
    expect(turns[0]).toMatchObject({ turnNo: 1, field: 'T', outcome: 'SKIPPED', answer: null });
    expect(turns.slice(1).map((t) => t.field)).not.toContain('T');

    // 답을 기다리는 질문이 있으면 서버는 field 와 상관없이 그 질문을 돌려준다(계약) — 과제 칸 질문을 새로 만들지 않는다
    const xsrf = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')?.value ?? '';
    const again: Turn = await dataOf(await page.request.post(`/api/cards/${cardId}/interview`, { data: { field: 'T' }, headers: { 'X-XSRF-TOKEN': xsrf } }));
    expect(again.turnNo).toBe(turns.at(-1)!.turnNo);
    expect(again.field).not.toBe('T');

    const card = await dataOf(await page.request.get(`/api/cards/${cardId}`));
    expect(card.status).toBe('DRAFT');
    expect(card.version.task).toBeNull();
  });
});

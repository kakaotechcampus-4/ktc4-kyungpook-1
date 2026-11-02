import { expect, test } from '@playwright/test';
import { loginAsDemo, showCardTiles } from './helpers';

test('minimal API summaries never imply a manual source or invented evidence count', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/cards?demoScenario=minimal-summary');
  const item = page.locator('.experience-item').first(); await expect(item).toBeVisible();
  await expect(item.locator('.experience-item__meta')).toHaveText('기술 카드');
  await expect(page.locator('.experience-item__footer').filter({ hasText: /근거 커밋 \d+개|내 답변 \d+개/ })).toHaveCount(0);
});

test('cards with all four STAR fields filled drop the text status badge; incomplete cards keep it', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/cards'); await showCardTiles(page);
  const tile = (id: string) => page.locator(`.experience-item[href="/cards/${id}"]`);
  // 네 칸이 다 찬 확정 카드 — 글자 대신 체크 아이콘(이름 "확정됨"). card_05 는 확인 필요 표시가 있어도 확정 카드라 같다
  for (const id of ['card_03', 'card_04', 'card_05']) {
    await expect(tile(id).getByRole('img', { name: '확정됨' })).toBeVisible();
    await expect(tile(id).locator('.badge')).toHaveCount(0);
  }
  // 빈 칸이 있는 작성 중 카드는 그대로 — 작성 중 + 확인 필요 + 빈 칸 수
  await expect(tile('card_01').locator('.badge')).toHaveText(['작성 중', '확인 필요']);
  await expect(tile('card_02').locator('.badge')).toHaveText(['작성 중']);
  await expect(tile('card_02')).toContainText('빈 칸 2개');
  // 탭 이름과 개수는 그대로 — 글자 배지가 사라져도 "확정됨" 탭으로 확정 카드를 모아 볼 수 있다
  await expect(page.getByRole('button', { name: /^전체 8/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /^작성 중 3/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /^보완 필요 3/ })).toBeVisible();
  await page.getByRole('button', { name: /^확정됨 5/ }).click();
  await expect(page.locator('.experience-item')).toHaveCount(5);
});

test('active analysis is visible on home and can be reopened after refresh', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/');
  const jobId = await page.evaluate(async () => {
    const response = await fetch('/api/repos/r_auth/analyze', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() } });
    return (await response.json()).data.jobId as string;
  });
  await page.reload();
  const jobs = page.getByRole('region', { name: '진행 중인 작업' }); await expect(jobs).toBeVisible();
  await jobs.getByRole('link', { name: '진행 상황 보기', exact: true }).click();
  await expect(page).toHaveURL(new RegExp('/jobs/' + jobId + '$'));
  await expect(page.getByRole('heading', { name: '작업 진행', exact: true })).toBeVisible();
});

test('review and missing-field filters use the current server STAR states', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/cards'); await showCardTiles(page);
  // 보완 상태 선택 칸 대신 "보완 필요" 탭 하나 — 빈칸 있음, 확인 필요인 작성 중 카드를 함께 보여 준다
  await page.getByRole('button', { name: /^보완 필요/ }).click();
  await expect(page.getByRole('heading', { name: '로그인 세션 처리', exact: true })).toBeVisible(); // 확인 필요
  await expect(page.getByRole('heading', { name: '결제 롤백 대응 경험', exact: true })).toBeVisible(); // 빈칸 있음
  await expect(page.getByRole('heading', { name: '스터디 일정이 밀렸을 때 범위를 줄인 경험', exact: true })).toBeVisible(); // 활동 안의 카드도 같은 규칙
  await expect(page.locator('.experience-item')).toHaveCount(3);
  await expect(page.getByRole('heading', { name: '토큰 만료 자동 재발급', exact: true })).toHaveCount(0); // 확정 카드는 빠진다
});

test('copy preview offers plain STAR and evidence formats without modifying saved text', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/cards/card_03');
  await page.getByRole('button', { name: '복사', exact: true }).click();
  const preview = page.getByRole('textbox', { name: '복사할 내용', exact: true });
  const plain = await preview.inputValue(); expect(plain).not.toContain('##'); expect(plain).not.toContain('CONFIRMED');
  await page.getByRole('radio', { name: '상황, 과제, 행동, 결과 형식', exact: true }).click();
  await expect(preview).toHaveValue(/상황\n/);
  await page.getByRole('radio', { name: '근거 포함', exact: true }).click();
  await expect(preview).toHaveValue(/https:\/\/github.com/);
  const saved = await page.evaluate(async () => (await (await fetch('/api/cards/card_03')).json()).data);
  expect(plain).toContain(saved.version.situation);
  expect(saved.version.versionNo).toBe(3);
});

test('manual drafts can change title and period after the first save and clear the period explicitly', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/cards/new');
  await page.getByRole('textbox', { name: '카드 제목', exact: true }).fill('메타 수정 경험');
  const period = page.getByRole('textbox', { name: '기간', exact: true }); await period.fill('2024.04');
  await page.getByRole('button', { name: '작성 시작', exact: true }).click();
  await expect(page.getByRole('textbox', { name: '카드 제목', exact: true })).toBeEnabled();
  await page.getByRole('textbox', { name: '카드 제목', exact: true }).fill('수정한 경험'); await period.fill('2025.02');
  await expect(page.getByRole('status').filter({ hasText: '저장됨' })).toBeVisible({ timeout: 15000 });
  await page.getByRole('button', { name: '저장 후 종료', exact: true }).click();
  await page.getByRole('button', { name: '기본 정보 수정', exact: true }).click();
  const dialog = page.getByRole('dialog'); await expect(dialog.getByRole('textbox', { name: '기간', exact: true })).toHaveValue('2025.02');
  await dialog.getByRole('textbox', { name: '기간', exact: true }).fill('');
  await dialog.getByRole('button', { name: '저장', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await page.reload(); await page.getByRole('button', { name: '기본 정보 수정', exact: true }).click();
  await expect(page.getByRole('dialog').getByRole('textbox', { name: '기간', exact: true })).toHaveValue('');
});

import { test, expect } from '@playwright/test';
import { freshSeed, loginAsDemo as login } from './helpers';

/** 방어선·되돌리기 경로. 매 페이지 로드가 시드에서 시작하므로 테스트끼리 간섭하지 않는다. */

test('E-1 수집 실패 — 정리 시작 자체를 막고 다음 행동 3개를 준다', async ({ page }) => {
  await login(page);
  await page.goto('/repos?select=r_fail&disclose=1');
  await page.getByRole('button', { name: '정리 시작' }).click();
  await expect(page.getByRole('heading', { name: 'GitHub에서 답이 오지 않았어요' })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText('읽은 데까지로는 후보를 만들지 않아요')).toBeVisible();
  await expect(page.getByRole('button', { name: '다시 시도' }).first()).toBeEnabled(); // retryable: true
});

test('E-2 요청 한도 — partial 을 명시하고 읽은 범위의 후보를 보여준다', async ({ page }) => {
  await login(page);
  await page.goto('/repos?select=r_ratelimit&disclose=1');
  await page.getByRole('button', { name: '정리 시작' }).click();
  await expect(page.getByRole('heading', { name: '읽은 데까지 정리했어요' })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole('button', { name: /뒤 이어 읽기/ })).toBeDisabled(); // RATE_LIMITED — 한도가 풀릴 때까지 막는다
  await page.getByRole('button', { name: '읽은 범위의 후보 보기' }).click();
  await expect(page.getByRole('heading', { name: /후보 \d+개 \(전체 아님\)/ })).toBeVisible();
  await expect(page.getByText('"이게 전부"가 아닙니다')).toBeVisible();
});

test('후보 제외는 실행 취소할 수 있다', async ({ page }) => {
  await login(page);
  await page.goto('/repos/r_auth/candidates');
  const first = page.locator('.cand').first();
  const title = await first.locator('.cand__title').textContent();
  await first.getByRole('button', { name: '제외' }).click();
  await expect(page.locator('.toast')).toContainText('제외했습니다');
  await expect(page.locator('.cand').first().locator('.cand__title')).not.toHaveText(title!);
  await page.getByRole('button', { name: '실행 취소' }).click();
  await expect(page.locator('.cand__title', { hasText: title! })).toBeVisible();
});

test('되묻기 — 보기에서 고른 답도 그대로 저장되고 칸이 채워진다', async ({ page }) => {
  await login(page);
  await page.goto('/cards/card_01/interview?field=T');
  await expect(page.getByText('코드에서 찾은 것')).toBeVisible();
  await page.locator('.chip--option').first().click();
  await page.getByRole('button', { name: '다음으로' }).click();
  await expect(page.locator('.toast')).toContainText('다듬지 않고 그대로');
  // 내부 enum 이 화면에 새지 않는다
  await expect(page.getByText(/보기에서 고른 것 · 고치지 않고 그대로 넣었어요/)).toBeVisible();
  await expect(page.getByText('USER_SELECTED')).toHaveCount(0);
  await page.getByRole('button', { name: '카드로 돌아가기' }).click();
  await expect(page.locator('#star-T').locator('..').locator('..')).not.toContainText('근거를 찾지 못해');
});

test('직접 수정 — 쓰는 동안 서버에 저장되고 AI 초안은 히스토리에 남는다', async ({ page }) => {
  await login(page);
  await page.goto('/cards/card_01?mode=edit');
  const a = page.getByLabel('행동 (Action)');
  const typed = `인증 모듈의 세션 처리를 담당했다. 리프레시 토큰을 쿠키에 두고 만료 시 자동 갱신되게 했다. (${Date.now()})`;
  await a.fill(typed);
  await expect(page.getByText('저장됨')).toBeVisible({ timeout: 15_000 }); // 입력이 멈춘 뒤 한 번만 나간다
  // 브라우저가 아니라 서버에 남았다는 증거 — 편집 화면을 떠났다 돌아와도 그대로다
  await page.goto('/cards/card_01');
  await expect(page.getByText(typed)).toBeVisible();
  await page.goto('/cards/card_01?mode=edit');
  await page.getByRole('button', { name: '저장하고 닫기' }).click();
  await page.getByRole('button', { name: '버전 히스토리' }).first().click();
  await expect(page.getByText('AI_DRAFT').first()).toBeVisible();
  await expect(page.getByText('USER_EDIT').first()).toBeVisible();
});

test('새로고침으로 job 이 빠져도 서버의 진행 중 작업으로 되돌아온다', async ({ page }) => {
  await login(page);
  await page.goto('/repos?select=r_auth&disclose=1');
  await page.getByRole('button', { name: '정리 시작' }).click();
  await expect(page).toHaveURL(/\/run\?job=/);
  // 주소에서 job 을 떼고 다시 들어가도(= 새로고침으로 잃어도) 진행 화면으로 붙는다
  await page.goto('/repos/r_auth/run');
  await expect(page).toHaveURL(/\/run\?job=/, { timeout: 15_000 });
  await expect(page.getByRole('heading', { name: '읽고 있습니다' })).toBeVisible();
});

test('모바일 되묻기 — 질문과 입력칸이 카드 미리보기보다 먼저 보인다', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page);
  await page.goto('/cards/card_01/interview?field=T');
  const panel = page.locator('.iv__panel');
  const canvas = page.locator('.iv__canvas');
  await expect(page.getByRole('heading', { level: 2 })).toBeVisible();
  const panelTop = (await panel.boundingBox())!.y;
  const canvasTop = (await canvas.boundingBox())!.y;
  expect(panelTop).toBeLessThan(canvasTop);
  // 미리보기는 접혀 있고, 눌러야 펴진다
  await expect(canvas.locator('.star-read')).toBeHidden();
  await page.getByRole('button', { name: '지금까지 쓴 카드 보기' }).click();
  await expect(canvas.locator('.star-read')).toBeVisible();
});

test('마스킹 — 원문은 그대로, 표시만 바뀐다', async ({ page }) => {
  await login(page);
  await page.goto('/cards/card_03?mode=edit'); // 확정 카드는 먼저 다시 열어야 한다 → 여기선 DRAFT 카드 사용
  await page.goto('/cards/card_02?mode=mask');
  await page.getByLabel('원문').first().fill('결제');
  await page.getByLabel('치환').first().fill('[결제모듈]');
  await expect(page.locator('.cmp__box--after').first()).toContainText('[결제모듈]');
  await expect(page.locator('.cmp__box').first()).toContainText('결제 기능');
  await page.getByRole('button', { name: '마스킹 적용' }).click();
  await expect(page.locator('.toast')).toContainText('마스킹 규칙');
});

test('직접 작성 — S·A 만 있어도 확정할 수 있고 근거는 USER_STATED', async ({ page }) => {
  await login(page);
  await page.goto('/cards/new');
  await page.getByPlaceholder('예) 팀원과 토큰 저장 위치로 갈린 경험').fill('E2E 직접 작성 카드');
  await page.getByLabel('상황 (Situation)').fill('3인 팀에서 배포 담당이 없었다');
  await page.getByLabel('행동 (Action)').fill('GitHub Actions 로 배포 파이프라인을 직접 구성했다');
  await page.getByRole('button', { name: '확정으로' }).click();
  await page.getByRole('checkbox', { name: '확인' }).click();
  await page.getByRole('button', { name: '확정하기' }).click();
  await expect(page.getByText('확정됨').first()).toBeVisible();
  await expect(page.getByText('내가 말한 것').first()).toBeVisible();
});

/**
 * 동의 취소 — 실서버에서 Spring 이 /login?error=access_denied 로 돌려보내는 지점.
 * 리다이렉트 자체는 백엔드 몫이라 여기서는 그 결과 화면만 검증한다.
 */
test('동의 취소는 랜딩으로 돌아와 안내를 보여준다', async ({ page }) => {
  await freshSeed(page);
  await page.goto('/login?error=access_denied');
  await expect(page.getByText('GitHub에서 동의를 취소하셨네요')).toBeVisible();
  await expect(page.getByRole('button', { name: '샘플로 체험하기' })).toHaveCount(2);
});

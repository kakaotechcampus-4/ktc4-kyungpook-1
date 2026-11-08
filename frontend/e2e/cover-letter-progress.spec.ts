import { expect, test, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

/**
 * 자소서를 쓰는 동안 다른 화면에서도 위쪽 진행 표시에 보이고, 누르면 그 자소서로 돌아간다.
 * 목 서버는 경과 시간으로 단계를 넘긴다 — 카드 고르기 6초, 질문 4초, 초안 8초.
 */
const SHOTS = 'docs/screenshots/2026-11-07-cover-letter-progress';

const call = (page: Page, method: string, path: string, body?: unknown) =>
  page.evaluate(async ([m, p, b]) => {
    const res = await fetch(`/api${p}`, { method: m, headers: { 'Content-Type': 'application/json', 'Idempotency-Key': 'e2e-progress' }, body: b ? JSON.stringify(b) : undefined });
    return (await res.json()).data as { id: string; stage: string };
  }, [method, path, body] as const);

async function startDrafting(page: Page) {
  // 공고를 골라 만든 자소서라야 진행 표시에 회사 마크와 회사 이름이 보인다
  const postings = await page.evaluate(async () => (await (await fetch('/api/matches')).json()).data as { id: string }[]);
  const made = await call(page, 'POST', '/cover-letters', { jobRole: '백엔드 개발', memo: null, strengths: [], questions: [{ text: '지원 동기를 적어 주세요', charLimit: null }], postingId: postings[0]?.id ?? null });
  await expect.poll(async () => (await call(page, 'GET', `/cover-letters/${made.id}`)).stage, { timeout: 15_000 }).toBe('ASSIGNED');
  await call(page, 'POST', `/cover-letters/${made.id}/asks`);
  await expect.poll(async () => (await call(page, 'GET', `/cover-letters/${made.id}`)).stage, { timeout: 15_000 }).toBe('ASKED');
  await call(page, 'POST', `/cover-letters/${made.id}/drafts`);
  return made.id;
}

for (const [name, width, height] of [['pc-1440', 1440, 900], ['phone-390', 390, 844]] as const) {
  test(`초안을 쓰는 동안 다른 화면의 진행 표시에 보이고 누르면 그 자소서로 돌아간다 (${width}px)`, async ({ page }) => {
    await page.setViewportSize({ width, height });
    await loginAsDemo(page);
    await page.goto('/cards');
    const id = await startDrafting(page);
    await page.goto('/cards');

    const pill = page.getByRole('link', { name: / 자소서 쓰는 중$/ });
    await expect(pill).toBeVisible();
    await expect(pill).toHaveAttribute('href', `/cover-letters/${id}`);
    await expect(page.getByRole('heading', { level: 1, name: /경험 카드/ })).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/${name}.png` });

    await pill.click();
    await expect(page).toHaveURL(new RegExp(`/cover-letters/${id}$`));
  });
}

import { expect, test, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

/**
 * 이슈 #772 — 확정 카드 읽기는 왼쪽 라벨 열 + 오른쪽 본문의 행이다.
 * 단위 시험(jsdom)은 CSS 를 읽지 않아, 실제 배치와 바탕색은 여기서 본다. 목에서는 card_03(확정)을 쓴다.
 */
const rowsOf = (page: Page) => page.locator('.star-read--rows .star-read__row');
const openCard = async (page: Page) => { await loginAsDemo(page); await page.goto('/cards/card_03'); await expect(rowsOf(page)).toHaveCount(4); };

// 확정 카드의 칸을 비운 채로 다시 확정한다 — 앱이 쓰는 목 API 그대로(S·A 만 있으면 확정할 수 있다)
async function blankAndReconfirm(page: Page, fields: ('task' | 'result')[]) {
  await page.evaluate(async (blank) => {
    const call = async (method: string, path: string, body?: unknown) => (await fetch(`/api${path}`, { method, headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined })).json();
    await call('POST', '/cards/card_03/reopen');
    const { version } = (await call('GET', '/cards/card_03')).data;
    const next: Record<string, unknown> = { situation: version.situation, task: version.task, action: version.action, result: version.result };
    blank.forEach((field) => { next[field] = null; });
    await call('PATCH', '/cards/card_03/draft', next);
    await call('POST', '/cards/card_03/confirm');
  }, fields);
  await page.reload();
  await expect(rowsOf(page)).toHaveCount(4);
}

test('데스크톱: 라벨은 본문 왼쪽에서 같은 줄에 시작하고, 근거는 문장 아래 글자 링크이며, 결과 행에만 바탕이 깔린다', async ({ page }) => {
  await openCard(page);
  const rows = await rowsOf(page).evaluateAll((els) => els.map((row) => {
    const at = (selector: string) => row.querySelector(selector)!.getBoundingClientRect();
    const link = row.querySelector('.evidence-open')!;
    const linkBox = link.getBoundingClientRect(), linkStyle = getComputedStyle(link);
    return {
      headRight: at('.star-row__heading').right, headTop: at('.star-row__heading').top, bodyLeft: at('.star-row__body').left, bodyRight: at('.star-row__body').right, bodyTop: at('.star-row__body').top,
      pencilLeft: at('.star__pencil').left, textBottom: at('.star__text').bottom, linkTop: linkBox.top, linkHeight: linkBox.height,
      linkBackground: linkStyle.backgroundColor, linkBorder: linkStyle.borderTopWidth, background: getComputedStyle(row).backgroundColor,
    };
  }));
  for (const r of rows) {
    expect(r.headRight).toBeLessThanOrEqual(r.bodyLeft);
    expect(Math.abs(r.headTop - r.bodyTop)).toBeLessThanOrEqual(1);
    expect(r.pencilLeft).toBeGreaterThanOrEqual(r.bodyRight); // 연필이 본문 글과 겹치지 않는다
    expect(r.linkTop).toBeGreaterThanOrEqual(r.textBottom - 4);
    expect(r.linkHeight).toBeGreaterThanOrEqual(44);
    expect([r.linkBackground, r.linkBorder]).toEqual(['rgba(0, 0, 0, 0)', '0px']);
  }
  expect(new Set(rows.slice(0, 3).map((r) => r.background)).size).toBe(1);
  expect(rows[3].background).not.toBe(rows[0].background);
});

test('폰 폭에서는 라벨이 본문 위로 접히고 가로 스크롤이 생기지 않는다', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openCard(page);
  const rows = await rowsOf(page).evaluateAll((els) => els.map((row) => {
    const at = (selector: string) => row.querySelector(selector)!.getBoundingClientRect();
    return { headBottom: at('.star-row__heading').bottom, bodyTop: at('.star-row__body').top, pencilBottom: at('.star__pencil').bottom, bodyLeft: at('.star-row__body').left, headLeft: at('.star-row__heading').left };
  }));
  for (const r of rows) {
    expect(r.headBottom).toBeLessThanOrEqual(r.bodyTop);
    expect(r.pencilBottom).toBeLessThanOrEqual(r.bodyTop + 1); // 연필이 본문 첫 줄을 가리지 않는다
    expect(r.bodyLeft).toBe(r.headLeft);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});

test('비운 결과 칸은 결과 바탕이 아니라 비운 칸 바탕이다', async ({ page }) => {
  await openCard(page);
  await blankAndReconfirm(page, ['task', 'result']);
  const backgrounds = await rowsOf(page).evaluateAll((els) => els.map((row) => getComputedStyle(row).backgroundColor));
  expect(backgrounds[3]).toBe(backgrounds[1]); // 비운 과제 칸과 같다
  expect(backgrounds[3]).not.toBe(backgrounds[0]);
  await expect(rowsOf(page).nth(3)).toContainText('비운 채로 확정했어요');
});

test('인쇄에서도 2열이고 근거 버튼은 숨고 근거 목록이 보인다', async ({ page }) => {
  await page.setViewportSize({ width: 600, height: 900 }); // 인쇄 폭은 700px 안팎이라 폰 폭에서 접히지 않는지 본다
  await openCard(page);
  await page.emulateMedia({ media: 'print' });
  const row = rowsOf(page).first();
  const columns = await row.evaluate((el) => getComputedStyle(el).gridTemplateColumns.split(' ').length);
  expect(columns).toBe(2);
  await expect(row.locator('.evidence-open')).toBeHidden();
  await expect(row.locator('.evidence-print')).toBeVisible();
});

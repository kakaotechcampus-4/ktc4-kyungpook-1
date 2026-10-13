import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

for (const width of [320, 390, 1440]) {
  for (const theme of ['light', 'dark']) {
    test(`${width}px ${theme} commit picker keeps SHA, message and existing-candidate badge readable`, async ({ page }, testInfo) => {
      await page.setViewportSize({ width, height: 900 });
      await loginAsDemo(page);
      await page.addInitScript((value) => localStorage.setItem('gitory.theme', value), theme);
      await page.goto('/repos/r_auth/candidates?add=1');
      const dialog = page.getByRole('dialog');
      await dialog.getByPlaceholder('커밋 메시지나 sha 로 검색').fill('결제');
      const rows = dialog.locator('button.commit-row');
      await expect(rows).toHaveCount(4);
      for (const row of await rows.all()) {
        const geometry = await row.evaluate((element) => {
          const box = element.getBoundingClientRect();
          return {
            width: box.width, height: box.height, scrollWidth: element.scrollWidth, clientWidth: element.clientWidth,
            scrollHeight: element.scrollHeight, clientHeight: element.clientHeight,
            children: Array.from(element.querySelectorAll('.commit-row__sha, .commit-row__msg, .badge')).map((child) => {
              const rect = child.getBoundingClientRect();
              return { left: rect.left, right: rect.right, bottom: rect.bottom, rowBottom: box.bottom, rowLeft: box.left, rowRight: box.right, scrollWidth: child.scrollWidth, clientWidth: child.clientWidth };
            }),
          };
        });
        expect(geometry.width).toBeGreaterThanOrEqual(44);
        expect(geometry.height).toBeGreaterThanOrEqual(44);
        expect(geometry.scrollWidth).toBeLessThanOrEqual(geometry.clientWidth);
        expect(geometry.scrollHeight).toBeLessThanOrEqual(geometry.clientHeight);
        for (const child of geometry.children) {
          expect(child.left).toBeGreaterThanOrEqual(child.rowLeft);
          expect(child.right).toBeLessThanOrEqual(child.rowRight);
          expect(child.bottom).toBeLessThanOrEqual(child.rowBottom);
          expect(child.scrollWidth).toBeLessThanOrEqual(child.clientWidth);
        }
        await expect(row.locator('.badge')).toHaveText('다른 후보에 있음');
        await expect(row.locator('.commit-row__sha')).toHaveText(/^[a-f0-9]{7}$/);
        const messageWidth = await row.locator('.commit-row__msg').evaluate((element) => element.getBoundingClientRect().width);
        expect(messageWidth).toBeGreaterThanOrEqual(width === 320 ? 180 : 240);
      }
      expect(await dialog.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
      if (width === 320) await page.screenshot({ path: testInfo.outputPath(`picker-320-${theme}.png`) });
    });
  }
}

test('commit picker Space and child clicks preserve exact selected SHAs through search and submission', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 900 });
  await loginAsDemo(page);
  await page.goto('/repos/r_auth/candidates?add=1');
  const dialog = page.getByRole('dialog');
  const search = dialog.getByPlaceholder('커밋 메시지나 sha 로 검색');
  await search.fill('결제');
  const commits = await page.evaluate(async () => (await (await fetch('/api/repos/r_auth/commits?q=결제')).json()).data as { sha: string; message: string }[]);
  const first = dialog.locator('button.commit-row').filter({ hasText: commits[0].sha.slice(0, 7) });
  const second = dialog.locator('button.commit-row').filter({ hasText: commits[1].sha.slice(0, 7) });
  const count = dialog.locator('.modal__foot-text');
  await expect(first).toHaveAttribute('aria-pressed', 'false');
  await first.focus();
  await page.keyboard.press('Space');
  await expect(first).toHaveAttribute('aria-pressed', 'true');
  await expect(count).toContainText('커밋 1건이 붙어 있습니다');
  await second.locator('.badge').click();
  await expect(second).toHaveAttribute('aria-pressed', 'true');
  await expect(count).toContainText('커밋 2건이 붙어 있습니다');
  await first.locator('.commit-row__msg').click();
  await expect(first).toHaveAttribute('aria-pressed', 'false');
  await expect(count).toContainText('커밋 1건이 붙어 있습니다');
  await search.fill(commits[0].sha.slice(0, 7));
  await expect(dialog.locator('button.commit-row')).toHaveCount(1);
  await first.focus();
  await page.keyboard.press('Space');
  await expect(first).toHaveAttribute('aria-pressed', 'true');
  await expect(count).toContainText('커밋 2건이 붙어 있습니다');
  const firstChip = dialog.getByRole('button', { name: `${commits[0].sha.slice(0, 7)} ${commits[0].message.slice(0, 18)} ✕`, exact: true });
  await firstChip.click();
  await expect(first).toHaveAttribute('aria-pressed', 'false');
  await expect(count).toContainText('커밋 1건이 붙어 있습니다');
  await first.click();
  await expect(count).toContainText('커밋 2건이 붙어 있습니다');
  await dialog.getByRole('textbox', { name: '후보 이름' }).fill('커밋 선택 검증');
  await dialog.getByRole('textbox', { name: '무엇을 한 작업인가요' }).fill('검색과 키보드로 선택한 커밋을 첨부한다');
  // Demo fetch is intercepted in the app; hold only this real submission to inspect its payload and pending UI.
  await page.evaluate(() => {
    const currentFetch = window.fetch;
    const state = window as typeof window & { pickerPayloads: unknown[]; releasePickerSubmission: () => void };
    state.pickerPayloads = [];
    window.fetch = async (input, init) => {
      if (String(input).endsWith('/repos/r_auth/candidates') && init?.method === 'POST') {
        state.pickerPayloads.push(JSON.parse(String(init.body)));
        await new Promise<void>((resolve) => { state.releasePickerSubmission = resolve; });
      }
      return currentFetch(input, init);
    };
  });
  await dialog.getByRole('button', { name: '후보 추가', exact: true }).click();
  await expect(first).toBeDisabled();
  await expect(firstChip).toBeDisabled();
  await expect(search).toBeDisabled();
  await expect(dialog.getByRole('button', { name: '취소', exact: true })).toBeDisabled();
  expect(await page.evaluate(() => (window as typeof window & { pickerPayloads: unknown[] }).pickerPayloads)).toEqual([
    { title: '커밋 선택 검증', summary: '검색과 키보드로 선택한 커밋을 첨부한다', shas: [commits[1].sha, commits[0].sha] },
  ]);
  await page.evaluate(() => (window as typeof window & { releasePickerSubmission: () => void }).releasePickerSubmission());
  await expect(dialog).toHaveCount(0);
  const saved = page.locator('.cand').filter({ has: page.getByRole('heading', { name: '커밋 선택 검증', exact: true }) });
  await expect(saved.locator('.cand__meta')).toContainText('커밋 2개');
  await expect(page.locator('.sticky-footer__text')).toContainText('0개 선택');
});

test('320px picker wraps long messages for commits that have no existing candidate', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 900 });
  await loginAsDemo(page);
  await page.goto('/repos/r_auth/candidates?add=1');
  const message = '설정 파일 정리와 연속된긴문자열'.repeat(8);
  await page.evaluate((longMessage) => {
    const currentFetch = window.fetch;
    window.fetch = async (input, init) => {
      const response = await currentFetch(input, init);
      if (String(input).includes('/repos/r_auth/commits?')) {
        const body = await response.json();
        body.data[0].message = longMessage;
        return new Response(JSON.stringify(body), { status: response.status, headers: response.headers });
      }
      return response;
    };
  }, message);
  const dialog = page.getByRole('dialog');
  await dialog.getByPlaceholder('커밋 메시지나 sha 로 검색').fill('설정');
  const first = dialog.locator('button.commit-row').first();
  await expect(first.locator('.commit-row__msg')).toHaveText(message);
  await expect(first.locator('.badge')).toHaveCount(0);
  expect(await first.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
  const textBox = await first.locator('.commit-row__msg').boundingBox();
  expect(textBox!.width).toBeGreaterThanOrEqual(180);
  expect(textBox!.height).toBeGreaterThan(40);
  await first.focus();
  await page.keyboard.press('Space');
  await expect(first).toHaveAttribute('aria-pressed', 'true');
  await expect(dialog.locator('.modal__foot-text')).toContainText('커밋 1건이 붙어 있습니다');
});

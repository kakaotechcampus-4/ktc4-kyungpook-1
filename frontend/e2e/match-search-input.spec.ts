import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => sessionStorage.setItem('gitory.demo.session', '1'));
});

const urlQuery = (url: string) => new URL(url).searchParams.get('q');

for (const text of ['abcdef', 'backend developer', '프론트엔드 개발자']) {
  test(`매칭 검색창에 빠르게 쳐도 글자가 그대로고 URL 의 q 도 같아요 (${text})`, async ({ page }) => {
    await page.goto('/match');
    await expect(page.locator('.match-card').first()).toBeVisible();
    const search = page.getByPlaceholder('기업·직무 검색');
    await search.pressSequentially(text, { delay: 0 });
    await expect(search).toHaveValue(text);
    await expect.poll(() => urlQuery(page.url())).toBe(text);
    // 입력이 끝난 뒤 라우터 전환이 늦게 끝나도 입력값이 되돌아가지 않아야 해요.
    await page.waitForTimeout(300);
    await expect(search).toHaveValue(text);
    expect(urlQuery(page.url())).toBe(text);
  });
}

test('검색어를 친 뒤 필터를 바꿔도 검색어가 입력과 URL 에 남아요', async ({ page }) => {
  await page.goto('/match');
  await expect(page.locator('.match-card').first()).toBeVisible();
  const search = page.getByPlaceholder('기업·직무 검색');
  await search.pressSequentially('abc', { delay: 0 });
  await page.getByRole('combobox', { name: '공고 정렬', exact: true }).click();
  await page.getByRole('option', { name: '마감 순', exact: true }).click();
  await expect.poll(() => new URL(page.url()).searchParams.get('sort')).toBe('deadline');
  await expect(search).toHaveValue('abc');
  expect(urlQuery(page.url())).toBe('abc');
});

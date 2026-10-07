import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

test('Escape during IME composition preserves the search query', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/cards');
  const search = page.getByRole('textbox', { name: '카드 이름으로 검색', exact: true });
  await search.fill('로그인');

  const dispatched = await search.evaluate(element => element.dispatchEvent(new KeyboardEvent('keydown', {
    key: 'Escape', bubbles: true, cancelable: true, isComposing: true,
  })));

  expect(dispatched).toBe(true);
  await expect(search).toHaveValue('로그인');
});

test('Escape with the legacy IME keyCode preserves the search query', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/cards');
  const search = page.getByRole('textbox', { name: '카드 이름으로 검색', exact: true });
  await search.fill('로그인');

  const dispatched = await search.evaluate(element => element.dispatchEvent(new KeyboardEvent('keydown', {
    key: 'Escape', bubbles: true, cancelable: true, isComposing: false, keyCode: 229,
  })));

  expect(dispatched).toBe(true);
  await expect(search).toHaveValue('로그인');
});

test('ordinary Escape clears the search query and keeps focus in the search field', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/cards');
  const search = page.getByRole('textbox', { name: '카드 이름으로 검색', exact: true });
  await search.fill('로그인');
  await search.press('Escape');

  await expect(search).toHaveValue('');
  await expect(search).toBeFocused();
});

import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

for (const legacy of [false, true]) {
  test(`repository Enter during ${legacy ? 'legacy keyCode 229' : 'IME composition'} keeps the query and stays on home`, async ({ page }) => {
    await loginAsDemo(page); await page.goto('/');
    const search = page.getByLabel('레포 검색', { exact: true });
    await search.fill('auth-service');
    await expect(page.getByRole('option').first()).toBeVisible();
    await search.evaluate((element, legacy) => element.dispatchEvent(new KeyboardEvent('keydown', {
      key: 'Enter', bubbles: true, cancelable: true, isComposing: !legacy, keyCode: legacy ? 229 : 13,
    })), legacy);
    await page.waitForTimeout(150);
    await expect(page).toHaveURL(/\/$/);
    await expect(search).toHaveValue('auth-service');
  });
}

test('repository navigation keys during composition preserve the active suggestion', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/');
  const search = page.getByLabel('레포 검색', { exact: true });
  await search.fill('a');
  const first = page.getByRole('option').first();
  await expect(first).toHaveAttribute('aria-selected', 'true');
  const dispatched = await search.evaluate(element => element.dispatchEvent(new KeyboardEvent('keydown', {
    key: 'ArrowDown', bubbles: true, cancelable: true, isComposing: true,
  })));
  expect(dispatched).toBe(true);
  await expect(first).toHaveAttribute('aria-selected', 'true');
});

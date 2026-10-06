import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

test('repository menus support keyboard selection and outside dismissal without changing the selection', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/repos');
  const sort = page.getByRole('combobox', { name: '정렬', exact: true });
  await sort.click();
  await expect(page.getByRole('listbox', { name: '정렬', exact: true })).toBeVisible();
  await page.keyboard.press('End');
  await expect(page.getByRole('option', { name: '이름 순', exact: true })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(sort).toContainText('이름 순');
  await expect(page.getByRole('listbox', { name: '정렬', exact: true })).toHaveCount(0);
  await expect(sort).toBeFocused();
  await sort.click();
  await expect(page.getByRole('listbox', { name: '정렬', exact: true })).toBeVisible();
  await expect(page.getByRole('option', { name: '이름 순', exact: true })).toBeFocused();
  // Reach the next animation frame before exercising an outside pointer action.
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => resolve())));
  await page.mouse.click(10, 10);
  await expect(page.getByRole('listbox', { name: '정렬', exact: true })).toHaveCount(0);
  await expect(sort).toContainText('이름 순');
  await expect(sort).toBeFocused();
  const filter = page.getByRole('combobox', { name: '필터', exact: true });
  await filter.click(); await page.getByRole('option', { name: 'PR 0건', exact: true }).click();
  await expect(filter).toContainText('PR 0건');
  await expect(page.getByRole('radio')).toHaveCount(2);
  await expect(page.getByRole('radio', { name: /algorithm-study/ })).toBeVisible();
  await expect(page.getByRole('radio', { name: /auth-service/ })).toHaveCount(0);
});

test('repository typeahead reaches the draft unchanged and locks after the first save', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/cards/new');
  const repo = page.getByRole('combobox', { name: '관련 레포', exact: true });
  await repo.click(); await page.keyboard.type('Tae');
  await expect(page.getByRole('option', { name: 'TaeHuiKKIM/free-tier-sleep', exact: true })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(repo).toContainText('TaeHuiKKIM/free-tier-sleep');
  await page.getByRole('textbox', { name: '카드 제목', exact: true }).fill('레포 선택 저장 검사');
  await page.getByRole('button', { name: '임시 저장 시작', exact: true }).click();
  await expect(repo).toBeDisabled();
  await repo.dispatchEvent('click');
  await expect(page.getByRole('listbox', { name: '관련 레포', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '저장 후 종료', exact: true }).click();
  await expect(page).toHaveURL(/\/cards\/[^/?]+$/);
  const saved = await page.evaluate(async () => (await (await fetch('/api' + location.pathname)).json()).data);
  expect(saved.repo.id).toBe('r_fts');
});

test('long repository menus scroll to the keyboard destination within the mobile viewport', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 600 });
  await loginAsDemo(page); await page.goto('/cards/new');
  await expect(page.getByRole('combobox', { name: '관련 레포', exact: true })).toContainText('없음');
  await page.evaluate(() => {
    const db = JSON.parse(sessionStorage.getItem('gitory.demo.db')!);
    db.repos = Array.from({ length: 40 }, (_, index) => ({ ...db.repos[0], id: `repo_${index}`, name: `repository-${index}` }));
    sessionStorage.setItem('gitory.demo.db', JSON.stringify(db));
  });
  await page.reload();
  const repo = page.getByRole('combobox', { name: '관련 레포', exact: true });
  await repo.click();
  const menu = page.getByRole('listbox', { name: '관련 레포', exact: true });
  await expect(menu).toBeVisible();
  await page.keyboard.press('End');
  const last = page.getByRole('option', { name: 'hong-dev/repository-39', exact: true });
  await expect(last).toBeFocused();
  const bounds = await menu.boundingBox(), destination = await last.boundingBox();
  expect(bounds!.height).toBeLessThanOrEqual(320);
  expect(bounds!.y).toBeGreaterThanOrEqual(0); expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(600);
  expect(destination!.y).toBeGreaterThanOrEqual(bounds!.y);
  expect(destination!.y + destination!.height).toBeLessThanOrEqual(bounds!.y + bounds!.height);
  await page.keyboard.press('Enter'); await expect(repo).toContainText('hong-dev/repository-39');
});

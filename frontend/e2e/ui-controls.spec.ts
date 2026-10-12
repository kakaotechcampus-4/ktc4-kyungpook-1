import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

test('search has one focus boundary, Escape clears and returns typing focus', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/');
  const search = page.getByRole('textbox', { name: '카드 이름으로 검색', exact: true });
  await search.fill('로그인');
  const focus = await search.evaluate(element => ({ outline: getComputedStyle(element).outlineStyle, shadow: getComputedStyle(element).boxShadow }));
  expect(focus.outline).toBe('none'); expect(focus.shadow).toBe('none');
  await search.press('Escape'); await expect(search).toHaveValue(''); await expect(search).toBeFocused();
  await search.fill('로그인'); await page.getByRole('button', { name: '검색 지우기', exact: true }).click();
  await expect(search).toHaveValue(''); await expect(search).toBeFocused();
});

test('select menus are styled, support keyboard selection and restore focus on Escape', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/cards');
  const sort = page.getByRole('combobox', { name: '정렬', exact: true });
  await sort.click(); const menu = page.getByRole('listbox', { name: '정렬', exact: true }); await expect(menu).toBeVisible();
  await page.keyboard.press('End'); await expect(page.getByRole('option', { name: '이름순', exact: true })).toBeFocused();
  await page.keyboard.press('Enter'); await expect(sort).toContainText('이름순');
  await sort.click(); await page.keyboard.press('Escape'); await expect(menu).toHaveCount(0); await expect(sort).toBeFocused();
  const create = page.getByRole('button', { name: '레포에서 만들기', exact: true });
  const style = async (element: typeof sort) => element.evaluate(node => ({ radius: getComputedStyle(node).borderRadius, height: node.getBoundingClientRect().height }));
  const triggerStyle = await style(sort), buttonStyle = await style(create);
  expect(triggerStyle.radius).toBe(buttonStyle.radius); expect(triggerStyle.height).toBeGreaterThanOrEqual(44);
  expect(Math.abs(triggerStyle.height - buttonStyle.height)).toBeLessThanOrEqual(1);
});

test('desktop resume and new-experience input share their left and right boundaries', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 }); await loginAsDemo(page); await page.goto('/');
  await expect(page.locator('.home-resume')).toBeVisible();
  const resume = await page.locator('.home-resume').boundingBox(), prompt = await page.locator('.prompt__card').boundingBox();
  expect(Math.abs(resume!.x - prompt!.x)).toBeLessThanOrEqual(1);
  expect(Math.abs(resume!.x + resume!.width - prompt!.x - prompt!.width)).toBeLessThanOrEqual(1);
});

test('STAR editors begin at the same left edge as the field letter on desktop and mobile', async ({ page }) => {
  await loginAsDemo(page);
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 900 }); await page.goto('/cards/new');
    await expect(page.getByRole('textbox', { name: '상황 (Situation)', exact: true })).toBeVisible();
    const edges = await page.locator('.star-read__row').evaluateAll(rows => rows.map(row => ({ letter: row.querySelector('.star-key')!.getBoundingClientRect().left, input: row.querySelector('textarea')!.getBoundingClientRect().left })));
    expect(edges.every(edge => Math.abs(edge.letter - edge.input) <= 1)).toBe(true);
  }
});

test('home and card list titles share their typography and use lighter weights', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/');
  const headingStyle = () => page.locator('main h1').first().evaluate(element => ({ family: getComputedStyle(element).fontFamily, weight: getComputedStyle(element).fontWeight, size: getComputedStyle(element).fontSize, spacing: getComputedStyle(element).letterSpacing }));
  const home = await headingStyle(); await page.goto('/cards'); const cards = await headingStyle();
  expect(home).toEqual(cards); expect(Number(cards.weight)).toBeLessThanOrEqual(650); expect(Number(cards.weight)).toBeGreaterThanOrEqual(500);
  expect(cards.family).toContain('Pretendard');
});

test('mobile repository select keeps its menu in view and roundtrips the empty choice', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 844 }); await loginAsDemo(page); await page.goto('/cards/new');
  const repo = page.getByRole('combobox', { name: '관련 레포', exact: true });
  await repo.click(); const menu = page.getByRole('listbox', { name: '관련 레포', exact: true }); await expect(menu).toBeVisible();
  const bounds = await menu.boundingBox(); expect(bounds!.x).toBeGreaterThanOrEqual(0); expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(320);
  await page.getByRole('option', { name: 'hong-dev/auth-service', exact: true }).click(); await expect(repo).toContainText('hong-dev/auth-service');
  await repo.click(); await page.getByRole('option', { name: '없음', exact: true }).click(); await expect(repo).toContainText('없음');
  await page.getByRole('textbox', { name: '카드 제목', exact: true }).fill('선택값 보존 검사');
  await page.getByRole('button', { name: '임시 저장 시작', exact: true }).click(); await expect(repo).toBeDisabled();
  await page.getByRole('button', { name: '저장 후 종료', exact: true }).click(); await expect(page).toHaveURL(/\/cards\/[^/?]+$/);
  const saved = await page.evaluate(async () => (await (await fetch('/api' + location.pathname)).json()).data);
  expect(saved.repo).toBeNull();
});

test('repository autocomplete exposes its active option and closes without losing the query', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/');
  const search = page.getByRole('combobox', { name: '레포 검색', exact: true }); await search.fill('auth-service');
  await expect(search).toHaveAttribute('aria-expanded', 'true'); await search.press('ArrowDown');
  const active = await search.getAttribute('aria-activedescendant'); expect(active).toBeTruthy();
  expect(await page.evaluate(id => document.getElementById(id!)?.getAttribute('aria-selected'), active)).toBe('true');
  await search.press('Escape'); await expect(search).toHaveAttribute('aria-expanded', 'false'); await expect(search).toHaveValue('auth-service');
  await search.press('ArrowDown'); await search.press('Enter'); await expect(page).toHaveURL(/\/repos\?.*disclose=1/);
});

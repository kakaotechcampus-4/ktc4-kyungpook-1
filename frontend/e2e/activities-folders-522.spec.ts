import { expect, test, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

const SHOTS = 'docs/screenshots/2026-10-31-folders';
const readContrasts = (pairs: { text: string; surface: string }[]) => {
  const luminance = (color: string) => {
    const channels = color.match(/[\d.]+/g)!.slice(0, 3).map(Number).map((c) => c / 255).map((c) => c <= .04045 ? c / 12.92 : ((c + .055) / 1.055) ** 2.4);
    return .2126 * channels[0] + .7152 * channels[1] + .0722 * channels[2];
  };
  return pairs.map(({ text: foreground, surface: background }) => {
    const text = luminance(getComputedStyle(document.querySelector(foreground)!).color);
    const surface = luminance(getComputedStyle(document.querySelector(background)!).backgroundColor);
    return (Math.max(text, surface) + .05) / (Math.min(text, surface) + .05);
  });
};
const folder = (page: Page, name: RegExp | string) => page.getByRole('link', { name });

test('활동 만들기, 활동 안에서 카드 만들기, 폴더 보기, 지우기', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/cards');
  await expect(folder(page, /^활동, 동아리, 교내 개발 동아리, 카드 2장/)).toBeVisible();

  // 활동 만들기(빈 채로 저장하면 칸마다 안내가 나온다)
  await page.getByRole('link', { name: '활동 만들기' }).click();
  await expect(page).toHaveURL(/\/activities\/new$/);
  await page.getByRole('button', { name: '활동 만들기' }).click();
  await expect(page.getByText('활동 이름을 적어 주세요.')).toBeVisible();
  await expect(page.getByLabel('활동 이름')).toBeFocused();
  await page.getByLabel('활동 이름').fill('알고리즘 스터디');
  await page.getByRole('combobox', { name: '종류' }).click();
  await page.getByRole('option', { name: '스터디' }).click();
  await page.getByLabel('시작한 달').fill('2026-03');
  await page.getByRole('checkbox', { name: '진행 중이에요' }).check();
  await page.getByRole('button', { name: '활동 만들기' }).click();
  await expect(page).toHaveURL(/\/cards\/activity\/act_/);
  await expect(page.getByRole('heading', { level: 1, name: '알고리즘 스터디' })).toBeVisible();
  await expect(page.getByText('스터디, 2026년 3월부터 진행 중')).toBeVisible();
  await expect(page.getByText('아직 카드가 없어요.')).toBeVisible();

  // 활동 안에서 경험 카드 만들기 — 활동이 미리 골라져 있다
  await page.getByRole('link', { name: '경험 카드 만들기' }).click();
  await expect(page.getByRole('combobox', { name: '활동', exact: true })).toContainText('알고리즘 스터디');
  await page.getByLabel('카드 제목').fill('스터디를 운영한 경험');
  await page.getByRole('button', { name: /임시 저장 시작|작성 시작/ }).click();
  await expect(page.getByRole('combobox', { name: '활동', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '저장 후 종료' }).click();
  await expect(page).toHaveURL(/\/cards\/card_\d+$/);
  const crumbs = page.getByRole('navigation', { name: 'breadcrumb' });
  await expect(crumbs).toContainText('알고리즘 스터디');

  // 카드 상세의 활동 이름에서 폴더 안으로, 목록의 폴더에서도 같은 곳으로
  await crumbs.getByRole('link', { name: '알고리즘 스터디' }).click();
  await expect(page.locator('.experience-item')).toHaveCount(1);
  await expect(page.getByText('카드 1장, 확정 0장')).toBeVisible();
  await page.getByRole('navigation', { name: 'breadcrumb' }).getByRole('link', { name: '경험 카드' }).click();
  await expect(folder(page, /^활동, 스터디, 알고리즘 스터디, 카드 1장/)).toBeVisible();
  await folder(page, /^활동, 스터디, 알고리즘 스터디/).click();
  await expect(page.locator('.experience-item')).toHaveCount(1);

  // 지우기 — 카드 N장(확정 M장)을 말로 확인한다
  const cardHref = await page.locator('.experience-item').getAttribute('href');
  await page.getByRole('button', { name: '활동 삭제' }).click();
  const dialog = page.getByRole('dialog', { name: '활동을 삭제할까요?' });
  await expect(dialog).toContainText('카드 1장(확정 0장)도 함께 지워져요.');
  await dialog.getByRole('button', { name: '삭제', exact: true }).click();
  await expect(page).toHaveURL(/\/cards$/);
  await expect(folder(page, /알고리즘 스터디/)).toHaveCount(0);
  expect(await page.evaluate(async (href) => (await fetch(`/api${href}`)).status, cardHref)).toBe(404);
});

test('확정 카드가 든 활동을 지우면 확정 카드도 지워진다고 알리고, 취소하면 그대로다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/activities');
  await page.getByRole('button', { name: '교내 개발 동아리 삭제' }).click();
  const dialog = page.getByRole('dialog', { name: '활동을 삭제할까요?' });
  await expect(dialog).toContainText('카드 2장(확정 1장)도 함께 지워져요.');
  await expect(dialog).toContainText('확정한 카드는 공고 매칭과 자소서에 쓰였을 수 있어요.');
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  const row = page.getByRole('link', { name: /^교내 개발 동아리, / });
  await expect(row).toBeVisible();
  await page.getByRole('button', { name: '교내 개발 동아리 삭제' }).click();
  await page.getByRole('dialog').getByRole('button', { name: '삭제', exact: true }).click();
  await expect(row).toHaveCount(0);
  await page.goto('/cards');
  await expect(page.getByRole('radio', { name: '최근순' })).toBeVisible();
  await page.getByRole('radio', { name: '최근순' }).click();
  await expect(page.locator('.experience-item')).toHaveCount(6); // 8장 중 활동 카드 2장이 함께 지워졌다
});

test('폴더는 키보드로 열고, 위에 두면 색만 바뀐다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/cards');
  const auth = folder(page, /^레포 hong-dev, auth-service/);
  await expect(auth).toBeVisible();
  const before = await auth.evaluate((el) => {
    const back = el.querySelector('.folder__back')!, front = el.querySelector('.folder__front')!;
    return { back: getComputedStyle(back).backgroundColor, tab: getComputedStyle(back, '::before').backgroundColor, border: getComputedStyle(front).borderColor, box: JSON.stringify(el.getBoundingClientRect()), transform: getComputedStyle(el).transform };
  });
  await auth.hover();
  const after = await auth.evaluate((el) => {
    const back = el.querySelector('.folder__back')!, front = el.querySelector('.folder__front')!;
    return { back: getComputedStyle(back).backgroundColor, tab: getComputedStyle(back, '::before').backgroundColor, border: getComputedStyle(front).borderColor, box: JSON.stringify(el.getBoundingClientRect()), transform: getComputedStyle(el).transform };
  });
  expect(after.back).not.toBe(before.back); // 뒤판 --bg-ink-hover
  expect(after.tab).toBe(after.back); // 탭은 뒤판과 한 색
  expect(after.border).not.toBe(before.border); // 앞판 테두리 초록
  expect(after.box).toBe(before.box); // 움직임 없음
  expect(after.transform).toBe(before.transform);

  await page.mouse.move(0, 0);
  await auth.focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/cards\/repo\/r_auth$/);
  await expect(page.getByRole('heading', { level: 1, name: 'auth-service' })).toBeVisible();
  await expect(page.getByRole('link', { name: '후보 보드' })).toHaveAttribute('href', '/repos/r_auth/candidates');
  // 폴더 안의 카드를 열면 이전/다음은 폴더 안의 순서를 따른다
  await page.locator('.experience-item').first().click();
  await expect(page).toHaveURL(/\/cards\/card_0[1-4]$/);
});

for (const width of [1440, 390, 320]) for (const theme of ['light', 'dark'] as const) {
  test(`폴더 보기와 폴더 안 ${width} ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.addInitScript((value) => localStorage.setItem('gitory.theme', value), theme);
    await loginAsDemo(page);
    await page.goto('/cards');
    const folders = page.locator('.folder');
    await expect(folders).toHaveCount(5);
    // 격자: 넓은 화면은 여러 열, 좁은 화면은 한두 열. 가로로 넘치지 않고 폴더마다 44px 이상이다
    const columns = await folders.evaluateAll((els) => new Set(els.map((el) => Math.round(el.getBoundingClientRect().left))).size);
    if (width >= 1440) expect(columns).toBeGreaterThanOrEqual(3); else expect(columns).toBeLessThanOrEqual(2);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    for (const box of await folders.evaluateAll((els) => els.map((el) => el.getBoundingClientRect().toJSON()))) {
      expect(box.height).toBeGreaterThanOrEqual(44); expect(box.width).toBeGreaterThanOrEqual(44); expect(box.right).toBeLessThanOrEqual(width);
    }
    // 앞판의 글은 읽을 수 있는 대비다(숫자·소유자·경험 카드 없음·기술 태그)
    for (const value of await page.evaluate(readContrasts, [
      { text: '.folder__owner', surface: '.folder__front' }, { text: '.folder__name', surface: '.folder__front' }, { text: '.folder__note', surface: '.folder__front' },
      { text: '.folder__count-num', surface: '.folder__front' }, { text: '.folder__tag', surface: '.folder__tag' },
    ])) expect(value).toBeGreaterThanOrEqual(4.5);
    // 활동 폴더 앞판 첫 줄은 '활동, 종류'이고, 경험 카드가 없는 폴더에만 '경험 카드 없음'이 있다
    await expect(page.locator('.folder__note')).toHaveCount(2);
    await expect(page.getByText('활동, 동아리', { exact: true })).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/folders-${width}-${theme}.png`, fullPage: true, animations: 'disabled' });

    await folder(page, /^활동, 동아리/).click();
    await expect(page.getByRole('heading', { level: 1, name: '교내 개발 동아리' })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    for (const box of await page.locator('.folder-head__actions .btn').evaluateAll((els) => els.map((el) => el.getBoundingClientRect().toJSON()))) expect(box.height).toBeGreaterThanOrEqual(44);
    await page.screenshot({ path: `${SHOTS}/inside-activity-${width}-${theme}.png`, fullPage: true, animations: 'disabled' });

    if (width !== 320) {
      await page.goto('/cards/repo/r_auth');
      await expect(page.getByRole('region', { name: '기술 카드' })).toBeVisible();
      await expect(page.getByRole('region', { name: '경험 카드' })).toBeVisible();
      await page.screenshot({ path: `${SHOTS}/inside-repo-${width}-${theme}.png`, fullPage: true, animations: 'disabled' });
    }
  });
}

test('활동 목록과 만들기 화면은 모바일에서도 넘치지 않는다', async ({ page }) => {
  await loginAsDemo(page);
  const screens: [string, RegExp][] = [['/activities', /^활동/], ['/activities/new', /^활동 만들기$/], ['/activities/act_club/edit', /^활동 고치기$/], ['/cards/new?activity=act_club', /^직접 작성$/]];
  for (const width of [320, 390]) {
    await page.setViewportSize({ width, height: 900 });
    for (const [path, heading] of screens) {
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth), `${path} ${width}`).toBeLessThanOrEqual(width);
    }
    await page.goto('/activities');
    await expect(page.locator('.activity-row')).toHaveCount(3);
    for (const box of await page.locator('.activity-row .btn').evaluateAll((els) => els.map((el) => el.getBoundingClientRect().toJSON()))) expect(box.height).toBeGreaterThanOrEqual(44);
    await page.screenshot({ path: `${SHOTS}/activities-${width}.png`, fullPage: true, animations: 'disabled' });
  }
});

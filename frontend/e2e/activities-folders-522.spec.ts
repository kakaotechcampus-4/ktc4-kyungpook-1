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
const SHOTS_DESCRIPTION = 'docs/screenshots/2026-11-04-repo-description';
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
  await expect(page.locator('.experience-item')).toHaveCount(0);

  // 활동 안에서 경험 카드 만들기 — 구역 끝의 점선 타일이 연 창에서 제목만 적고, 활동은 누른 폴더라 고르는 칸이 없다
  await page.getByRole('button', { name: '경험 카드 만들기' }).click();
  const create = page.getByRole('dialog', { name: '경험 카드 만들기' });
  await expect(create).toContainText('알고리즘 스터디 폴더에 만들어요');
  await expect(create.getByLabel(/카드 제목/)).toBeFocused();
  await create.getByLabel(/카드 제목/).fill('스터디를 운영한 경험');
  await create.getByRole('button', { name: '네 칸 직접 쓰기' }).click();
  await expect(page).toHaveURL(/\/cards\/card_\d+\?mode=edit$/);
  await expect(page.getByRole('navigation', { name: 'breadcrumb' })).toContainText('알고리즘 스터디');
  await page.getByRole('button', { name: '저장하고 닫기' }).click();
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

test('폴더는 키보드로 열고, 위에 두면 테두리 색만 바뀌며 겹친 종이 장식은 없다(#920)', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/cards');
  const auth = folder(page, /^레포 hong-dev, auth-service/);
  await expect(auth).toBeVisible();
  const read = (el: Element) => {
    const back = el.querySelector('.folder__back')!, front = el.querySelector('.folder__front')!;
    return { layers: ['::before', '::after'].map((pseudo) => getComputedStyle(back, pseudo).display), border: getComputedStyle(front).borderColor, box: JSON.stringify(el.getBoundingClientRect()), transform: getComputedStyle(el).transform };
  };
  const before = await auth.evaluate(read);
  expect(before.layers).toEqual(['none', 'none']); // 폴더 수와 상태는 글이 말한다
  await auth.hover();
  await expect.poll(async () => (await auth.evaluate(read)).border).not.toBe(before.border); // 맨 위 카드 테두리
  const after = await auth.evaluate(read);
  expect(after.layers).toEqual(['none', 'none']);
  expect(after.box).toBe(before.box); // 폴더 자리는 그대로
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

// 격자는 머리 줄과 같은 폭을 채운다(#785) — 1440 은 한 줄에 셋, 1920 은 넷이고 타일은 340~440px 안이다
for (const [width, perRow] of [[1440, 3], [1920, 4]] as const) test(`폴더는 폭을 채워 한 줄에 ${perRow}개이고 타일은 340~440px 이다 (${width})`, async ({ page }) => {
  await page.setViewportSize({ width, height: 900 });
  await loginAsDemo(page);
  await page.goto('/cards');
  const folders = page.locator('.folder');
  await expect(folders).toHaveCount(5);
  const boxes = await folders.evaluateAll((els) => els.map((el) => el.getBoundingClientRect().toJSON()));
  for (const box of boxes) { expect(box.width).toBeGreaterThanOrEqual(340); expect(box.width).toBeLessThanOrEqual(440); }
  expect(boxes.filter((box) => Math.round(box.top) === Math.round(boxes[0].top))).toHaveLength(perRow);
  expect(boxes.filter((box) => box.top > boxes[0].bottom)).toHaveLength(5 - perRow); // 다섯이면 두 줄
});

for (const width of [1440, 390, 320]) for (const theme of ['light', 'dark'] as const) {
  test(`폴더 보기와 폴더 안 ${width} ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.addInitScript((value) => localStorage.setItem('gitory.theme', value), theme);
    await loginAsDemo(page);
    await page.goto('/cards');
    const folders = page.locator('.folder');
    await expect(folders).toHaveCount(5);
    // 격자: 넓은 화면은 한 줄에 셋, 폰은 한 줄에 하나. 가로로 넘치지 않고 폴더마다 44px 이상이다
    const columns = await folders.evaluateAll((els) => new Set(els.map((el) => Math.round(el.getBoundingClientRect().left))).size);
    expect(columns).toBe(width >= 1440 ? 3 : 1);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    for (const box of await folders.evaluateAll((els) => els.map((el) => el.getBoundingClientRect().toJSON()))) {
      expect(box.height).toBeGreaterThanOrEqual(44); expect(box.width).toBeGreaterThanOrEqual(44); expect(box.right).toBeLessThanOrEqual(width);
    }
    // 앞판의 글은 읽을 수 있는 대비다(장 수·소유자·설명·작은 글·기술 태그)
    for (const value of await page.evaluate(readContrasts, [
      { text: '.folder__owner', surface: '.folder__front' }, { text: '.folder__name', surface: '.folder__front' }, { text: '.folder__note', surface: '.folder__front' }, { text: '.folder__desc', surface: '.folder__front' },
      { text: '.folder__count-num', surface: '.folder__front' }, { text: '.folder__tag', surface: '.folder__tag' },
    ])) expect(value).toBeGreaterThanOrEqual(4.5);
    // 활동 폴더 앞판 첫 줄은 '활동, 종류'이고, 설명은 레포 폴더에만 있으며, 작은 글은 기술·경험 카드가 둘 다 있는 폴더와 빈 폴더에만 있다
    await expect(page.locator('.folder__desc')).toHaveCount(2);
    await expect(page.locator('.folder__note')).toHaveCount(2);
    await expect(page.locator('.folder__peek')).toHaveCount(0);
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

for (const theme of ['light', 'dark'] as const) test(`폴더 안 타일: 경험 카드는 종이색 바탕, 기술 카드는 흰 바탕, 점선 타일은 투명 바탕에 점선이다 (${theme})`, async ({ page }) => {
  await page.addInitScript((value) => localStorage.setItem('gitory.theme', value), theme);
  await loginAsDemo(page);
  await page.goto('/cards/repo/r_auth');
  await expect(page.locator('.experience-item')).toHaveCount(4);
  const read = await page.evaluate(() => {
    const token = (name: string) => { const probe = document.createElement('i'); probe.style.background = `var(${name})`; document.body.append(probe); const color = getComputedStyle(probe).backgroundColor; probe.remove(); return color; };
    const style = (selector: string) => getComputedStyle(document.querySelector(selector)!);
    return {
      paper: token('--pastel-yellow'), surface: token('--bg-surface'), strong: token('--border-strong'),
      tech: style('.experience-item:not(.gcard--experience)').backgroundColor, experience: style('.experience-item.gcard--experience').backgroundColor,
      chip: (() => { // 목의 경험 카드에는 태그가 없어 칩 하나를 넣어 본다
        document.querySelector('.gcard--experience')!.insertAdjacentHTML('beforeend', '<ul class="card-tags"><li>태그</li></ul>');
        return style('.gcard--experience .card-tags li').backgroundColor;
      })(),
      add: { background: style('.experience-new').backgroundColor, border: style('.experience-new').borderTopStyle, color: style('.experience-new').borderTopColor },
    };
  });
  expect(read.paper).not.toBe(read.surface);
  expect(read.tech).toBe(read.surface);
  expect(read.experience).toBe(read.paper);
  expect(read.chip).toBe(read.surface);
  expect(read.add).toEqual({ background: 'rgba(0, 0, 0, 0)', border: 'dashed', color: read.strong });
});

for (const width of [390, 320]) test(`폴더 안 경험 카드 만들기 창은 폰(${width})에서도 넘치지 않고 버튼이 44px 이상이다`, async ({ page }) => {
  await page.setViewportSize({ width, height: 700 });
  await loginAsDemo(page);
  await page.goto('/cards/repo/r_auth');
  await page.getByRole('button', { name: '경험 카드 만들기' }).click();
  const dialog = page.getByRole('dialog', { name: '경험 카드 만들기' });
  await expect(dialog.getByRole('button', { name: '질문 받으며 쓰기' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
  const box = await dialog.boundingBox();
  for (const button of await dialog.locator('.new-card-dialog__actions .btn').all()) {
    const rect = (await button.boundingBox())!;
    expect(rect.height).toBeGreaterThanOrEqual(44);
    expect(rect.x).toBeGreaterThanOrEqual(box!.x); expect(rect.x + rect.width).toBeLessThanOrEqual(box!.x + box!.width);
  }
});

test('활동 목록과 만들기 화면은 모바일에서도 넘치지 않는다', async ({ page }) => {
  await loginAsDemo(page);
  const screens: [string, RegExp][] = [['/activities', /^활동/], ['/activities/new', /^활동 만들기$/], ['/activities/act_club/edit', /^활동 고치기$/], ['/cards/new?activity=act_club', /^경험 카드 만들기$/]];
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

test('레포 폴더 설명을 고치면 폴더 머리와 목록 타일에 바로 보이고, 비우면 GitHub 설명으로 돌아간다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/cards');
  const auth = folder(page, /^레포 hong-dev, auth-service/);
  await expect(auth.locator('.folder__desc')).toHaveText('로그인과 세션 인증을 맡는 Spring Boot 서비스');
  await auth.click();
  await expect(page.getByRole('heading', { level: 1, name: 'auth-service' })).toBeVisible();
  await expect(page.getByText('로그인과 세션 인증을 맡는 Spring Boot 서비스')).toBeVisible();

  await page.getByRole('button', { name: '폴더 설명 수정' }).click();
  const dialog = page.getByRole('dialog', { name: '폴더 설명' });
  const field = dialog.getByRole('textbox', { name: '한 줄 설명' });
  await expect(field).toHaveValue('');
  await expect(dialog.getByRole('button', { name: '저장' })).toBeDisabled();
  await field.fill('팀 프로젝트에서 로그인을 맡아 만든 서비스');
  await dialog.getByRole('button', { name: '저장' }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.getByText('팀 프로젝트에서 로그인을 맡아 만든 서비스')).toBeVisible();
  await page.getByRole('navigation', { name: 'breadcrumb' }).getByRole('link', { name: '경험 카드' }).click();
  await expect(folder(page, /^레포 hong-dev, auth-service/).locator('.folder__desc')).toHaveText('팀 프로젝트에서 로그인을 맡아 만든 서비스');

  await folder(page, /^레포 hong-dev, auth-service/).click();
  await page.getByRole('button', { name: '폴더 설명 수정' }).click();
  await expect(field).toHaveValue('팀 프로젝트에서 로그인을 맡아 만든 서비스');
  await field.fill('');
  await dialog.getByRole('button', { name: '저장' }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.getByText('로그인과 세션 인증을 맡는 Spring Boot 서비스')).toBeVisible();
});

for (const width of [1440, 390]) for (const theme of ['light', 'dark'] as const) {
  test(`폴더 타일에 프로젝트 설명이 보이고 설명이 없는 레포도 같은 높이다 ${width} ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.addInitScript((value) => localStorage.setItem('gitory.theme', value), theme);
    await loginAsDemo(page);
    await page.goto('/cards');
    const auth = folder(page, /^레포 hong-dev, auth-service/);
    await expect(auth.locator('.folder__desc')).toHaveText('로그인과 세션 인증을 맡는 Spring Boot 서비스');
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    const [desc] = await auth.locator('.folder__desc').evaluateAll((els) => els.map((el) => el.getBoundingClientRect().height));
    expect(desc).toBeGreaterThanOrEqual(36); // 두 줄 자리
    await page.screenshot({ path: `${SHOTS_DESCRIPTION}/folders-${width}-${theme}.png`, fullPage: true, animations: 'disabled' });

    if (width === 1440 && theme === 'light') {
      await auth.click();
      await page.getByRole('button', { name: '폴더 설명 수정' }).click();
      await expect(page.getByRole('dialog', { name: '폴더 설명' })).toBeVisible();
      await page.screenshot({ path: `${SHOTS_DESCRIPTION}/dialog-1440-light.png`, animations: 'disabled' });
    }
  });
}

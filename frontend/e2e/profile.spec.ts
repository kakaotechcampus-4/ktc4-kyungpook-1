import { expect, test, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

/** 인적사항(제안 계약) — 홈 첫 방문 안내 → 입력 → 저장 → 새로고침 → 마이페이지. 목 서버가 브라우저 안에서 돈다. */

const noHorizontalOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth);
const banner = (page: Page) => page.getByRole('region', { name: '매칭에 쓸 인적사항을 입력해 주세요' });

async function chooseStatus(page: Page, row: number, option: string) {
  await page.getByRole('combobox', { name: `학력 ${row} 상태`, exact: true }).click();
  await page.getByRole('option', { name: option, exact: true }).click();
}
async function addEducation(page: Page, n: number, values: { school: string; major: string; status?: string; start?: string; end?: string }) {
  await page.getByRole('button', { name: '학력 추가', exact: true }).click();
  await page.getByLabel(`학력 ${n} 학교`, { exact: true }).fill(values.school);
  await page.getByLabel(`학력 ${n} 전공`, { exact: true }).fill(values.major);
  await chooseStatus(page, n, values.status ?? '졸업');
  await page.getByLabel(`학력 ${n} 시작 월`, { exact: true }).fill(values.start ?? '2018-03');
  await page.getByLabel(`학력 ${n} 종료 월`, { exact: true }).fill(values.end ?? '2022-02');
}
async function addCareer(page: Page, n: number, values: { company: string; role: string; start?: string }) {
  await page.getByRole('button', { name: '경력 추가', exact: true }).click();
  await page.getByLabel(`경력 ${n} 회사`, { exact: true }).fill(values.company);
  await page.getByLabel(`경력 ${n} 직무`, { exact: true }).fill(values.role);
  await page.getByLabel(`경력 ${n} 시작 월`, { exact: true }).fill(values.start ?? '2022-03');
  await page.getByLabel(`경력 ${n} 재직 중`, { exact: true }).check();
}
async function addLanguage(page: Page, n: number, values: { name: string; score: string; month?: string }) {
  await page.getByRole('button', { name: '어학 추가', exact: true }).click();
  await page.getByLabel(`어학 ${n} 시험`, { exact: true }).fill(values.name);
  await page.getByLabel(`어학 ${n} 점수 또는 등급`, { exact: true }).fill(values.score);
  await page.getByLabel(`어학 ${n} 취득 월`, { exact: true }).fill(values.month ?? '2021-08');
}
async function addCertificate(page: Page, n: number, values: { name: string; issuer?: string; month?: string }) {
  await page.getByRole('button', { name: '자격증 추가', exact: true }).click();
  await page.getByLabel(`자격증 ${n} 이름`, { exact: true }).fill(values.name);
  if (values.issuer) await page.getByLabel(`자격증 ${n} 발급 기관`, { exact: true }).fill(values.issuer);
  await page.getByLabel(`자격증 ${n} 취득 월`, { exact: true }).fill(values.month ?? '2021-11');
}
const status = (page: Page) => page.locator('.sticky-footer strong');

test.beforeEach(async ({ page }) => { await loginAsDemo(page); });

test('home prompt → form → save → reload keeps it → prompt is gone → MyPage link works', async ({ page }) => {
  await page.goto('/');
  await expect(banner(page)).toBeVisible();
  await expect(banner(page).getByRole('button', { name: '나중에', exact: true })).toBeVisible();
  await banner(page).getByRole('link', { name: '입력하기', exact: true }).click();
  await expect(page).toHaveURL(/\/settings\/profile$/);
  await expect(page.getByRole('heading', { name: '인적사항', level: 1 })).toBeVisible();
  await expect(status(page)).toHaveText('고친 곳이 없어요');

  await addEducation(page, 1, { school: '샘플대학교', major: '컴퓨터공학' });
  await addCareer(page, 1, { company: '샘플컴퍼니', role: '백엔드 개발' });
  await addLanguage(page, 1, { name: 'TOEIC', score: '900' });
  await addCertificate(page, 1, { name: '정보처리기사', issuer: '한국산업인력공단' });
  await expect(status(page)).toHaveText('저장하지 않은 변경이 있어요');
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(status(page)).toHaveText('저장했어요');
  await expect(page.getByRole('button', { name: '저장', exact: true })).toBeFocused();

  await page.reload();
  await expect(page.getByLabel('학력 1 학교', { exact: true })).toHaveValue('샘플대학교');
  await expect(page.getByRole('combobox', { name: '학력 1 상태', exact: true })).toContainText('졸업');
  await expect(page.getByLabel('경력 1 재직 중', { exact: true })).toBeChecked();
  await expect(page.getByLabel('경력 1 종료 월', { exact: true })).toBeDisabled();
  await expect(page.getByLabel('어학 1 점수 또는 등급', { exact: true })).toHaveValue('900');
  await expect(page.getByLabel('자격증 1 발급 기관', { exact: true })).toHaveValue('한국산업인력공단');
  await expect(status(page)).toHaveText('고친 곳이 없어요');

  await page.goto('/');
  await expect(page.getByRole('heading', { name: '경험 정리', level: 1 })).toBeVisible();
  await expect(banner(page)).toHaveCount(0);

  await page.goto('/settings');
  const row = page.getByRole('link', { name: /인적사항/ });
  await expect(row).toContainText('학력 1 · 경력 1 · 어학 1 · 자격증 1');
  await row.click();
  await expect(page).toHaveURL(/\/settings\/profile$/);
  await expect(page.getByLabel('학력 1 전공', { exact: true })).toHaveValue('컴퓨터공학');
});

test('"나중에" closes the prompt for good without blocking anything, and MyPage still opens the form', async ({ page }) => {
  await page.goto('/');
  await expect(banner(page)).toBeVisible();
  // 안내가 있어도 주 흐름은 그대로다
  await expect(page.getByRole('combobox', { name: '레포 검색' })).toBeEnabled();
  await banner(page).getByRole('button', { name: '나중에', exact: true }).click();
  await expect(banner(page)).toHaveCount(0);
  await expect(page.getByText('마이페이지에서 언제든 인적사항을 입력할 수 있어요.')).toBeVisible();
  expect(await page.evaluate(() => Object.keys(localStorage).filter((key) => key.startsWith('gitory.profile-prompt')))).toHaveLength(1);
  await page.reload();
  await expect(page.getByRole('heading', { name: '경험 정리', level: 1 })).toBeVisible();
  await expect(banner(page)).toHaveCount(0);
  await page.goto('/settings');
  await expect(page.getByRole('link', { name: /인적사항/ })).toContainText('아직 입력하지 않았어요');
  await page.getByRole('link', { name: /인적사항/ }).click();
  await expect(page.getByRole('heading', { name: '인적사항', level: 1 })).toBeVisible();
});

test('the prompt also appears for a first-time user with no cards, above the start prompt', async ({ page }) => {
  await page.goto('/?demoScenario=empty');
  await expect(banner(page)).toBeVisible();
  const hero = page.getByRole('heading', { name: /오늘은 어떤 경험을 정리해 볼까요/ });
  await expect(hero).toBeVisible();
  expect((await banner(page).boundingBox())!.y).toBeLessThan((await hero.boundingBox())!.y);
  // 안내가 있어도 시작 입력은 그대로 쓸 수 있다
  await expect(page.getByRole('combobox', { name: '레포 검색' })).toBeEnabled();
});

test('a failed save keeps the input and the retry saves it', async ({ page }) => {
  await page.goto('/settings/profile?demoScenario=save-error');
  await addCertificate(page, 1, { name: '정보처리기사' });
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('서버에 연결하지 못했어요');
  await expect(status(page)).toHaveText('저장하지 못했어요. 입력한 내용은 그대로예요');
  await expect(page.getByLabel('자격증 1 이름', { exact: true })).toHaveValue('정보처리기사');
  await expect(page.getByRole('button', { name: '다시 저장', exact: true })).toBeFocused();
  await page.getByRole('button', { name: '다시 저장', exact: true }).click();
  await expect(status(page)).toHaveText('저장했어요');
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('invalid input is explained next to the field and nothing is saved', async ({ page }) => {
  await page.goto('/settings/profile');
  await page.getByRole('button', { name: '학력 추가', exact: true }).click();
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(page.getByText('학교 이름을 입력해 주세요')).toBeVisible();
  await expect(page.getByLabel('학력 1 학교', { exact: true })).toBeFocused();
  await expect(page.getByRole('alert')).toContainText('4곳');
  await page.getByLabel('학력 1 학교', { exact: true }).fill('샘플대학교');
  await page.getByLabel('학력 1 전공', { exact: true }).fill('컴퓨터공학');
  await chooseStatus(page, 1, '졸업');
  await page.getByLabel('학력 1 시작 월', { exact: true }).fill('2022-03');
  await page.getByLabel('학력 1 종료 월', { exact: true }).fill('2022-02');
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(page.getByText('종료 월은 시작 월과 같거나 뒤여야 해요')).toBeVisible();
  const saved = await page.evaluate(() => JSON.parse(sessionStorage.getItem('gitory.demo.db') ?? '{}').profile);
  expect(saved?.education ?? []).toEqual([]);
});

test('leaving with unsaved input asks first', async ({ page }) => {
  await page.goto('/settings/profile');
  await addCertificate(page, 1, { name: '정보처리기사' });
  await page.getByRole('link', { name: '마이페이지로', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '아직 저장되지 않은 내용이 있어요' });
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: '계속 작성', exact: true }).click();
  await expect(page).toHaveURL(/\/settings\/profile$/);
  await expect(page.getByLabel('자격증 1 이름', { exact: true })).toHaveValue('정보처리기사');
  await page.getByRole('link', { name: '마이페이지로', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: '저장하지 않고 나가기', exact: true }).click();
  await expect(page).toHaveURL(/\/settings$/);
});

test('rows can be removed and the list stops at ten', async ({ page }) => {
  await page.goto('/settings/profile');
  for (let i = 0; i < 10; i++) await page.getByRole('button', { name: '어학 추가', exact: true }).click();
  await expect(page.getByRole('button', { name: '어학 추가', exact: true })).toBeDisabled();
  await expect(page.getByText('최대 10개까지 입력할 수 있어요')).toBeVisible();
  await page.getByRole('button', { name: '어학 10 삭제', exact: true }).click();
  await expect(page.getByLabel('어학 10 시험', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '어학 추가', exact: true })).toBeEnabled();
});

for (const width of [390, 320]) {
  test(`mobile ${width}: banner, filled form and MyPage have no horizontal scroll and touch-sized controls`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await page.goto('/');
    await expect(banner(page)).toBeVisible();
    expect(await noHorizontalOverflow(page)).toBe(true);
    for (const control of [banner(page).getByRole('link', { name: '입력하기', exact: true }), banner(page).getByRole('button', { name: '나중에', exact: true })]) {
      expect((await control.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    }

    await page.goto('/settings/profile');
    for (let n = 1; n <= 3; n++) {
      await addEducation(page, n, { school: `아주아주 긴 이름의 대학교 ${n} 캠퍼스 대학원 일반대학원 협동과정`, major: '컴퓨터공학과 소프트웨어 융합 전공 심화 과정' });
      await addCareer(page, n, { company: `매우 긴 이름의 주식회사 ${n} 소프트웨어 연구소`, role: '백엔드 서버 개발 및 인프라 운영 담당' });
      await addLanguage(page, n, { name: 'TOEIC Speaking', score: 'Level 7 / IH / N1 1급' });
      await addCertificate(page, n, { name: '정보처리기사 실기 필기 통합 자격', issuer: '한국산업인력공단 서울지역본부' });
    }
    expect(await noHorizontalOverflow(page)).toBe(true);
    const controls = page.locator('.profile-page input:not([type="checkbox"]), .profile-page .select-trigger, .profile-page .btn');
    const heights = await controls.evaluateAll((els) => els.filter((el) => (el as HTMLElement).offsetParent !== null).map((el) => el.getBoundingClientRect().height));
    expect(heights.length).toBeGreaterThan(30);
    expect(Math.min(...heights)).toBeGreaterThanOrEqual(43.5);
    const inputs = page.locator('.profile-page input:not([type="checkbox"])');
    const widths = await inputs.evaluateAll((els) => els.map((el) => el.getBoundingClientRect().right));
    expect(Math.max(...widths)).toBeLessThanOrEqual(width);
    expect(await page.locator('.profile-page input').first().evaluate((el) => parseFloat(getComputedStyle(el).fontSize))).toBeGreaterThanOrEqual(16); // iOS 확대 방지

    await page.getByRole('button', { name: '저장', exact: true }).click();
    await expect(status(page)).toHaveText('저장했어요');
    await page.goto('/settings');
    await expect(page.getByRole('link', { name: /인적사항/ })).toContainText('학력 3 · 경력 3 · 어학 3 · 자격증 3');
    expect(await noHorizontalOverflow(page)).toBe(true);
  });
}

test('mobile 390: the sticky save bar does not hide the last section', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/settings/profile');
  await page.getByRole('button', { name: '자격증 추가', exact: true }).scrollIntoViewIfNeeded();
  const add = await page.getByRole('button', { name: '자격증 추가', exact: true }).boundingBox();
  const footer = await page.locator('.sticky-footer').boundingBox();
  expect(add!.y + add!.height).toBeLessThanOrEqual(footer!.y + 1);
});

for (const width of [1440, 390]) {
  test(`dark theme ${width}: prompt, form and errors stay readable`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.addInitScript(() => localStorage.setItem('gitory.theme', 'dark'));
    const contrast = (pairs: { text: string; surface: string }[]) => pairs.map(({ text: foreground, surface: background }) => {
      const luminance = (color: string) => {
        const [r, g, b] = color.match(/[\d.]+/g)!.slice(0, 3).map(Number).map((c) => c / 255).map((c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
      };
      const text = luminance(getComputedStyle(document.querySelector(foreground)!).color);
      const surface = luminance(getComputedStyle(document.querySelector(background)!).backgroundColor);
      return (Math.max(text, surface) + 0.05) / (Math.min(text, surface) + 0.05);
    });
    await page.goto('/');
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await expect(banner(page)).toBeVisible();
    for (const value of await page.evaluate(contrast, [{ text: '.profile-prompt__text h2', surface: '.profile-prompt' }, { text: '.profile-prompt__text p', surface: '.profile-prompt' }, { text: '.profile-prompt .btn--primary', surface: '.profile-prompt .btn--primary' }])) {
      expect(value).toBeGreaterThanOrEqual(4.5);
    }
    expect(await noHorizontalOverflow(page)).toBe(true);

    await page.goto('/settings/profile');
    await page.getByRole('button', { name: '학력 추가', exact: true }).click();
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await expect(page.locator('.profile-error').first()).toBeVisible();
    for (const value of await page.evaluate(contrast, [
      { text: '.profile-row__title', surface: '.profile-row' }, { text: '.profile-section__hint', surface: '.profile-section' }, { text: '.profile-error', surface: '.profile-row' },
      { text: '.profile-row .input', surface: '.profile-row .input' }, { text: '.note--danger strong', surface: '.note--danger' },
    ])) expect(value).toBeGreaterThanOrEqual(4.5);
    expect(await noHorizontalOverflow(page)).toBe(true);
  });
}

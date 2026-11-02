import { expect, test, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

/** 인적사항(BE #552 계약) — 홈 첫 방문 안내 → 입력 → 저장 → 새로고침 → 마이페이지. 목 서버가 브라우저 안에서 돈다. */

const noHorizontalOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth);
const banner = (page: Page) => page.getByRole('region', { name: '매칭에 쓸 인적사항을 입력해 주세요' });
/** 진짜 Radix 선택칸에서 보이는 말(한국어 라벨)로 고른다 */
async function choose(page: Page, name: string, option: string) {
  await page.getByRole('combobox', { name, exact: true }).click();
  await page.getByRole('option', { name: option, exact: true }).click();
}
async function addEducation(page: Page, n: number, values: { school: string; major: string; level?: string; status?: string; start?: string; end?: string; gpa?: string; gpaMax?: string }) {
  await page.getByRole('button', { name: '학력 추가', exact: true }).click();
  await choose(page, `학력 ${n} 학력 구분`, values.level ?? '학사');
  await page.getByLabel(`학력 ${n} 학교`, { exact: true }).fill(values.school);
  await page.getByLabel(`학력 ${n} 전공 (선택)`, { exact: true }).fill(values.major);
  await choose(page, `학력 ${n} 상태`, values.status ?? '졸업');
  await page.getByLabel(`학력 ${n} 입학 월 (선택)`, { exact: true }).fill(values.start ?? '2018-03');
  await page.getByLabel(`학력 ${n} 졸업(예정) 월 (선택)`, { exact: true }).fill(values.end ?? '2022-02');
  if (values.gpa) await page.getByLabel(`학력 ${n} 학점 (선택)`, { exact: true }).fill(values.gpa);
  if (values.gpaMax) await choose(page, `학력 ${n} 학점 만점 (선택)`, values.gpaMax);
}
async function addCareer(page: Page, n: number, values: { company: string; role: string; type?: string; start?: string }) {
  await page.getByRole('button', { name: '경력 추가', exact: true }).click();
  await page.getByLabel(`경력 ${n} 회사`, { exact: true }).fill(values.company);
  await choose(page, `경력 ${n} 고용 형태`, values.type ?? '정규직');
  await page.getByLabel(`경력 ${n} 직무 (선택)`, { exact: true }).fill(values.role);
  await page.getByLabel(`경력 ${n} 입사 월`, { exact: true }).fill(values.start ?? '2022-03');
  await page.getByLabel(`경력 ${n} 재직 중`, { exact: true }).check();
}
async function addLanguage(page: Page, n: number, values: { test?: string; testName?: string; score: string; month?: string }) {
  await page.getByRole('button', { name: '어학 추가', exact: true }).click();
  await choose(page, `어학 ${n} 시험`, values.test ?? '토익');
  if (values.testName) await page.getByLabel(`어학 ${n} 시험 이름`, { exact: true }).fill(values.testName);
  await page.getByLabel(`어학 ${n} 점수 또는 등급`, { exact: true }).fill(values.score);
  await page.getByLabel(`어학 ${n} 취득 월 (선택)`, { exact: true }).fill(values.month ?? '2021-08');
}
async function addCertificate(page: Page, n: number, values: { name: string; issuer?: string; month?: string }) {
  await page.getByRole('button', { name: '자격증 추가', exact: true }).click();
  await page.getByLabel(`자격증 ${n} 이름`, { exact: true }).fill(values.name);
  if (values.issuer) await page.getByLabel(`자격증 ${n} 발급 기관 (선택)`, { exact: true }).fill(values.issuer);
  await page.getByLabel(`자격증 ${n} 취득 월 (선택)`, { exact: true }).fill(values.month ?? '2021-11');
}
const status = (page: Page) => page.locator('.sticky-footer strong');
const savedProfile = (page: Page) => page.evaluate(() => JSON.parse(sessionStorage.getItem('gitory.demo.db') ?? '{}').profile);

test.beforeEach(async ({ page }) => { await loginAsDemo(page); });

test('home prompt → form → save → reload keeps it → prompt is gone → MyPage link works', async ({ page }) => {
  await page.goto('/');
  await expect(banner(page)).toBeVisible();
  await expect(banner(page).getByRole('button', { name: '인적사항 입력 건너뛰기', exact: true })).toBeVisible();
  await banner(page).getByRole('link', { name: '입력하기', exact: true }).click();
  await expect(page).toHaveURL(/\/settings\/profile$/);
  await expect(page.getByRole('heading', { name: '인적사항', level: 1 })).toBeVisible();
  await expect(status(page)).toHaveText('고친 곳이 없어요');

  await addEducation(page, 1, { school: '샘플대학교', major: '컴퓨터공학', gpa: '3.8', gpaMax: '4.5' });
  await addCareer(page, 1, { company: '샘플컴퍼니', role: '백엔드 개발' });
  await addLanguage(page, 1, { score: '900' });
  await addCertificate(page, 1, { name: '정보처리기사', issuer: '한국산업인력공단' });
  await expect(status(page)).toHaveText('저장하지 않은 변경이 있어요');
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(status(page)).toHaveText('저장했어요');
  await expect(page.getByRole('button', { name: '저장', exact: true })).toBeFocused();
  expect((await savedProfile(page)).savedAt).not.toBeNull();

  await page.reload();
  await expect(page.getByLabel('학력 1 학교', { exact: true })).toHaveValue('샘플대학교');
  await expect(page.getByRole('combobox', { name: '학력 1 학력 구분', exact: true })).toContainText('학사');
  await expect(page.getByRole('combobox', { name: '학력 1 상태', exact: true })).toContainText('졸업');
  await expect(page.getByLabel('학력 1 학점 (선택)', { exact: true })).toHaveValue('3.8');
  await expect(page.getByRole('combobox', { name: '학력 1 학점 만점 (선택)', exact: true })).toContainText('4.5');
  await expect(page.getByRole('combobox', { name: '경력 1 고용 형태', exact: true })).toContainText('정규직');
  await expect(page.getByLabel('경력 1 재직 중', { exact: true })).toBeChecked();
  await expect(page.getByLabel('경력 1 퇴사 월', { exact: true })).toBeDisabled();
  await expect(page.getByRole('combobox', { name: '어학 1 시험', exact: true })).toContainText('토익');
  await expect(page.getByLabel('어학 1 점수 또는 등급', { exact: true })).toHaveValue('900');
  await expect(page.getByLabel('자격증 1 발급 기관 (선택)', { exact: true })).toHaveValue('한국산업인력공단');
  await expect(status(page)).toHaveText('고친 곳이 없어요');
  // 영어 열거 이름이 화면에 나오지 않는다
  await expect(page.getByRole('main')).not.toContainText(/GRADUATED|BACHELOR|FULL_TIME|TOEIC_SPEAKING|ON_LEAVE/);

  await page.goto('/');
  await expect(page.getByRole('heading', { name: '홈', level: 1 })).toBeVisible();
  await expect(banner(page)).toHaveCount(0);

  await page.goto('/settings');
  const row = page.getByRole('link', { name: /인적사항/ });
  await expect(row).toContainText('학력 1 · 경력 1 · 어학 1 · 자격증 1');
  await row.click();
  await expect(page).toHaveURL(/\/settings\/profile$/);
  await expect(page.getByLabel('학력 1 전공 (선택)', { exact: true })).toHaveValue('컴퓨터공학');
});

test('"건너뛰기" saves four empty lists, closes the prompt for good without blocking anything, and MyPage still opens the form', async ({ page }) => {
  await page.goto('/');
  await expect(banner(page)).toBeVisible();
  // 안내가 있어도 주 흐름은 그대로다
  await expect(page.getByRole('link', { name: '레포 연결하기', exact: true })).toBeVisible();
  expect((await savedProfile(page)).savedAt).toBeNull();
  await banner(page).getByRole('button', { name: '인적사항 입력 건너뛰기', exact: true }).click();
  await expect(banner(page)).toHaveCount(0);
  await expect(page.getByText('마이페이지에서 언제든 인적사항을 입력할 수 있어요.')).toBeVisible();
  // 서버가 기억한다: 빈 목록 네 개가 저장되어 savedAt 이 생겼고, 브라우저에는 아무 표시도 남기지 않는다
  const saved = await savedProfile(page);
  expect(saved.savedAt).not.toBeNull();
  expect([saved.educations, saved.careers, saved.languages, saved.certifications]).toEqual([[], [], [], []]);
  expect(await page.evaluate(() => Object.keys(localStorage).filter((key) => key.startsWith('gitory.profile')))).toEqual([]);
  await page.reload();
  await expect(page.getByRole('heading', { name: '홈', level: 1 })).toBeVisible();
  await expect(banner(page)).toHaveCount(0);
  await page.goto('/settings');
  await expect(page.getByRole('link', { name: /인적사항/ })).toContainText('입력한 정보가 없어요');
  await page.getByRole('link', { name: /인적사항/ }).click();
  await expect(page.getByRole('heading', { name: '인적사항', level: 1 })).toBeVisible();
});

test('the prompt also appears for a first-time user with no cards, without blocking the start actions', async ({ page }) => {
  await page.goto('/?demoScenario=empty');
  await expect(banner(page)).toBeVisible();
  // 안내가 있어도 시작 행동은 그대로 쓸 수 있다
  await expect(page.getByRole('link', { name: '레포 연결하기', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '레포 검색하기', exact: true })).toBeEnabled();
});

test('a failed save keeps the input and the retry saves it', async ({ page }) => {
  await page.goto('/settings/profile?demoScenario=save-error');
  await addCertificate(page, 1, { name: '정보처리기사' });
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('서버에서 처리하지 못했어요');
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
  await expect(page.getByRole('combobox', { name: '학력 1 학력 구분', exact: true })).toBeFocused();
  await expect(page.getByRole('alert')).toContainText('3곳');
  // 진짜 Radix 선택칸도 오류를 스크린리더에 전한다(단위 시험의 모의 select 로는 볼 수 없다)
  const statusSelect = page.getByRole('combobox', { name: '학력 1 상태', exact: true });
  await expect(statusSelect).toHaveAttribute('aria-invalid', 'true');
  await expect(statusSelect).toHaveAccessibleDescription('상태를 선택해 주세요');
  await choose(page, '학력 1 학력 구분', '석사');
  await page.getByLabel('학력 1 학교', { exact: true }).fill('샘플대학교');
  await choose(page, '학력 1 상태', '졸업');
  await expect(statusSelect).not.toHaveAttribute('aria-invalid', 'true');
  await page.getByLabel('학력 1 입학 월 (선택)', { exact: true }).fill('2022-03');
  await page.getByLabel('학력 1 졸업(예정) 월 (선택)', { exact: true }).fill('2022-02');
  await page.getByLabel('학력 1 학점 (선택)', { exact: true }).fill('3.8');
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(page.getByText('졸업(예정) 월은 입학 월과 같거나 뒤여야 해요')).toBeVisible();
  await expect(page.getByText('학점을 적었다면 만점도 골라 주세요')).toBeVisible();
  const saved = await savedProfile(page);
  expect(saved.savedAt).toBeNull();
  expect(saved.educations ?? []).toEqual([]);
});

test('a test that is not on the list asks for its name, and the name is sent only then', async ({ page }) => {
  await page.goto('/settings/profile');
  await page.getByRole('button', { name: '어학 추가', exact: true }).click();
  await expect(page.getByLabel('어학 1 시험 이름', { exact: true })).toHaveCount(0);
  await choose(page, '어학 1 시험', '기타');
  await page.getByLabel('어학 1 점수 또는 등급', { exact: true }).fill('6급');
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(page.getByText('시험 이름을 입력해 주세요')).toBeVisible();
  await page.getByLabel('어학 1 시험 이름', { exact: true }).fill('토픽');
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(status(page)).toHaveText('저장했어요');
  expect((await savedProfile(page)).languages).toEqual([{ test: 'OTHER', testName: '토픽', score: '6급', acquiredMonth: null }]);
  await choose(page, '어학 1 시험', '토익');
  await expect(page.getByLabel('어학 1 시험 이름', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '저장', exact: true }).click();
  await expect(status(page)).toHaveText('저장했어요');
  expect((await savedProfile(page)).languages).toEqual([{ test: 'TOEIC', testName: null, score: '6급', acquiredMonth: null }]);
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

test('rows can be removed and each list stops at its own limit (5 / 10 / 10 / 20)', async ({ page }) => {
  await page.goto('/settings/profile');
  for (const [noun, max] of [['학력', 5], ['경력', 10], ['어학', 10], ['자격증', 20]] as const) {
    const add = page.getByRole('button', { name: `${noun} 추가`, exact: true });
    for (let i = 0; i < max; i++) await add.click();
    await expect(add).toBeDisabled();
    await expect(page.getByRole('region', { name: noun }).getByText(`최대 ${max}개까지 입력할 수 있어요`)).toBeVisible();
    await page.getByRole('button', { name: `${noun} ${max} 삭제`, exact: true }).click();
    await expect(add).toBeEnabled();
  }
  await expect(page.getByLabel('어학 10 시험', { exact: true })).toHaveCount(0);
});

for (const width of [390, 320]) {
  test(`mobile ${width}: banner, filled form and MyPage have no horizontal scroll and touch-sized controls`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    await page.goto('/');
    await expect(banner(page)).toBeVisible();
    expect(await noHorizontalOverflow(page)).toBe(true);
    for (const control of [banner(page).getByRole('link', { name: '입력하기', exact: true }), banner(page).getByRole('button', { name: '인적사항 입력 건너뛰기', exact: true })]) {
      expect((await control.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    }

    await page.goto('/settings/profile');
    for (let n = 1; n <= 3; n++) {
      await addEducation(page, n, { school: `아주아주 긴 이름의 대학교 ${n} 캠퍼스 대학원 일반대학원 협동과정`, major: '컴퓨터공학과 소프트웨어 융합 전공 심화 과정', gpa: '3.85', gpaMax: '4.5' });
      await addCareer(page, n, { company: `매우 긴 이름의 주식회사 ${n} 소프트웨어 연구소`, role: '백엔드 서버 개발 및 인프라 운영 담당' });
      // 시험 이름 칸이 함께 나오는 '기타'로 가장 넓은 줄을 만든다
      await addLanguage(page, n, { test: '기타', testName: 'TOPIK 한국어능력시험 고급 읽기 쓰기', score: 'Level 7 / IH / N1 1급' });
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

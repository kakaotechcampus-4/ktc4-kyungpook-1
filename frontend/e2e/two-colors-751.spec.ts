import { test, expect, type Page } from '@playwright/test';
import { loginAsDemo } from './helpers';

/**
 * #751 — 색은 파랑(지금·선택)과 호박(확인 필요) 둘만 뜻을 갖고, 지우기·로그아웃 진입은 회색 글자 버튼 하나로 맞춘다.
 * 작은 화면에서 손가락으로 누르는 곳(캘린더 날짜·공고 카드·진행 중 체크)은 44px 이상이다.
 */
const tokenColor = (page: Page, token: string, property: 'color' | 'backgroundColor' = 'color') => page.evaluate(([name, prop]) => {
  const probe = document.createElement('span');
  probe.style[prop as 'color'] = getComputedStyle(document.documentElement).getPropertyValue(name);
  document.body.append(probe);
  const value = getComputedStyle(probe)[prop as 'color'];
  probe.remove();
  return value;
}, [token, property] as const);

test('공고 목록: 기업 글자 표시는 모두 같은 회색이고, 마감 임박 D-day 는 호박색이다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/match');
  await expect(page.locator('.match-card')).toHaveCount(5);
  const marks = page.locator('.match-card .company-mark:not(.company-mark--logo)');
  const backgrounds = await marks.evaluateAll((els) => els.map((el) => getComputedStyle(el).backgroundColor));
  expect(backgrounds.length).toBeGreaterThan(1);
  expect(new Set(backgrounds)).toEqual(new Set([await tokenColor(page, '--bg-subtle', 'backgroundColor')]));
  // 오늘 날짜에 따라 데모 공고의 D-day 가 바뀌므로, 같은 클래스의 표시를 직접 그려 색을 본다
  const badge = await page.evaluate(() => {
    const wrap = document.createElement('span');
    wrap.className = 'deadline deadline--soon';
    wrap.innerHTML = '<span class="deadline__badge">D-2</span>';
    document.body.append(wrap);
    const style = getComputedStyle(wrap.firstElementChild!);
    const result = { color: style.color, background: style.backgroundColor };
    wrap.remove();
    return result;
  });
  expect(badge).toEqual({ color: await tokenColor(page, '--state-open-text'), background: await tokenColor(page, '--state-open-bg', 'backgroundColor') });
});

test('홈 캘린더(390px): 날짜 버튼은 44px 이상이고, 요일은 같은 글자색이며 고른 날은 파랑이다', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await loginAsDemo(page);
  await page.goto('/');
  const days = page.locator('.job-calendar .calendar-day__number');
  await expect(days.first()).toBeVisible();
  for (const day of (await days.all()).slice(0, 7)) {
    const box = (await day.boundingBox())!;
    expect(box.width).toBeGreaterThanOrEqual(44);
    expect(box.height).toBeGreaterThanOrEqual(44);
  }
  const weekdayColors = await page.locator('.job-calendar .calendar-weekdays > span').evaluateAll((els) => els.map((el) => getComputedStyle(el).color));
  expect(new Set(weekdayColors)).toEqual(new Set([await tokenColor(page, '--text-secondary')]));
  const selected = page.locator('.job-calendar .calendar-day__number[aria-pressed="true"]');
  await expect(selected).toHaveCount(1);
  expect(await selected.evaluate((el) => getComputedStyle(el, '::before').backgroundColor)).toBe(await tokenColor(page, '--nav-active-bg', 'backgroundColor'));
});

test('지우기·로그아웃 진입 버튼은 회색 글자이고, 활동 삭제는 확인 창의 마지막 버튼만 빨강이다', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/activities');
  const remove = page.getByRole('button', { name: '교내 개발 동아리 삭제' });
  const quiet = await tokenColor(page, '--text-secondary');
  expect(await remove.evaluate((el) => getComputedStyle(el).color)).toBe(quiet);
  await remove.click();
  const confirm = page.getByRole('dialog').getByRole('button', { name: '삭제', exact: true });
  expect(await confirm.evaluate((el) => getComputedStyle(el).color)).toBe(await tokenColor(page, '--state-failed-text'));
  await page.getByRole('dialog').getByRole('button', { name: '취소' }).click();

  await page.goto('/settings');
  expect(await page.getByRole('button', { name: '로그아웃', exact: true }).evaluate((el) => getComputedStyle(el).color)).toBe(quiet);
});

test('공고 카드(390px): 제목 글자 밖, 카드 아래쪽을 눌러도 공고 상세로 간다', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await loginAsDemo(page);
  await page.goto('/match');
  const card = page.locator('.match-card').first();
  const link = card.locator('.match-card__link');
  const href = await link.getAttribute('href');
  // 아래 탭바에 가리지 않게 화면 가운데로 올린다
  await card.evaluate((el) => el.scrollIntoView({ block: 'center' }));
  const box = (await card.boundingBox())!;
  expect(box.height).toBeGreaterThanOrEqual(44);
  // 마감 표시 줄 근처(제목 링크 글자에서 먼 곳)를 누른다
  await page.mouse.click(box.x + box.width / 2, box.y + box.height - 12);
  await expect(page).toHaveURL(new RegExp(`${href!.split('?')[0]}$`));
});

test('활동 만들기(390px): "진행 중이에요" 는 줄 전체가 44px 이상 눌리는 곳이다', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await loginAsDemo(page);
  await page.goto('/activities/new');
  const checkbox = page.getByRole('checkbox', { name: '진행 중이에요' });
  const label = page.locator('label.activity-check');
  await label.evaluate((el) => el.scrollIntoView({ block: 'center' }));
  const box = (await label.boundingBox())!;
  expect(box.height).toBeGreaterThanOrEqual(44);
  expect(box.width).toBeGreaterThanOrEqual(200);
  await expect(checkbox).not.toBeChecked();
  await page.mouse.click(box.x + box.width - 8, box.y + box.height / 2);
  await expect(checkbox).toBeChecked();
});

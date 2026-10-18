import { test, expect } from '@playwright/test';
import { loginAsDemo as login } from './helpers';

/** 멘토 리뷰(6주차 develop → main)에서 실제로 써 보며 느낀 점들. */

test('분석이 도는 동안 경과 시간과 마지막 확인이 흐르고 진행 막대가 움직인다', async ({ page }) => {
  await login(page);
  await page.goto('/repos?select=r_auth&disclose=1');
  await page.getByRole('button', { name: '정리 시작' }).click();
  const live = page.locator('.job-live');
  await expect(live).toContainText(/(째 진행 중이에요|째 기다리는 중이에요)/);
  await expect(live).toContainText('마지막 확인');
  await expect(page.getByRole('progressbar', { name: '분석 진행률' })).toHaveClass(/track--live/);
  const first = await live.innerText();
  await expect.poll(async () => live.innerText(), { timeout: 5000 }).not.toBe(first);   // 1초마다 흐른다
  await expect(page.locator('.stage--NOW .spinner').first()).toBeVisible();
  // 끝나면 활동 표시도 사라진다 — 후보 보드로 넘어간다
  await expect(page).toHaveURL(/\/repos\/r_auth\/candidates/, { timeout: 30_000 });
});

test('후보 보드는 이미 카드로 만든 후보를 기본 목록에서 빼고, 개수가 적힌 행을 눌러야 펼친다', async ({ page }) => {
  await login(page);
  await page.goto('/repos/r_auth/candidates');
  await expect(page.locator('.cand').first()).toBeVisible();
  await expect(page.getByRole('button', { name: /사용됨 (보이기|숨기기)/ })).toHaveCount(0);   // 뒤바뀌는 글자의 버튼이 없다
  const toggle = page.getByRole('button', { name: '이미 카드로 만든 후보 1개 · 보기' });
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');                                // 기본은 접힘
  await expect(page.locator('.cand', { hasText: '토큰 만료 처리' })).toHaveCount(0);
  await toggle.click();
  await expect(page.locator('.record-list')).toHaveCount(1);                                    // 별도 목록이 아니라 같은 묶음 안
  const link = page.locator('.cand', { hasText: '토큰 만료 처리' }).getByRole('link', { name: '카드 보기' });
  await expect(link).toBeVisible();
  await link.click();
  await expect(page).toHaveURL(/\/cards\//);
});

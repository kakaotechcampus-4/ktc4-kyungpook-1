import { test, expect } from '@playwright/test';
import { installContractFixture } from './contract-fixture';

// 인적사항은 서버에 아직 없다(제안 계약) — 실서버 모드에서는 화면도 요청도 열지 않는다.
test('unreleased profile is hidden: no home prompt, no MyPage row, a direct route says not open, and no /profile request is ever sent', async ({ page }) => {
  const http = await installContractFixture(page);
  await page.goto('/');
  await expect(page.getByRole('heading', { name: /오늘은 어떤 경험을 정리해 볼까요/, level: 1 })).toBeVisible();
  await expect(page.getByRole('combobox', { name: '레포 검색' })).toBeVisible(); // 홈이 다 그려진 뒤에 안내가 없음을 본다
  await expect(page.getByRole('region', { name: '매칭에 쓸 인적사항을 입력해 주세요' })).toHaveCount(0);

  await page.goto('/settings');
  await expect(page.getByRole('link', { name: /GitHub 연결 관리/ })).toBeVisible();
  await expect(page.getByRole('link', { name: /인적사항/ })).toHaveCount(0);

  await page.goto('/settings/profile');
  await expect(page.getByRole('heading', { name: '인적사항 입력을 준비 중이에요', level: 1 })).toBeVisible();
  await expect(page.getByRole('button', { name: '저장', exact: true })).toHaveCount(0);

  const guarded = await page.evaluate(async () => {
    const source = '/src/api/profile.ts';
    const { profileApi } = await import(/* @vite-ignore */ source);
    const empty = { education: [], career: [], languages: [], certificates: [] };
    return Promise.all([profileApi.get(), profileApi.save(empty)].map((p: Promise<unknown>) => p.catch((e: { code: string }) => e.code)));
  });
  expect(guarded).toEqual(['FEATURE_UNAVAILABLE', 'FEATURE_UNAVAILABLE']);
  expect(http.requests.filter((r) => r.path.startsWith('/profile'))).toEqual([]);
  expect(http.unexpected).toEqual([]);
});

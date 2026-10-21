import { test, expect } from '@playwright/test';
import { installContractFixture } from './contract-fixture';

test('unreleased matching is hidden and direct routes/calls send no matching request',async({page})=>{
  const http=await installContractFixture(page);
  await page.goto('/match');
  await expect(page.getByRole('heading',{name:'공고 매칭을 준비 중이에요'})).toBeVisible();
  await expect(page.locator('aside').getByRole('link',{name:'공고 매칭',exact:true})).toHaveCount(0);
  const guarded=await page.evaluate(async()=>{
    const source='/src/api/matching.ts';const {matchingApi}=await import(/* @vite-ignore */source);
    return Promise.all([matchingApi.list(),matchingApi.detail('x')].map(p=>p.catch((e:{code:string})=>e.code)));
  });
  expect(guarded).toEqual(['FEATURE_UNAVAILABLE','FEATURE_UNAVAILABLE']);
  expect(http.requests.filter(r=>r.path.startsWith('/matches'))).toEqual([]);
  expect(http.unexpected).toEqual([]);
});

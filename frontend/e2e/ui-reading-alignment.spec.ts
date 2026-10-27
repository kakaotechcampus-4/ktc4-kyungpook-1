import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

test('desktop cards with different title lengths share STAR and footer baselines', async ({ page }) => {
  await loginAsDemo(page); await page.goto('/cards');
  await expect(page.locator('.experience-item')).toHaveCount(5);
  const bounds = await page.locator('.experience-item').evaluateAll(items => items.slice(0,3).map(item=>({star:item.querySelector('.stardots--labeled')!.getBoundingClientRect().top,footer:item.querySelector('.experience-item__footer')!.getBoundingClientRect().top})));
  expect(Math.max(...bounds.map(b=>b.star))-Math.min(...bounds.map(b=>b.star))).toBeLessThanOrEqual(1);
  expect(Math.max(...bounds.map(b=>b.footer))-Math.min(...bounds.map(b=>b.footer))).toBeLessThanOrEqual(1);
});

test('all three repository step labels are visible without horizontal scrolling at 320px', async ({ page }) => {
  await page.setViewportSize({width:320,height:900}); await loginAsDemo(page); await page.goto('/repos');
  const rail=page.getByRole('list',{name:'진행 단계'});
  const bounds=await rail.evaluate(e=>({left:e.getBoundingClientRect().left,right:e.getBoundingClientRect().right,items:Array.from(e.children).map(child=>({left:child.getBoundingClientRect().left,right:child.getBoundingClientRect().right}))}));
  for(const item of bounds.items){expect(item.left).toBeGreaterThanOrEqual(bounds.left);expect(item.right).toBeLessThanOrEqual(bounds.right);}
});

test('mobile evidence links are easy to press and expose long snippets without truncation', async ({ page }) => {
  await page.setViewportSize({width:320,height:900}); await loginAsDemo(page); await page.goto('/cards/card_01');
  // 근거는 칸마다 버튼 하나로 접혀 있고 팝업에서 본다
  const open=page.getByRole('button',{name:/근거 커밋 \d+개 확인하기/}).first(); expect((await open.boundingBox())!.height).toBeGreaterThanOrEqual(44); await open.click();
  const evidence=page.getByRole('dialog').locator('a.evidence').first(); await expect(evidence).toBeVisible();
  expect((await evidence.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  await evidence.locator('.evidence__sha').evaluate(e=>{e.textContent='abcdef0123456789abcdef0123456789abcdef0123';});
  await evidence.locator('.evidence__snippet').evaluate(e=>{e.textContent='긴 커밋 설명과 경로를 화면에서 모두 읽을 수 있어야 합니다. '.repeat(4);});
  expect(await evidence.locator('.evidence__snippet').evaluate(e=>getComputedStyle(e).whiteSpace)).not.toBe('nowrap');
  expect(await evidence.evaluate(e=>e.scrollWidth)).toBeLessThanOrEqual((await evidence.boundingBox())!.width);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(320);
});

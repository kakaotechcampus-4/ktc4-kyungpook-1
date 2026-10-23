import { expect, test } from '@playwright/test';
import { loginAsDemo } from './helpers';

test('social crawlers get metadata without running JavaScript', async ({ request }) => {
  const response = await request.get('/login?reason=expired');
  const html = await response.text();
  expect(html).toContain('property="og:title"');
  expect(html).toContain('name="twitter:card" content="summary_large_image"');
  expect(html).toContain('rel="canonical" href="https://gitory-prototypes.vercel.app/login"');
  expect(html).toContain('property="og:image:width" content="1200"');
  expect(html).toContain('property="og:image:height" content="630"');
  expect(html).toContain('application/ld+json');
  expect(html).not.toContain('expired · Gitory');
});

test('only the public landing is listed and the share image is a real PNG', async ({ request }) => {
  const robots = await request.get('/robots.txt');
  expect(await robots.text()).toContain('Sitemap: https://gitory-prototypes.vercel.app/sitemap.xml');
  const sitemap = await request.get('/sitemap.xml');
  const xml = await sitemap.text();
  expect(xml).toContain('<loc>https://gitory-prototypes.vercel.app/login</loc>');
  expect(xml.match(/<loc>/g)).toHaveLength(1);
  const image = await request.get('/og/gitory-social-light-v2.png');
  expect(image.status()).toBe(200);
  expect(image.headers()['content-type']).toContain('image/png');
  const bytes = await image.body();
  expect(bytes.readUInt32BE(16)).toBe(1200);
  expect(bytes.readUInt32BE(20)).toBe(630);
});

test('private routes and SPA navigation use noindex without leaking card titles to OG', async ({ page }) => {
  await loginAsDemo(page);
  await page.goto('/cards/card_01');
  await expect(page.locator('meta[name="robots"]')).toHaveAttribute('content', /noindex/);
  await expect(page.locator('meta[property="og:title"]')).not.toHaveAttribute('content', /로그인 세션 처리/);
  await page.goto('/login?intro=1');
  await expect(page.locator('meta[name="robots"]')).toHaveAttribute('content', /index, follow/);
});

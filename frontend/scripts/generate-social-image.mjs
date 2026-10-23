// Manual asset generation; production builds consume the committed PNG and need no browser.
import { chromium } from '@playwright/test';
import { readFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const root = new URL('../', import.meta.url);
const logo = (await readFile(new URL('public/gitory-wordmark-white.svg', root))).toString('base64');
const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: 1200, height: 630 }, deviceScaleFactor: 1 });
  await page.setContent(`<!doctype html><html lang="ko"><head><meta charset="utf-8">
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/variable/pretendardvariable-dynamic-subset.min.css">
    <style>*{box-sizing:border-box}body{margin:0;background:#12140f;color:#f5f7ee;font-family:Pretendard,"Malgun Gothic",sans-serif;letter-spacing:-.04em}.canvas{width:1200px;height:630px;padding:64px 70px;position:relative;overflow:hidden;background:radial-gradient(ellipse at 90% 75%,#25351d 0%,transparent 60%)}.logo{height:42px;width:auto}.tag{position:absolute;right:70px;top:75px;font-size:17px;letter-spacing:.02em;color:#a8c47c}.headline{margin:60px 0 24px;font-size:76px;font-weight:650;line-height:1.14}.headline span{color:#a8c47c}.caption{margin:0;font-size:22px;color:#bdc6b5;line-height:1.6}.flow{position:absolute;right:70px;bottom:68px;display:flex;gap:10px}.slot{width:64px;height:64px;border:1px solid #45563a;background:#1f2719;border-radius:18px;display:grid;place-items:center;font-size:26px;font-weight:600;color:#a8c47c}.slot:first-child{background:#a8c47c;color:#12140f;border-color:#a8c47c}.foot{position:absolute;left:70px;bottom:72px;font-size:17px;color:#8e9d83;letter-spacing:.01em}</style></head>
    <body><main class="canvas"><img class="logo" src="data:image/svg+xml;base64,${logo}" alt="Gitory"><div class="tag">기록을 경험으로</div><h1 class="headline">커밋과 PR을<br><span>자소서 경험으로</span></h1><p class="caption">내가 한 일을 찾고, 내 경험으로 정리하세요.</p><div class="foot">GitHub → 경험 카드</div><div class="flow"><span class="slot">S</span><span class="slot">T</span><span class="slot">A</span><span class="slot">R</span></div></main></body></html>`, { waitUntil: 'networkidle' });
  await page.evaluate(() => document.fonts.ready);
  await page.locator('.logo').evaluate(img => img.decode());
  await mkdir(new URL('public/og/', root), { recursive: true });
  await page.screenshot({ path: fileURLToPath(new URL('public/og/gitory-social-v1.png', root)) });
  console.log('Generated public/og/gitory-social-v1.png (1200×630).');
} finally { await browser.close(); }

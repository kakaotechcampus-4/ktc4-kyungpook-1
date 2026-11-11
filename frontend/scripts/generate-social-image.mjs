// Manual asset generation; production builds consume the committed PNG and need no browser.
// 링크 미리보기 이미지는 로그인 첫 화면의 본문(제목 · GitHub 기록 → 경험 카드 예시)을 잘라 찍는다.
// 운영과 같은 'GitHub로 시작' 버튼이 보이게 샘플 모드를 끄고 띄운 뒤 실행한다.
//   VITE_API_MOCK=false npx vite --port 5173
//   node scripts/generate-social-image.mjs [http://localhost:5173]
import { chromium } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const root = new URL('../', import.meta.url);
const base = process.argv[2] ?? 'http://localhost:5173';
const output = 'public/og/gitory-social-light-v3.png';
const [width, height] = [1200, 630];
const padding = 32;

async function openLanding(browser, scale) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: scale });
  await page.addInitScript(() => localStorage.setItem('gitory.theme', 'light'));
  await page.goto(`${base}/login?intro=1`, { waitUntil: 'networkidle' });
  await page.keyboard.press('Escape');
  await page.locator('.landing[data-intro="done"]').waitFor();
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(1500);
  return page;
}

const browser = await chromium.launch();
try {
  // 본문 상자를 미리보기 비율(1200:630)로 넓힌 영역을 정하고, 그 영역이 정확히 1200×630 이 되게 배율을 맞춘다
  const probe = await openLanding(browser, 1);
  const hero = await probe.locator('.landing__hero').boundingBox();
  await probe.close();
  // 1200:630 = 40:21 이라 너비를 40 의 배수로 올리면 잘라 낸 크기와 배율을 곱한 값이 정수로 떨어진다
  const clipWidth = Math.ceil(Math.max(hero.width + padding * 2, (hero.height + padding * 2) * 40 / 21) / 40) * 40;
  const clipHeight = clipWidth * 21 / 40;
  const clip = { x: Math.round(hero.x + hero.width / 2 - clipWidth / 2), y: Math.round(hero.y + hero.height / 2 - clipHeight / 2),
    width: clipWidth, height: clipHeight };
  const page = await openLanding(browser, width / clipWidth);
  await mkdir(new URL('public/og/', root), { recursive: true });
  await page.screenshot({ path: fileURLToPath(new URL(output, root)), clip });
  console.log(`Generated ${output} (${width}×${height}).`);
} finally { await browser.close(); }

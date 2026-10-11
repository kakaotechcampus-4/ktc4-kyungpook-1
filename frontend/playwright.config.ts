import { defineConfig } from '@playwright/test';
export const testPort = Number(process.env.PLAYWRIGHT_PORT ?? 5173);
const baseURL = `http://localhost:${testPort}`;

/**
 *   npm run e2e         개발 서버 (빠른 피드백)
 *   npm run e2e:static  프로덕션 빌드 + vite preview — Vercel 이 실제로 서빙하는 것과 동일 (playwright.static.config.ts)
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  use: { baseURL, locale: 'ko-KR', viewport: { width: 1440, height: 1024 } },
  webServer: {
    command: `npx vite --port ${testPort} --strictPort`,
    url: baseURL,
    reuseExistingServer: true,
    timeout: 120_000,
  },
  reporter: [['list']],
});

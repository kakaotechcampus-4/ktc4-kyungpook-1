import { defineConfig } from '@playwright/test';

/**
 *   npm run e2e         개발 서버 (빠른 피드백)
 *   npm run e2e:static  프로덕션 빌드 + vite preview — Vercel 이 실제로 서빙하는 것과 동일 (playwright.static.config.ts)
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  use: { baseURL: 'http://localhost:5173', locale: 'ko-KR', viewport: { width: 1440, height: 1024 } },
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 120_000,
  },
  reporter: [['list']],
});

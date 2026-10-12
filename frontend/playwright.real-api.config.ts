import { defineConfig } from '@playwright/test';

const port = Number(process.env.PLAYWRIGHT_REAL_API_PORT ?? 5189);
const baseURL = `http://localhost:${port}`;

/** Browser → fetch → isolated HTTP contract fixtures; never the in-app demo router. */
export default defineConfig({
  testDir: './e2e-real',
  outputDir: './artifacts/playwright-real-api',
  timeout: 60_000,
  use: {
    baseURL, locale: 'ko-KR', viewport: { width: 1440, height: 1024 },
    serviceWorkers: 'block', trace: 'retain-on-failure',
  },
  webServer: {
    command: `npx vite --port ${port} --strictPort`,
    url: baseURL,
    reuseExistingServer: false,
    timeout: 120_000,
    env: {
      VITE_API_MOCK: 'false', VITE_CARD_METADATA_ENABLED: 'false',
      VITE_API_BASE: '/api',
      // An unhandled request cannot reach a developer's configured backend.
      VITE_API_ORIGIN: 'http://127.0.0.1:1',
    },
  },
  reporter: [['list']],
});

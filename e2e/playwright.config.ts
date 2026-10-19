import { defineConfig, devices } from '@playwright/test';

// 스택(compose.yaml)은 시험 밖에서 띄우고, 여기서는 주소만 안다.
// 가짜 서버 상태가 프로세스 하나라 시험은 한 번에 하나씩만 돌린다.
export default defineConfig({
  testDir: './tests',
  timeout: 3 * 60_000,
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list']],
  outputDir: './test-results',
  use: {
    baseURL: 'http://localhost:8088',
    locale: 'ko-KR',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 1024 } } }],
});

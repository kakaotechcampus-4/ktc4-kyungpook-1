import { defineConfig } from '@playwright/test';
import base from './playwright.config';

/**
 * 같은 E2E 를 프로덕션 빌드 + vite preview 에 돌린다 — Vercel 이 서빙하는 것과 동일한 산출물.
 * 정적 빌드에서만 깨지는 부류(목 API 가 개발 서버에만 존재했던 문제)를 잡는 자리.
 */
export default defineConfig({
  ...base,
  webServer: {
    command: 'npm run build && npx vite preview --port 5173 --strictPort',
    url: 'http://localhost:5173',
    reuseExistingServer: false,
    timeout: 180_000,
  },
});

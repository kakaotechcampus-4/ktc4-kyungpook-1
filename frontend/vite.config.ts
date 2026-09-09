import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

/**
 * 두 모드로 돈다. 프론트 코드는 어느 쪽이든 fetch('/api/...') 만 한다.
 *  - VITE_API_MOCK=true  (기본) : 브라우저 안에서 목 API 가 응답한다 (src/mock/browser.ts).
 *                                 개발 서버와 정적 배포(Vercel)가 같은 경로를 타므로 데모가 깨지지 않는다.
 *  - VITE_API_MOCK=false        : 목을 끄고, /api/* 를 VITE_API_ORIGIN(Spring) 으로 프록시한다.
 *
 * 배포에서는 같은 오리진 뒤에 /api 를 리버스 프록시로 붙이는 것을 전제로 한다 (R-10: 교차 출처 쿠키 회피).
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const useMock = env.VITE_API_MOCK !== 'false';
  const apiOrigin = env.VITE_API_ORIGIN ?? 'http://localhost:8080';

  return {
    plugins: [react()],
    resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
    server: {
      port: 5173,
      strictPort: true,
      proxy: useMock ? undefined : { '/api': { target: apiOrigin, changeOrigin: true, cookieDomainRewrite: 'localhost' } },
    },
    build: { outDir: 'dist', sourcemap: false },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
    },
  };
});

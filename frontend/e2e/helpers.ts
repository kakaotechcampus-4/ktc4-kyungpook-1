import type { Page } from '@playwright/test';

/**
 * 데모 모드 로그인 — 실서버에서는 Spring 이 OAuth 콜백에서 세션 쿠키를 세우는 자리.
 * 매 페이지 로드마다 목 DB 를 시드로 되돌리므로 테스트가 서로의 잔상에 흔들리지 않는다.
 * (SPA 내부 이동에는 다시 실행되지 않으니 한 테스트 안의 흐름은 이어진다.)
 */
export async function loginAsDemo(page: Page) {
  await page.addInitScript(() => {
    try { sessionStorage.setItem('gitory.demo.session', '1'); sessionStorage.removeItem('gitory.demo.db'); } catch { /* noop */ }
  });
}

/** 로그인 없이 시드만 초기화 — 랜딩부터 시작하는 테스트용 */
export async function freshSeed(page: Page) {
  await page.addInitScript(() => {
    try { sessionStorage.removeItem('gitory.demo.session'); sessionStorage.removeItem('gitory.demo.db'); } catch { /* noop */ }
  });
}

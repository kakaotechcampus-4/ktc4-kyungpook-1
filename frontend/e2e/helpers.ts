import { expect, type Page } from '@playwright/test';

/**
 * 목 DB 를 테스트 시작 시 딱 한 번만 시드로 되돌린다.
 *
 * 매 페이지 로드마다 지우면 테스트끼리는 깨끗하지만 "새로고침해도 남아 있는가"를 확인할 수 없다.
 * 브라우저 컨텍스트는 테스트마다 새로 뜨므로 표시(seeded)도 같이 사라진다 — 격리는 그대로 유지된다.
 */
const SEED = (login: boolean) => {
  try {
    if (!sessionStorage.getItem('gitory.e2e.seeded')) {
      sessionStorage.removeItem('gitory.demo.db');
      sessionStorage.setItem('gitory.e2e.seeded', '1');
    }
    if (login) sessionStorage.setItem('gitory.demo.session', '1');
    else sessionStorage.removeItem('gitory.demo.session');
  } catch { /* 시크릿 모드 등 */ }
};

/** 데모 모드 로그인 — 실서버에서는 Spring 이 OAuth 콜백에서 세션 쿠키를 세우는 자리. */
export async function loginAsDemo(page: Page) {
  await page.addInitScript(SEED, true);
}

/** 로그인 없이 시드만 초기화 — 랜딩부터 시작하는 테스트용 */
export async function freshSeed(page: Page) {
  await page.addInitScript(SEED, false);
}

/**
 * 경험 카드 목록의 기본 보기는 레포·활동별 폴더다(#522). 카드 타일 자체를 보는 시험은 목록을 연 뒤 이 보기로 바꾼다.
 * 고른 보기는 브라우저가 기억하므로 같은 시험 안의 다음 이동에도 이어진다.
 */
export async function showCardTiles(page: Page) {
  const recent = page.getByRole('radio', { name: '최근순', exact: true });
  await recent.click();
  await expect(recent).toHaveAttribute('aria-checked', 'true');
}

/**
 * 문항만 적은 자소서가 카드 고르기, 재료 확인, 초안 쓰기를 스스로 이어 가는 동안 목 서버의 가짜 시계를 흘려 보낸다.
 * 단계마다 화면이 다음 요청을 보내야 이어지므로 한 번에 넘기지 않고 5초씩 나눠 흘리며, 그 사이에 화면이 따라올 틈을 준다.
 */
export async function runToDraft(page: Page) {
  const done = page.getByText('초안이 나왔어요');
  for (let i = 0; i < 16 && !(await done.isVisible()); i += 1) await page.clock.runFor(5_000);
  await expect(done).toBeVisible();
}

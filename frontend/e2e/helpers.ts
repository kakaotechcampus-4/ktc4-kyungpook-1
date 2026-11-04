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
/** 홈의 레포 검색칸은 '레포 검색하기' 를 눌러야 열린다 */
export async function openRepoSearch(page: Page) {
  await page.getByRole('button', { name: '레포 검색하기', exact: true }).click();
}

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
  await pickCardView(page, '최근순');
}

/** 카드 목록의 보기 방식(폴더·최근순·기술별)을 드롭다운에서 고른다 */
export async function pickCardView(page: Page, label: '폴더' | '최근순' | '기술별') {
  const view = page.getByRole('combobox', { name: '카드 보기 방식' });
  await view.click();
  await page.getByRole('option', { name: label, exact: true }).click();
  await expect(view).toHaveText(label);
}

/** 카드 목록의 작성 상태를 드롭다운에서 고른다. 항목 이름 뒤에 장 수가 붙는다 */
export async function pickCardStatus(page: Page, label: '전체' | '작성 중' | '확정됨' | '보완 필요') {
  await page.getByRole('combobox', { name: '카드 작성 상태 필터' }).click();
  await page.getByRole('option', { name: new RegExp(`^${label} `) }).click();
}

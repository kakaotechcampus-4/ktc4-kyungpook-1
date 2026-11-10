/**
 * 둘러보기 — 운영 빌드(VITE_API_MOCK=false)에서도 로그인 없이 브라우저 안 목 API 로 써 보는 모드.
 *
 * 브라우저 탭(sessionStorage)에만 켜지므로 다른 사람·다른 탭에는 영향이 없다.
 * browser.ts 가 이 파일을 불러 쓰므로 browser.ts 를 가져오지 않는다(순환하면 isDemo 계산 시점에 아직 비어 있다).
 */
export const SAMPLE_KEY = 'gitory.sample';
export const DEMO_SESSION_KEY = 'gitory.demo.session';
export const DEMO_DB_KEY = 'gitory.demo.db';
export const DEMO_SCENARIO_KEY = 'gitory.demo.scenario';

type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

const safeSession = (): Store | undefined => { try { return sessionStorage; } catch { return undefined; } };

export function sampleTourActive(storage: Store | undefined = safeSession()): boolean {
  try { return storage?.getItem(SAMPLE_KEY) === '1'; } catch { return false; }
}

export const demoEnabled = (env: { VITE_API_MOCK?: string }, storage?: Store) =>
  env.VITE_API_MOCK !== 'false' || sampleTourActive(storage);

const drop = (storage: Store | undefined, keys: string[]) => {
  for (const key of keys) { try { storage?.removeItem(key); } catch { /* 시크릿 모드 등 */ } }
};

/** 전체 새로고침으로 들어가야 부팅 때 fetch 가림막이 깔린다 */
export function startSampleTour() {
  const storage = safeSession();
  drop(storage, [DEMO_DB_KEY, DEMO_SCENARIO_KEY]);
  try { storage?.setItem(SAMPLE_KEY, '1'); storage?.setItem(DEMO_SESSION_KEY, '1'); } catch { /* 시크릿 모드 등 */ }
  location.assign('/');
}

export function endSampleTour() {
  drop(safeSession(), [SAMPLE_KEY, DEMO_DB_KEY, DEMO_SCENARIO_KEY, DEMO_SESSION_KEY]);
  location.assign('/login');
}

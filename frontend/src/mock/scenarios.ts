import type { MockResult } from './router';

export const SCENARIOS = {
  normal: { label: '기본', delayMs: 160 },
  slow: { label: '느린 응답', delayMs: 3500 },
  empty: { label: '빈 목록', delayMs: 160 },
  sparse: { label: '긴 제목과 빈 값', delayMs: 160 },
  'read-error': { label: '조회 실패', delayMs: 160 },
  'save-error': { label: '첫 저장 실패', delayMs: 160 },
  'lost-response': { label: '분석 접수 후 응답 유실', delayMs: 160 },
  conflict: { label: '분석 요청 충돌', delayMs: 160 },
  'candidate-error': { label: '첫 후보 변경·카드 생성 실패', delayMs: 160 },
} as const;
export type Scenario = keyof typeof SCENARIOS;
export function parseScenario(value: string | null): Scenario {
  return value && Object.hasOwn(SCENARIOS, value) ? value as Scenario : 'normal';
}

/** Deterministic request faults, independent from view code and the mock business router. */
export function createScenario(scenario: Scenario) {
  let failedSave = false;
  let lostResponse = false;
  const failedCandidateRequests = new Set<string>();
  const fault = (code: string, status = 503): MockResult => ({ status, data: null, error: { code, message: '테스트 시나리오 응답' } });
  return {
    delayMs: SCENARIOS[scenario].delayMs,
    before(method: string, path: string): MockResult | undefined {
      if (scenario === 'candidate-error' && ((method === 'PATCH' && path.startsWith('/candidates/')) || (method === 'POST' && /^\/repos\/[^/]+\/(cards|candidates)$/.test(path)))) {
        const key = method + path;
        if (!failedCandidateRequests.has(key)) {
          failedCandidateRequests.add(key);
          return fault('INTERNAL_ERROR');
        }
      }
      if (scenario === 'read-error' && method === 'GET' && path !== '/me' && !path.startsWith('/jobs')) return fault('INTERNAL_ERROR');
      if (scenario === 'save-error' && method === 'PATCH' && path.endsWith('/draft') && !failedSave) {
        failedSave = true; return fault('INTERNAL_ERROR');
      }
      if (scenario === 'conflict' && method === 'POST' && path.endsWith('/analyze')) return fault('IDEMPOTENCY_KEY_MISMATCH', 409);
      return undefined;
    },
    after(method: string, path: string, result: MockResult): MockResult {
      if (result.status >= 400) return result;
      if (scenario === 'lost-response' && method === 'POST' && path.endsWith('/analyze') && !lostResponse) {
        lostResponse = true; throw new TypeError('Simulated response loss after acceptance');
      }
      if (method !== 'GET') return result;
      if (scenario === 'empty' && (path === '/cards' || path === '/repos')) return { ...result, data: [] };
      if (scenario !== 'sparse') return result;
      if (path === '/me' && result.data && typeof result.data === 'object') return { ...result, data: { ...result.data, avatarUrl: null } };
      if ((path === '/cards' || path === '/repos') && Array.isArray(result.data)) return { ...result, data: result.data.map((item, i) => {
        if (path === '/repos') return { ...item, name: i === 0 ? 'very-long-repository-name-with-no-short-friendly-title'.repeat(3) : item.name, language: null };
        return { ...item, title: i === 0 ? '긴 제목에서도 사용자가 작성한 경험의 내용을 생략하거나 화면 밖으로 밀어내지 않고 읽을 수 있어야 합니다. '.repeat(3) : item.title };
      }) };
      return result;
    },
  };
}

export function waitForMock(ms: number, signal?: AbortSignal | null): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) { reject(new DOMException('Aborted', 'AbortError')); return; }
    const abort = () => { clearTimeout(timer); signal?.removeEventListener('abort', abort); reject(new DOMException('Aborted', 'AbortError')); };
    const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve(); }, ms);
    signal?.addEventListener('abort', abort, { once: true });
  });
}

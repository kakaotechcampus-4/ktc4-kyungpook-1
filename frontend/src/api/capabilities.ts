/** Enable the proposed metadata endpoint only in Mock or after its server contract is confirmed. */
export const cardMetadataSupported = (env: { VITE_API_MOCK?: string; VITE_CARD_METADATA_ENABLED?: string } = {
  VITE_API_MOCK: import.meta.env.VITE_API_MOCK, VITE_CARD_METADATA_ENABLED: import.meta.env.VITE_CARD_METADATA_ENABLED,
}) =>
  env.VITE_API_MOCK !== 'false' || env.VITE_CARD_METADATA_ENABLED === 'true';

/** 기업·직무 매칭과 자소서 초안은 제안 계약이다 — 목에서만 켜고, 서버 계약이 확정돼 VITE_MATCH_ENABLED=true 가 되면 실서버에서도 켠다. */
export const matchSupported = (env: { VITE_API_MOCK?: string; VITE_MATCH_ENABLED?: string } = {
  VITE_API_MOCK: import.meta.env.VITE_API_MOCK, VITE_MATCH_ENABLED: import.meta.env.VITE_MATCH_ENABLED,
}) =>
  env.VITE_API_MOCK !== 'false' || env.VITE_MATCH_ENABLED === 'true';

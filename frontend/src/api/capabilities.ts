/** Enable the proposed metadata endpoint only in Mock or after its server contract is confirmed. */
export const cardMetadataSupported = (env: { VITE_API_MOCK?: string; VITE_CARD_METADATA_ENABLED?: string } = {
  VITE_API_MOCK: import.meta.env.VITE_API_MOCK, VITE_CARD_METADATA_ENABLED: import.meta.env.VITE_CARD_METADATA_ENABLED,
}) =>
  env.VITE_API_MOCK !== 'false' || env.VITE_CARD_METADATA_ENABLED === 'true';

/** Regeneration has no real server endpoint; it is available in the Mock demo only. */
export const cardRegenerationSupported = (env: { VITE_API_MOCK?: string } = {
  VITE_API_MOCK: import.meta.env.VITE_API_MOCK,
}) => env.VITE_API_MOCK !== 'false';

/**
 * 인적사항(학력·경력·어학·자격증) — `GET·PUT /api/profile` 은 제안 단계라 서버에 아직 없다.
 * Mock 에서는 기본으로 켜지고, 실서버 모드에서는 계약 확정·구현 뒤 `VITE_PROFILE_ENABLED=true` 로 명시해야 열린다.
 * 꺼져 있으면 화면은 "준비 중"만 보이고 어떤 요청도 보내지 않는다.
 */
export const profileSupported = (env: { VITE_API_MOCK?: string; VITE_PROFILE_ENABLED?: string } = {
  VITE_API_MOCK: import.meta.env.VITE_API_MOCK, VITE_PROFILE_ENABLED: import.meta.env.VITE_PROFILE_ENABLED,
}) =>
  env.VITE_API_MOCK !== 'false' || env.VITE_PROFILE_ENABLED === 'true';

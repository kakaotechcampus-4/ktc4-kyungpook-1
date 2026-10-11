/** Enable the proposed metadata endpoint only in Mock or after its server contract is confirmed. */
export const cardMetadataSupported = (env: { VITE_API_MOCK?: string; VITE_CARD_METADATA_ENABLED?: string } = {
  VITE_API_MOCK: import.meta.env.VITE_API_MOCK, VITE_CARD_METADATA_ENABLED: import.meta.env.VITE_CARD_METADATA_ENABLED,
}) =>
  env.VITE_API_MOCK !== 'false' || env.VITE_CARD_METADATA_ENABLED === 'true';

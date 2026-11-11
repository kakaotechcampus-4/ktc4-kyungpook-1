/** Shared by build-time HTML and client-side page titles. No account data belongs here. */
export const SITE_NAME = 'Gitory';
export const SITE_TITLE = 'Gitory · 커밋과 PR을 자소서 경험으로';
export const SITE_DESCRIPTION = '레포를 고르면 GitHub 커밋과 PR로 경험 카드를 만들고, 공고에 맞춰 보고, 자소서까지 이어서 써요. 근거는 끝까지 따라가요.';
export const SHARE_IMAGE_PATH = '/og/gitory-social-light-v3.png';

export function createSiteMetadata(configuredOrigin?: string) {
  const url = new URL(configuredOrigin?.trim() || 'https://gitory-prototypes.vercel.app');
  if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('VITE_SITE_URL must be an HTTPS origin without credentials, path, query or fragment.');
  }
  const origin = url.origin;
  return { origin, landing: `${origin}/login`, image: `${origin}${SHARE_IMAGE_PATH}` };
}

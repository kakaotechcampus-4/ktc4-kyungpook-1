/** Shared by build-time HTML and client-side page titles. No account data belongs here. */
export const SITE_NAME = 'Gitory';
export const SITE_TITLE = 'Gitory · 커밋과 PR을 자소서 경험으로';
export const SITE_DESCRIPTION = 'GitHub 커밋과 PR에서 내가 한 일을 찾아 STAR 경험 카드로 정리하세요. 부족한 내용은 질문에 답하며 채우고 자소서와 면접에 활용할 수 있어요.';
export const SHARE_IMAGE_PATH = '/og/gitory-social-v1.png';

export function createSiteMetadata(configuredOrigin?: string) {
  const url = new URL(configuredOrigin?.trim() || 'https://gitory-prototypes.vercel.app');
  if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('VITE_SITE_URL must be an HTTPS origin without credentials, path, query or fragment.');
  }
  const origin = url.origin;
  return { origin, landing: `${origin}/login`, image: `${origin}${SHARE_IMAGE_PATH}` };
}

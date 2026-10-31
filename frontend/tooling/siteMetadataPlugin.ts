import type { HtmlTagDescriptor, Plugin } from 'vite';
import { createSiteMetadata, SITE_DESCRIPTION, SITE_NAME, SITE_TITLE } from '../src/lib/siteMetadata';

/** Emit metadata into static HTML: link-preview bots do not need to execute React. */
export function siteMetadataPlugin(configuredOrigin?: string): Plugin {
  const site = createSiteMetadata(configuredOrigin);
  const robots = `User-agent: *\nAllow: /\nDisallow: /api/\nDisallow: /auth/\n\nSitemap: ${site.origin}/sitemap.xml\n`;
  const sitemap = `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"><url><loc>${site.landing}</loc></url></urlset>\n`;
  const meta = (key: string, value: string, property = false): HtmlTagDescriptor => ({
    tag: 'meta', attrs: { [property ? 'property' : 'name']: key, content: value }, injectTo: 'head',
  });
  return {
    name: 'gitory-site-metadata',
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        const path = request.url?.split('?')[0];
        if (path !== '/robots.txt' && path !== '/sitemap.xml') { next(); return; }
        response.setHeader('Content-Type', path === '/robots.txt' ? 'text/plain; charset=utf-8' : 'application/xml; charset=utf-8');
        response.end(path === '/robots.txt' ? robots : sitemap);
      });
    },
    transformIndexHtml(html) {
      return {
        html: html.replace(/<title>[^<]*<\/title>/, `<title>${SITE_TITLE}</title>`),
        tags: [
          meta('description', SITE_DESCRIPTION),
          meta('robots', 'index, follow, max-image-preview:large'),
          meta('theme-color', '#F2F7F4'),
          { tag: 'link', attrs: { rel: 'canonical', href: site.landing }, injectTo: 'head' },
          meta('og:type', 'website', true), meta('og:site_name', SITE_NAME, true),
          meta('og:title', SITE_TITLE, true), meta('og:description', SITE_DESCRIPTION, true),
          meta('og:locale', 'ko_KR', true), meta('og:url', site.landing, true),
          meta('og:image', site.image, true), meta('og:image:secure_url', site.image, true),
          meta('og:image:type', 'image/png', true), meta('og:image:width', '1200', true),
          meta('og:image:height', '630', true), meta('og:image:alt', 'Gitory — 커밋과 PR을 자소서 경험으로', true),
          meta('twitter:card', 'summary_large_image'), meta('twitter:title', SITE_TITLE),
          meta('twitter:description', SITE_DESCRIPTION), meta('twitter:image', site.image),
          meta('twitter:image:alt', 'Gitory — 커밋과 PR을 자소서 경험으로'),
          {
            tag: 'script', attrs: { type: 'application/ld+json' }, injectTo: 'head',
            children: JSON.stringify({ '@context': 'https://schema.org', '@type': 'WebSite', name: SITE_NAME, alternateName: '깃토리', url: `${site.origin}/`, inLanguage: 'ko-KR', description: SITE_DESCRIPTION }).replace(/</g, '\\u003c'),
          },
        ],
      };
    },
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: 'robots.txt', source: robots });
      this.emitFile({ type: 'asset', fileName: 'sitemap.xml', source: sitemap });
    },
  };
}

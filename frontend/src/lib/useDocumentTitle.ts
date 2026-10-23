import { useEffect } from 'react';
import { SITE_TITLE } from './siteMetadata';

export function useDocumentTitle(title?: string | null) {
  useEffect(() => {
    const prev = document.title;
    const publicLanding = window.location.pathname === '/login';
    document.title = publicLanding ? SITE_TITLE : title ? `${title} · Gitory` : 'Gitory';
    const robots = document.querySelector<HTMLMetaElement>('meta[name="robots"]');
    const previousRobots = robots?.content;
    if (robots) robots.content = publicLanding ? 'index, follow, max-image-preview:large' : 'noindex, nofollow, noarchive';
    return () => { document.title = prev; if (robots && previousRobots !== undefined) robots.content = previousRobots; };
  }, [title]);
}

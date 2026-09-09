import { useEffect } from 'react';

export function useDocumentTitle(title?: string | null) {
  useEffect(() => {
    const prev = document.title;
    document.title = title ? `${title} · Gitory` : 'Gitory';
    return () => { document.title = prev; };
  }, [title]);
}

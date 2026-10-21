import { CardArea, CardTech } from '@/api/schemas';
import type { z } from 'zod';

export const areaLabels: Record<z.infer<typeof CardArea>, string> = {
  BACKEND: '백엔드', FRONTEND: '프론트엔드', MOBILE: '모바일', DEVOPS: 'DevOps',
  AI: 'AI', DB: '데이터베이스', TEST: '테스트', DOCS: '문서',
};
export const techLabels: Record<z.infer<typeof CardTech>, string> = {
  C: 'C', CPP: 'C++', CSHARP: 'C#', JAVA: 'Java', KOTLIN: 'Kotlin', PYTHON: 'Python',
  GO: 'Go', RUST: 'Rust', SWIFT: 'Swift', DART: 'Dart', TYPESCRIPT: 'TypeScript',
  JAVASCRIPT: 'JavaScript', REACT: 'React', SQL: 'SQL', DOCKER: 'Docker', GITHUB_ACTIONS: 'GitHub Actions',
};
export const areaOptions = [{ value: 'ALL', label: '모든 분야' }, ...CardArea.options.map(value => ({ value, label: areaLabels[value] }))];
export const techOptions = [{ value: 'ALL', label: '모든 기술' }, ...CardTech.options.map(value => ({ value, label: techLabels[value] }))];

export type TaggedCard = { areas?: z.infer<typeof CardArea>[]; techs?: z.infer<typeof CardTech>[] };
/** AND between categories; missing tags are unknown, never inferred from STAR text. */
export function matchesTags(card: TaggedCard, area: string, tech: string): boolean {
  return (area === 'ALL' || (card.areas ?? []).some(value => value === area))
    && (tech === 'ALL' || (card.techs ?? []).some(value => value === tech));
}

import { areaLabels, techLabels, type TaggedCard } from '@/lib/cardTags';
import './CardTags.css';

/** Read-only server metadata, shared by home/list/detail. Empty means no extra UI. */
export function CardTags({ areas = [], techs = [] }: TaggedCard) {
  if (!areas.length && !techs.length) return null;
  return <ul className="card-tags" aria-label="분야·기술 태그">
    {areas.map(tag => <li className="card-tags__area" key={`area-${tag}`}>{areaLabels[tag]}</li>)}
    {techs.map(tag => <li key={`tech-${tag}`}>{techLabels[tag]}</li>)}
  </ul>;
}

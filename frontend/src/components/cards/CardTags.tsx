import { areaLabels, techLabels, type TaggedCard } from '@/lib/cardTags';
import './CardTags.css';

/**
 * Read-only server metadata, shared by home/list/detail. Empty means no extra UI.
 * 서버는 기술을 중요한 순서로 최대 8개 보낸다. 좁은 타일은 `techLimit` 으로 앞의 몇 개만 보이고 나머지는 '+N' 으로 접는다.
 */
export function CardTags({ areas = [], techs = [], techLimit }: TaggedCard & { techLimit?: number }) {
  if (!areas.length && !techs.length) return null;
  const shown = techLimit === undefined ? techs : techs.slice(0, techLimit);
  const hidden = techs.slice(shown.length).map(tag => techLabels[tag]);
  return <ul className="card-tags" aria-label="분야와 기술 태그">
    {areas.map(tag => <li className="card-tags__area" key={`area-${tag}`}>{areaLabels[tag]}</li>)}
    {shown.map(tag => <li key={`tech-${tag}`}>{techLabels[tag]}</li>)}
    {hidden.length > 0 && <li className="card-tags__more" title={hidden.join(', ')} aria-label={`기술 ${hidden.length}개 더: ${hidden.join(', ')}`}>+{hidden.length}</li>}
  </ul>;
}

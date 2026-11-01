import type { CardSummary } from '@/api/schemas';
import { dateKey } from './calendarModel';

export function HomeActivity({ cards }: { cards: CardSummary[] }) {
  const today = new Date();
  const days = Array.from({ length: 7 }, (_, index) => {
    const day = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 6 + index);
    const count = cards.filter(card => dateKey(new Date(card.updatedAt)) === dateKey(day)).length;
    return { key: dateKey(day), label: day.toLocaleDateString('ko-KR', { weekday: 'short' }), count };
  });
  const max = Math.max(1, ...days.map(day => day.count));
  return <section className="home-activity" aria-labelledby="home-activity-title"><div className="home-panel-head"><h2 id="home-activity-title">최근 카드 활동</h2><span>최근 7일</span></div><div className="home-activity__chart" role="img" aria-label={days.map(day => `${day.key}: ${day.count}개`).join(', ')}>{days.map((day, index) => <div key={day.key} className={`home-activity__column ${index === 6 ? 'home-activity__column--today' : ''}`}><span>{day.count}</span><div className="home-activity__track"><i style={{ height: `${day.count / max * 100}%` }} /></div><small>{day.label}</small></div>)}</div><p>카드의 마지막 수정일을 기준으로 집계해요.</p></section>;
}

import { useEffect, useState, type CSSProperties } from 'react';
import { activityDate, readCareerActivity, type CareerActivity } from '@/lib/careerActivity';

export function HomeActivity({ userId }: { userId?: string }) {
  const [entries, setEntries] = useState<CareerActivity[]>(() => userId ? readCareerActivity(userId) : []);
  const today = new Date();
  const todayKey = activityDate(today);
  useEffect(() => {
    const update = () => setEntries(userId ? readCareerActivity(userId) : []);
    update(); window.addEventListener('career-activity', update); window.addEventListener('storage', update);
    return () => { window.removeEventListener('career-activity', update); window.removeEventListener('storage', update); };
  }, [userId]);
  const year = today.getFullYear();
  const first = new Date(year, 0, 1);
  const start = new Date(year, 0, 1 - first.getDay());
  const last = new Date(year, 11, 31);
  const dayCount = Math.round((Date.UTC(year, 11, 31) - Date.UTC(year, 0, 1)) / 86400000) + 1;
  const weeks = Math.ceil((first.getDay() + dayCount) / 7);
  const days = Array.from({ length: weeks * 7 }, (_, i) => {
    const date = new Date(start.getFullYear(), start.getMonth(), start.getDate() + i);
    const key = activityDate(date);
    const count = entries.filter(item => item.date === key).length;
    return { key, date, count, level: Math.min(4, count), future: key > todayKey, outside: date.getFullYear() !== year };
  });
  const months = Array.from({ length: 12 }, (_, month) => {
    const offset = Math.round((Date.UTC(year, month, 1) - Date.UTC(year, 0, 1)) / 86400000);
    return { label: `${month + 1}월`, column: Math.floor((first.getDay() + offset) / 7) + 1 };
  });
  const total = entries.filter(item => item.date >= activityDate(first) && item.date <= activityDate(last) && item.date <= todayKey).length;
  return <section className="home-activity" aria-labelledby="home-activity-title">
    <div className="home-panel-head"><h2 id="home-activity-title">나의 커리어 활동</h2><span>{year}년 · {total}건</span></div>
    <div className="career-grass-scroll"><div className="career-grass" style={{ '--grass-weeks': weeks } as CSSProperties}>
      <div className="career-grass__months" aria-hidden>{months.map(month => <span key={month.label} style={{ gridColumn: month.column }}>{month.label}</span>)}</div>
      <div className="career-grass__weekdays" aria-hidden>{['', '월', '', '수', '', '금', ''].map((label, i) => <span key={i}>{label}</span>)}</div>
      <div className="career-grass__grid">{days.map(day => <span key={day.key} className="career-grass__day" role="img" data-level={day.level} data-outside={day.outside || undefined} aria-label={`${day.key}, 활동 ${day.count}건`} title={`${day.key} · 활동 ${day.count}건`} />)}</div>
    </div></div>
    <div className="career-grass__legend"><span>적음</span>{[0, 1, 2, 3, 4].map(level => <i key={level} data-level={level} title={level === 4 ? '4건 이상' : `${level}건`} />)}<span>많음</span></div>
  </section>;
}

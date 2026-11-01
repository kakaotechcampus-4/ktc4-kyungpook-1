import { useId, useState, type FormEvent } from 'react';
import { CalendarDays, ChevronLeft, ChevronRight, ExternalLink, Plus } from 'lucide-react';
import { Button } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { dateKey, daysUntil, monthDays, readSchedules, safeScheduleUrl, scheduleKinds, type JobSchedule, type ScheduleKind } from './calendarModel';

const weekdayNames = ['일', '월', '화', '수', '목', '금', '토'];
const readableDate = (key: string) => new Date(`${key}T12:00:00`).toLocaleDateString('ko-KR', { month: 'long', day: 'numeric', weekday: 'short' });

export function JobCalendar({ userId }: { userId: string }) {
  const storageKey = `gitory.job-calendar.v1.${userId}`;
  const [initial] = useState(() => {
    try { return { events: readSchedules(localStorage.getItem(storageKey)), error: '' }; }
    catch { return { events: [] as JobSchedule[], error: '저장된 일정을 불러오지 못했어요. 새로고침 후 다시 확인해 주세요.' }; }
  });
  const [events, setEvents] = useState(initial.events);
  const [error, setError] = useState(initial.error);
  const today = dateKey(new Date());
  const [month, setMonth] = useState(() => new Date(new Date().getFullYear(), new Date().getMonth(), 1));
  const [selected, setSelected] = useState(today);
  const [editing, setEditing] = useState<JobSchedule | null>(null);
  const formId = useId();
  const days = monthDays(month);
  const selectedEvents = events.filter(e => e.date === selected);
  const upcoming = [...events].filter(e => e.date >= today).sort((a, b) => a.date.localeCompare(b.date)).slice(0, 4);
  const monthPrefix = dateKey(month).slice(0, 7);
  const monthCount = events.filter(e => e.date.startsWith(monthPrefix)).length;
  const write = (next: JobSchedule[]) => {
    try {
      localStorage.setItem(storageKey, JSON.stringify(next));
      setEvents(next); setError(''); return true;
    } catch { setError('일정을 저장하지 못했어요. 브라우저 저장 공간과 설정을 확인해 주세요.'); return false; }
  };
  const add = () => setEditing({ id: '', company: '', role: '', date: selected, kind: 'deadline', url: '', note: '' });
  const moveMonth = (offset: number) => {
    const next = new Date(month.getFullYear(), month.getMonth() + offset, 1);
    setMonth(next); setSelected(dateKey(next));
  };
  const save = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (!editing || !e.currentTarget.reportValidity()) return;
    if (!safeScheduleUrl(editing.url.trim())) { setError('공고 링크는 http 또는 https 주소로 입력해 주세요.'); return; }
    const item = { ...editing, id: editing.id || crypto.randomUUID(), company: editing.company.trim(), role: editing.role.trim(), url: editing.url.trim(), note: editing.note.trim() };
    if (!item.company) { setError('기업명을 입력해 주세요.'); return; }
    if (write([...events.filter(event => event.id !== item.id), item])) {
      setSelected(item.date); setMonth(new Date(`${item.date.slice(0, 7)}-01T12:00:00`)); setEditing(null);
    }
  };
  return <section className="job-calendar" aria-labelledby={`${formId}-heading`}>
    <div className="job-calendar__head">
      <div><span className="home-eyebrow">MY CAREER PLAN</span><h2 id={`${formId}-heading`}>캘린더</h2><p>관심 있는 공고부터 면접까지, 다음 일정을 한눈에 확인하세요.</p></div>
      <Button onClick={add} disabled={Boolean(initial.error)}><Plus size={16} /> 일정 등록</Button>
    </div>
    {error && !editing && <p className="calendar-error" role="alert">{error}</p>}
    <div className="job-calendar__layout">
      <div className="calendar-board">
        <div className="calendar-board__toolbar">
          <div className="calendar-month"><h3 aria-live="polite">{month.getFullYear()}년 {month.getMonth() + 1}월</h3><span>{monthCount}개 일정</span></div>
          <div className="calendar-controls"><button type="button" onClick={() => { setMonth(new Date(`${today.slice(0, 7)}-01T12:00:00`)); setSelected(today); }}>오늘</button><button type="button" onClick={() => moveMonth(-1)} aria-label="이전 달"><ChevronLeft size={17} /></button><button type="button" onClick={() => moveMonth(1)} aria-label="다음 달"><ChevronRight size={17} /></button></div>
        </div>
        <div className="calendar-weekdays" aria-hidden>{weekdayNames.map(d => <span key={d}>{d}</span>)}</div>
        <div className="calendar-days">
          {days.map(day => {
            const key = dateKey(day); const dayEvents = events.filter(event => event.date === key);
            return <div key={key} className={`calendar-day ${day.getMonth() !== month.getMonth() ? 'calendar-day--outside' : ''} ${key === selected ? 'calendar-day--selected' : ''}`}>
              <button type="button" className={`calendar-day__number ${key === today ? 'calendar-day__number--today' : ''}`} aria-label={`${readableDate(key)}, 일정 ${dayEvents.length}개`} aria-pressed={key === selected} aria-current={key === today ? 'date' : undefined} onClick={() => { setSelected(key); if (day.getMonth() !== month.getMonth()) setMonth(new Date(day.getFullYear(), day.getMonth(), 1)); }}>{day.getDate()}</button>
              {dayEvents.slice(0, 2).map(event => <button type="button" key={event.id} className={`calendar-event calendar-event--${event.kind}`} title={`${event.company} · ${scheduleKinds[event.kind]}`} aria-label={`${event.company} · ${scheduleKinds[event.kind]}`} onClick={() => { setSelected(key); setEditing(event); }}><span>{event.company}</span><span>{scheduleKinds[event.kind]}</span></button>)}
              {dayEvents.length > 2 && <button type="button" className="calendar-day__more" onClick={() => setSelected(key)}>+{dayEvents.length - 2}개 더 보기</button>}
            </div>;
          })}
        </div>
        <div className="calendar-legend">{Object.entries(scheduleKinds).map(([kind, label]) => <span key={kind}><i className={`calendar-dot calendar-dot--${kind}`} />{label}</span>)}</div>
      </div>
      <aside className="calendar-agenda" data-has-events={selectedEvents.length > 0} aria-label="채용 일정 목록">
        <div className="calendar-agenda__upcoming"><span className="home-eyebrow">UP NEXT</span><h3>다가오는 일정 <span>{upcoming.length}</span></h3>
          {upcoming.length ? upcoming.map(event => {
            const diff = daysUntil(event.date, today);
            return <button type="button" className="agenda-event" key={event.id} onClick={() => { setSelected(event.date); setEditing(event); }}><span className={`agenda-event__count agenda-event__count--${event.kind}`}>{diff === 0 ? '오늘' : `D-${diff}`}</span><span><strong>{event.company}</strong><small>{event.role || scheduleKinds[event.kind]}</small><small>{readableDate(event.date)} · {scheduleKinds[event.kind]}</small></span></button>;
          }) : <div className="calendar-empty"><CalendarDays size={28} strokeWidth={1.4} /><strong>다음 기회를 준비해요</strong><p>관심 기업의 공고 마감일을<br />첫 일정으로 등록해 보세요.</p><Button variant="outline" size="sm" onClick={add} disabled={Boolean(initial.error)}><Plus size={14} /> 첫 일정 등록</Button></div>}
        </div>
        <div className="calendar-agenda__selected"><h3>{readableDate(selected)}</h3>{selectedEvents.length ? selectedEvents.map(event => <div className="selected-event" key={event.id}><button type="button" onClick={() => setEditing(event)}><i className={`calendar-dot calendar-dot--${event.kind}`} /><span><strong>{event.company}</strong><small>{event.role ? `${event.role} · ` : ''}{scheduleKinds[event.kind]}</small></span></button>{event.url && <a href={event.url} target="_blank" rel="noopener noreferrer" aria-label={`${event.company} 공고 보기`}><ExternalLink size={15} /></a>}</div>) : <p className="calendar-agenda__none">등록된 일정이 없어요.</p>}</div>
      </aside>
    </div>
    <p className="calendar-storage-note">직접 등록한 일정은 이 브라우저에 저장돼요.</p>
    {editing && <Modal title={editing.id ? '공고 일정 수정' : '공고 일정 등록'} width={500} onClose={() => { setEditing(null); setError(''); }} footer={{ strong: '나의 채용 일정', actions: <>{editing.id && <Button variant="danger" onClick={() => { if (write(events.filter(event => event.id !== editing.id))) setEditing(null); }}>삭제</Button>}<Button variant="outline" onClick={() => { setEditing(null); setError(''); }}>취소</Button><Button type="submit" form={formId}>저장</Button></> }}>
      <form id={formId} className="calendar-form" onSubmit={save}>
        {error && <p className="calendar-error" role="alert">{error}</p>}
        <label>기업명 <span>*</span><input className="input" required maxLength={60} value={editing.company} placeholder="예: 관심 기업 이름" onChange={e => setEditing({ ...editing, company: e.target.value })} /></label>
        <label>직무<input className="input" maxLength={100} value={editing.role} placeholder="예: 프론트엔드 개발자" onChange={e => setEditing({ ...editing, role: e.target.value })} /></label>
        <div className="calendar-form__row"><label>날짜 <span>*</span><input className="input" type="date" min="1900-01-01" max="9999-12-31" required value={editing.date} onChange={e => setEditing({ ...editing, date: e.target.value })} /></label><label>일정 유형<select className="input" value={editing.kind} onChange={e => setEditing({ ...editing, kind: e.target.value as ScheduleKind })}>{Object.entries(scheduleKinds).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label></div>
        <label>공고 링크<input className="input" type="url" value={editing.url} placeholder="https://" onChange={e => setEditing({ ...editing, url: e.target.value })} /></label>
        <label>메모<textarea className="input" rows={3} maxLength={1000} value={editing.note} placeholder="준비할 내용이나 참고 사항을 남겨두세요." onChange={e => setEditing({ ...editing, note: e.target.value })} /></label>
      </form>
    </Modal>}
  </section>;
}

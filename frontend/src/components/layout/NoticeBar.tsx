import { useEffect, useId, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { Bell } from 'lucide-react';
import { isCoverLetterRunning, useRunningCoverLetters, type CoverLetterSummary } from '@/api/coverLetters';
import { useMatch } from '@/api/matching';
import { unreadCount, useNotices, useReadAllNotices, useReadNotice } from '@/api/notifications';
import type { ActiveJob } from '@/api/schemas';
import { canAskPermission, markPermissionAsked, requestPermission } from '@/lib/browserNotice';
import { CompanyMark } from '@/features/match/CompanyMark';
import { activeJobLink } from '@/lib/jobLinks';
import { noticeView, shortRepoName, type NoticeView } from '@/lib/notices';
import './notice-bar.css';

const LETTER_WHAT = '자소서 쓰는 중';
const jobWhat = (type: ActiveJob['type']) => (type === 'ANALYZE' ? '레포 읽는 중' : type === 'DRAFT' ? '카드 쓰는 중' : '작업 중');

type Progress =
  | { kind: 'job'; job: ActiveJob }
  | { kind: 'letter'; letter: CoverLetterSummary };

/** 레포 작업이 있으면 가장 먼저 시작한 것, 없으면 가장 먼저 바뀐 자소서 하나와 나머지 수 */
export function pickProgress(jobs: readonly ActiveJob[] | undefined, letters: readonly CoverLetterSummary[] | undefined): { first?: Progress; more: number } {
  const liveJobs = (jobs ?? []).filter((job) => job.state === 'QUEUED' || job.state === 'RUNNING');
  const liveLetters = (letters ?? []).filter((letter) => isCoverLetterRunning(letter.stage));
  const job = [...liveJobs].sort((a, b) => Date.parse(a.startedAt) - Date.parse(b.startedAt))[0];
  const letter = [...liveLetters].sort((a, b) => Date.parse(a.updatedAt) - Date.parse(b.updatedAt))[0];
  const first: Progress | undefined = job ? { kind: 'job', job } : letter ? { kind: 'letter', letter } : undefined;
  return { first, more: Math.max(0, liveJobs.length + liveLetters.length - 1) };
}

/**
 * 오른쪽 위 알림 자리 — 지금 도는 작업, 알림 버튼, 끝난 일의 목록, 브라우저 알림 안내.
 * 목록은 서버가 쌓은 알림이고 서버가 없으면 비어 있는 것으로 보인다.
 */
export function NoticeBar({ jobs }: { jobs: readonly ActiveJob[] | undefined }) {
  const nav = useNavigate();
  const { pathname } = useLocation();
  const notices = useNotices();
  const readOne = useReadNotice();
  const readAll = useReadAllNotices();
  const [open, setOpen] = useState(false);
  const [asked, setAsked] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const panelId = useId();

  const unread = unreadCount(notices.data);
  const now = new Date();
  const views = [...(notices.data ?? [])]
    .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
    .flatMap((notice) => noticeView(notice, { now }) ?? []);
  const runningLetters = useRunningCoverLetters();
  const { first, more } = pickProgress(jobs, runningLetters.data);
  // 자소서 목록에는 로고가 없어 진행 표시에 오른 자소서의 공고만 한 번 더 읽는다
  const letter = first?.kind === 'letter' ? first.letter : null;
  const letterPosting = useMatch(letter?.posting?.id ?? '');
  const letterWho = letter ? letter.posting?.company ?? letter.jobRole : '';
  const askPermission = !!first && !open && !asked && canAskPermission();

  useEffect(() => { setOpen(false); }, [pathname]);
  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => { if (!root.current?.contains(e.target as Node)) setOpen(false); };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { setOpen(false); trigger.current?.focus(); } };
    document.addEventListener('pointerdown', onDown);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('pointerdown', onDown); document.removeEventListener('keydown', onKey); };
  }, [open]);

  const toggle = () => {
    if (!open) void notices.refetch();
    setOpen((value) => !value);
  };
  const go = (view: NoticeView, to: string, markRead: boolean) => {
    if (markRead && view.unread) readOne.mutate(view.id);
    setOpen(false);
    nav(to);
  };
  const answer = (accept: boolean) => {
    setAsked(true);
    if (accept) void requestPermission(); else markPermissionAsked();
  };

  return (
    <div ref={root} className="notice-bar" onBlur={(e) => { if (open && e.relatedTarget && !root.current?.contains(e.relatedTarget as Node)) setOpen(false); }}>
      {first?.kind === 'job' && (
        <Link to={activeJobLink(first.job)} className="notice-pill"
          aria-label={`${first.job.repoName ? `${shortRepoName(first.job.repoName)} ` : ''}${jobWhat(first.job.type)}${more ? ` 외 ${more}개` : ''}`}>
          {first.job.repoName && <span className="notice-pill__who">{shortRepoName(first.job.repoName)}</span>}
          <span className="notice-pill__what">{jobWhat(first.job.type)}</span>
          <span className="notice-pill__spin" aria-hidden />
          {more > 0 && <small className="notice-pill__more">외 {more}개</small>}
        </Link>
      )}
      {letter && (
        <Link to={`/cover-letters/${encodeURIComponent(letter.id)}`} className={`notice-pill${letter.posting ? ' notice-pill--marked' : ''}`}
          aria-label={`${letterWho} ${LETTER_WHAT}${more ? ` 외 ${more}개` : ''}`}>
          {letter.posting && <CompanyMark name={letter.posting.company} logoUrl={letterPosting.data?.logoUrl} size={28} className="notice-pill__mark" />}
          <span className="notice-pill__who">{letterWho}</span>
          <span className="notice-pill__what">{LETTER_WHAT}</span>
          <span className="notice-pill__spin" aria-hidden />
          {more > 0 && <small className="notice-pill__more">외 {more}개</small>}
        </Link>
      )}
      <button ref={trigger} type="button" className="notice-bell" aria-haspopup="dialog" aria-expanded={open} aria-controls={panelId}
        aria-label={unread ? `알림, 안 읽은 알림 ${unread}개` : '알림'} onClick={toggle}>
        <Bell size={18} aria-hidden />
        <span className="notice-bell__text">알림</span>
        {unread > 0 && <i className="notice-bell__count" aria-hidden>{unread}</i>}
      </button>
      {askPermission && (
        <div className="notice-ask" role="group" aria-label="브라우저 알림 안내">
          <p><b>끝나면 브라우저 알림으로 알려 드릴까요?</b><br />다른 일을 하고 있어도 바로 알 수 있어요.</p>
          <div className="notice-ask__row">
            <button type="button" className="notice-btn notice-btn--ink" onClick={() => answer(true)}>알림 받기</button>
            <button type="button" className="notice-btn" onClick={() => answer(false)}>괜찮아요</button>
          </div>
        </div>
      )}
      {open && (
        <div id={panelId} role="dialog" aria-label="알림" className="notice-panel">
          <header className="notice-panel__head">
            <b>알림</b>
            {unread > 0 && <button type="button" className="notice-link" onClick={() => readAll.mutate()}>모두 읽음</button>}
          </header>
          {views.length === 0 ? (
            <p className="notice-empty">{notices.isPending ? '불러오는 중이에요' : '아직 알림이 없어요'}</p>
          ) : (
            <ul className="notice-list">
              {views.map((view) => (
                <li key={view.id} className={`notice${view.unread ? ' notice--new' : ''}${view.hint ? ' notice--bad' : ''}`}>
                  <p className="notice__title">{view.unread && <span className="sr-only">안 읽음, </span>}{view.title}</p>
                  {view.hint && <p className="notice__hint">{view.hint}</p>}
                  {view.lines.length > 0 && <ul className="notice__lines">{view.lines.map((line, i) => <li key={`${i}-${line}`}>{line}</li>)}</ul>}
                  <div className="notice__row">
                    <time>{view.time}</time>
                    <button type="button" className={`notice-btn${view.unread ? ' notice-btn--ink' : ''}`} onClick={() => go(view, view.action.to, true)}>{view.action.label}</button>
                  </div>
                  {view.asks.length > 0 && (
                    <div className="notice__asks">
                      <span>이어서 맡길 수 있어요</span>
                      {view.asks.map((ask) => <button key={ask} type="button" className="notice-chip" onClick={() => go(view, `/ask?q=${encodeURIComponent(ask)}`, false)}>{ask}</button>)}
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}

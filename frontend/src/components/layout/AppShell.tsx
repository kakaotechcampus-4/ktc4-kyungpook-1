import { useEffect, useState } from 'react';
import { NavLink, Outlet, Link } from 'react-router-dom';
import { Home, FolderGit2, Layers, Github, UserRound, PanelLeft, Settings } from 'lucide-react';
import { useCards, useMe } from '@/api/queries';
import { JobWatcher } from '@/lib/jobWatcher';
import { UserAvatar } from '@/components/ui/UserAvatar';

const MENU = [
  { group: '커리어 관리', items: [
    { to: '/', label: '경험정리/홈', icon: Home, end: true },
    { to: '/repos', label: '레포 정리', icon: FolderGit2 },
    { to: '/cards', label: '경험 카드', icon: Layers },
  ] },
  { group: '설정', items: [
    { to: '/settings/github', label: 'GitHub 연결', icon: Github },
    { to: '/settings', label: '마이페이지', icon: UserRound, end: true },
  ] },
];

/** 워드마크 — 사각 G 마크 + itory. 원본 PNG(투명 배경). 다크 서페이스 위에서는 invert. */
export const Wordmark = ({ height = 22, className }: { height?: number; className?: string }) => (
  <img src="/gitory-wordmark.png" alt="Gitory" height={height} className={className} style={{ height, width: 'auto', display: 'block' }} draggable={false} />
);
const Mark = () => <img src="/gitory-mark.png" alt="Gitory" width={28} height={28} style={{ borderRadius: 7 }} draggable={false} />;

const RAIL_KEY = 'gitory.rail';
const MOBILE_MENU = [
  { to: '/', label: '홈', icon: Home, end: true },
  { to: '/repos', label: '레포', icon: FolderGit2 },
  { to: '/cards', label: '카드', icon: Layers },
  { to: '/settings', label: '설정', icon: Settings },
];

/** 앱 셸 — 접히는 레일 사이드바(브랜드 · 메뉴 그룹 · 최근 카드 · 프로필). 접힘 상태는 기억한다. */
export function AppShell() {
  const me = useMe();
  const cards = useCards();
  const [rail, setRail] = useState<boolean>(() => { try { return localStorage.getItem(RAIL_KEY) === '1'; } catch { return false; } });
  useEffect(() => { try { localStorage.setItem(RAIL_KEY, rail ? '1' : '0'); } catch { /* noop */ } }, [rail]);
  const recent = [...(cards.data ?? [])].sort((a, b) => (a.status === 'DRAFT' ? -1 : 1) - (b.status === 'DRAFT' ? -1 : 1) || b.updatedAt.localeCompare(a.updatedAt)).slice(0, 5);

  return (
    <div className={`shell ${rail ? 'shell--rail' : ''}`}>
      <a href="#main" className="skip-link">본문으로 건너뛰기</a>
      <header className="mobile-brand">
        <Link to="/" aria-label="Gitory 홈"><Wordmark height={22} /></Link>
        <span className="mobile-brand__profile"><UserAvatar src={me.data?.avatarUrl} login={me.data?.login ?? '사용자'} size={28} />{me.data?.login ?? '내 경험 정리'}</span>
      </header>
      <aside className="sidebar" aria-label="주 메뉴">
        <div className="sidebar__top">
          <Link to="/" className="brand" aria-label="Gitory 홈">{rail ? <Mark /> : <Wordmark height={22} />}</Link>
          {!rail && <button type="button" className="sidebar__toggle" onClick={() => setRail(true)} aria-label="사이드바 접기" title="사이드바 접기"><PanelLeft size={16} /></button>}
        </div>
        {rail && <button type="button" className="sidebar__toggle" style={{ alignSelf: 'center' }} onClick={() => setRail(false)} aria-label="사이드바 펼치기" title="사이드바 펼치기"><PanelLeft size={16} /></button>}
        {MENU.map((g) => (
          <nav key={g.group} className="menu-group" aria-label={g.group}>
            <div className="menu-group__title">{g.group}</div>
            {g.items.map((it) => (
              <NavLink key={it.to} to={it.to} end={it.end} className="menu-item" title={rail ? it.label : undefined}>
                <it.icon size={16} /><span>{it.label}</span>
              </NavLink>
            ))}
          </nav>
        ))}
        <div className="sidebar__recent">
          <div className="sidebar__recent-title">최근 카드</div>
          {recent.length === 0 && <div className="sidebar__recent-empty">최근 정리한 카드가 이곳에 표시돼요</div>}
          {recent.map((c) => <Link key={c.id} to={`/cards/${c.id}`} title={c.title}>{c.status === 'DRAFT' ? '✎ ' : ''}{c.title}</Link>)}
        </div>
        <div style={{ marginTop: 'auto' }} />
        <div className="sidebar__profile">
          <UserAvatar src={me.data?.avatarUrl} login={me.data?.login ?? '사용자'} size={36} />
          <div className="row grow" style={{ gap: 6, minWidth: 0 }}>
            <span className="sidebar__name">{me.data?.login ?? '…'}</span>
            <span className="sidebar__plan">Free</span>
          </div>
          <Link to="/settings" className="sidebar__gear" aria-label="마이페이지"><Settings size={15} /></Link>
        </div>
      </aside>
      <div id="main" className="shell__main"><Outlet /></div>
      <nav className="mobile-nav" aria-label="모바일 주 메뉴">
        {MOBILE_MENU.map((item) => (
          <NavLink key={item.to} to={item.to} end={item.end}>
            <item.icon size={20} aria-hidden="true" />
            <span>{item.label}</span>
          </NavLink>
        ))}
      </nav>
      <JobWatcher />
    </div>
  );
}

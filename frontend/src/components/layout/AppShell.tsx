import { useEffect, useState } from 'react';
import { NavLink, Outlet, Link, useLocation, useNavigationType } from 'react-router-dom';
import { Home, FolderGit2, Layers, Github, UserRound, PanelLeft, BriefcaseBusiness, FileText } from 'lucide-react';
import { matchSupported } from '@/api/matching';
import { useActiveJobs } from '@/api/queries';
import { JobWatcher } from '@/lib/jobWatcher';
import { BrandLogo } from '@/components/ui/BrandLogo';
import { GithubBanner } from './GithubBanner';
import { ProfileMenu } from './ProfileMenu';
import { NoticeBar } from './NoticeBar';
import { useMatches } from '@/features/coverLetters/panel/useActiveQuestion';
import { ProfileSetupModal } from '@/features/profile/ProfileSetupModal';
import { LegalLinks } from '@/features/legal/LegalLinks';

const MENU = [
  { group: '커리어 관리', items: [
    { to: '/', label: '홈', icon: Home, end: true },
    { to: '/repos', label: '레포', icon: FolderGit2 },
    { to: '/cards', label: '경험 카드', icon: Layers },
    ...(matchSupported() ? [{ to: '/match', label: '공고 매칭', icon: BriefcaseBusiness }] : []),
    { to: '/cover-letters', label: '자소서', icon: FileText },
  ] },
  { group: '설정', items: [
    { to: '/settings/github', label: 'GitHub 연결', icon: Github },
    // 인적사항·탈퇴는 마이페이지 아래 화면이다. GitHub 연결은 따로 강조되므로 end 로 막고 하위 화면만 더한다
    { to: '/settings', label: '마이페이지', icon: UserRound, end: true, also: ['/settings/profile', '/settings/leave'] },
  ] },
];

/** 워드마크 — 사각 G 마크 + itory. 원본 PNG(투명 배경). 다크 서페이스 위에서는 invert. */
export const Wordmark = ({ height = 27, className }: { height?: number; className?: string }) => (
  <BrandLogo height={height} className={className} />
);
const Mark = () => <BrandLogo height={32} mark />;

const RAIL_KEY = 'gitory.rail';
const MOBILE_MENU = [
  { to: '/', label: '홈', icon: Home, end: true },
  { to: '/repos', label: '레포', icon: FolderGit2 },
  { to: '/cards', label: '카드', icon: Layers },
  ...(matchSupported() ? [{ to: '/match', label: '매칭', icon: BriefcaseBusiness }] : []),
  { to: '/cover-letters', label: '자소서', icon: FileText },
  { to: '/settings', label: '마이페이지', icon: UserRound },
];

/** 앱 셸 — 접히는 레일 사이드바(브랜드 · 메뉴 그룹 · 프로필). 접힘 상태는 기억한다. */
export function AppShell() {

  const active = useActiveJobs();
  // 휴대폰에서는 위쪽 줄에, 넓은 화면에서는 본문 맨 위 줄에 한 번만 그린다
  const phone = useMatches('(max-width: 767px)');
  const { pathname } = useLocation();
  const navType = useNavigationType();
  // 메뉴·링크로 새 화면에 가면 맨 위에서 시작한다. 뒤로·앞으로(POP)는 브라우저가 둔 자리를 그대로 쓴다
  useEffect(() => { if (navType !== 'POP') window.scrollTo(0, 0); }, [pathname, navType]);
  const [rail, setRail] = useState<boolean>(() => { try { return localStorage.getItem(RAIL_KEY) === '1'; } catch { return false; } });
  useEffect(() => { try { localStorage.setItem(RAIL_KEY, rail ? '1' : '0'); } catch { /* noop */ } }, [rail]);

  return (
    <div className={`shell ${rail ? 'shell--rail' : ''}`}>
      <a href="#main" className="skip-link">본문으로 건너뛰기</a>
      <header className="mobile-brand">
        <Link to="/" aria-label="Gitory 홈"><Wordmark height={25} /></Link>
        {phone && <NoticeBar jobs={active.data?.jobs} />}
        <ProfileMenu variant="mobile" />
      </header>
      <aside className="sidebar" aria-label="주 메뉴">
        <div className="sidebar__top">
          <Link to="/" className="brand" aria-label="Gitory 홈">{rail ? <Mark /> : <Wordmark height={27} />}</Link>
          <button type="button" className="sidebar__toggle sidebar__toggle--edge" onClick={() => setRail(value => !value)} aria-label={rail ? '사이드바 펼치기' : '사이드바 접기'} title={rail ? '사이드바 펼치기' : '사이드바 접기'} aria-expanded={!rail}><PanelLeft size={16} /></button>
        </div>
        {MENU.map((g) => (
          <nav key={g.group} className="menu-group" aria-label={g.group}>
            {g.items.map((it) => (
              'also' in it && it.also?.some((p) => pathname.startsWith(p))
                ? <Link key={it.to} to={it.to} className="menu-item" title={it.label} aria-label={it.label} aria-current="page"><it.icon size={18} /><span>{it.label}</span></Link>
                : <NavLink key={it.to} to={it.to} end={it.end} className="menu-item" title={it.label} aria-label={it.label}>
                  <it.icon size={18} /><span>{it.label}</span>
                </NavLink>
            ))}
          </nav>
        ))}
        <div style={{ marginTop: 'auto' }} />
        <LegalLinks className="sidebar__legal" />
        <div className="sidebar__profile"><ProfileMenu variant="sidebar" /></div>
      </aside>
      <div id="main" className="shell__main">
        {!phone && <div className="notice-slot"><NoticeBar jobs={active.data?.jobs} /></div>}
        <GithubBanner /><Outlet context={active} />
      </div>
      <nav className="mobile-nav" aria-label="모바일 주 메뉴" style={{ gridTemplateColumns: `repeat(${MOBILE_MENU.length}, minmax(0, 1fr))` }}>
        {MOBILE_MENU.map((item) => (
          <NavLink key={item.to} to={item.to} end={item.end}>
            <item.icon size={20} aria-hidden="true" />
            <span>{item.label}</span>
          </NavLink>
        ))}
      </nav>
      <JobWatcher active={active} />
      <ProfileSetupModal />
    </div>
  );
}

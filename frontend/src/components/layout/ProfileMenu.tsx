import { useEffect, useId, useLayoutEffect, useRef, useState, type CSSProperties, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { createPortal } from 'react-dom';
import { Link, useNavigate } from 'react-router-dom';
import { ChevronsUpDown, LogOut, Moon, Sun, UserRound } from 'lucide-react';
import { useLogout, useMe } from '@/api/queries';
import { UserAvatar } from '@/components/ui/UserAvatar';
import { getTheme, setTheme, type Theme } from '@/lib/theme';

/** 메뉴와 눌린 버튼 사이, 화면 가장자리에서 띄우는 간격(px)과 메뉴의 최소 폭 */
const GAP = 6;
const EDGE = 8;
const POP_MIN = 200;
/** 접힌 사이드바에서는 사이드바 경계(버튼 오른쪽에서 조금 더 간다)에서 떨어뜨려 본문 위에 분명히 뜨게 한다 */
const RAIL_GAP = 14;
/** 이보다 좁은 프로필 버튼은 접힌 사이드바(아바타만 남음)다 */
const RAIL_MAX = 120;

/**
 * 메뉴 자리 — 사이드바는 가로로 넘치는 것을 가려서(overflow-x: hidden) 메뉴를 그 안에 두면 접힌 사이드바에서 잘린다.
 * 그래서 메뉴를 body 로 꺼내 화면 기준(fixed)으로 둔다. 접힌 사이드바는 아바타 오른쪽, 펼친 사이드바는 버튼 위, 모바일은 버튼 아래다.
 */
export function placeProfileMenu(variant: 'sidebar' | 'mobile', trigger: DOMRect, viewport: { width: number; height: number }): CSSProperties {
  if (variant === 'mobile') return { top: trigger.bottom + GAP, right: Math.max(EDGE, viewport.width - trigger.right) };
  if (trigger.width < RAIL_MAX) {
    return { left: Math.min(trigger.right + RAIL_GAP, viewport.width - POP_MIN - EDGE), bottom: Math.max(EDGE, viewport.height - trigger.bottom), width: POP_MIN };
  }
  return { left: trigger.left, bottom: Math.max(EDGE, viewport.height - trigger.top + GAP), width: Math.max(trigger.width, POP_MIN) };
}

/**
 * 프로필 메뉴 — 로그아웃이 마이페이지 구석에만 있어 찾기 어려웠다(#438). 프로필을 누르면 바로 보이게 한다.
 * 테마 전환도 여기서 한 번에. 바깥을 누르거나 Esc 를 누르면 닫히고, 닫히면 초점은 프로필 버튼으로 돌아온다.
 * 메뉴는 body 에 그려서 사이드바에 잘리지 않고 본문 위에 뜬다. 열면 첫 항목으로 초점이 가고, 위·아래 화살표로 옮기고, Tab 은 메뉴를 닫는다.
 */
export function ProfileMenu({ variant }: { variant: 'sidebar' | 'mobile' }) {
  const me = useMe();
  const logout = useLogout();
  const nav = useNavigate();
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState<CSSProperties | null>(null);
  const [theme, setT] = useState<Theme>(getTheme());
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const pop = useRef<HTMLDivElement>(null);
  const menuId = useId();
  const login = me.data?.login ?? '사용자';

  const close = (focusTrigger = false) => { setOpen(false); setPos(null); if (focusTrigger) trigger.current?.focus(); };

  useLayoutEffect(() => {
    if (!open) return;
    const place = () => {
      const el = trigger.current;
      if (el) setPos(placeProfileMenu(variant, el.getBoundingClientRect(), { width: window.innerWidth, height: window.innerHeight }));
    };
    place();
    window.addEventListener('resize', place);
    window.addEventListener('scroll', place, true);
    return () => { window.removeEventListener('resize', place); window.removeEventListener('scroll', place, true); };
  }, [open, variant]);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => {
      const target = e.target as Node;
      if (!root.current?.contains(target) && !pop.current?.contains(target)) close();
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(true); };
    document.addEventListener('pointerdown', onDown);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('pointerdown', onDown); document.removeEventListener('keydown', onKey); };
  }, [open]);

  // 처음 자리를 잡으면 첫 항목으로 초점을 옮긴다(메뉴가 body 끝에 있어 Tab 으로는 닿지 않는다)
  const placed = pos !== null;
  useEffect(() => {
    if (open && placed) pop.current?.querySelector<HTMLElement>('[role="menuitem"]:not(:disabled)')?.focus();
  }, [open, placed]);

  const onMenuKey = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'Tab') { e.preventDefault(); close(true); return; }
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp' && e.key !== 'Home' && e.key !== 'End') return;
    const items = Array.from(pop.current?.querySelectorAll<HTMLElement>('[role="menuitem"]:not(:disabled)') ?? []);
    if (items.length === 0) return;
    e.preventDefault();
    const at = items.indexOf(document.activeElement as HTMLElement);
    const next = e.key === 'Home' ? 0 : e.key === 'End' ? items.length - 1 : e.key === 'ArrowDown' ? (at + 1) % items.length : (at <= 0 ? items.length - 1 : at - 1);
    items[next].focus();
  };

  const toggleTheme = () => { const next = theme === 'dark' ? 'light' : 'dark'; setTheme(next); setT(next); };
  const signOut = async () => { await logout.mutateAsync(); nav('/login'); };

  return (
    <div ref={root} className={`profile-menu profile-menu--${variant}`}>
      <button ref={trigger} type="button" className="profile-menu__trigger" aria-haspopup="menu" aria-expanded={open} aria-controls={menuId}
        aria-label={`${login} 계정 메뉴`} onClick={() => (open ? close() : setOpen(true))}>
        <UserAvatar src={me.data?.avatarUrl} login={login} size={variant === 'sidebar' ? 36 : 28} />
        <span className="profile-menu__name">{me.data?.login ?? '…'}</span>
        {variant === 'sidebar' && <ChevronsUpDown size={15} className="profile-menu__chev" aria-hidden />}
      </button>
      {open && pos && createPortal(
        <div ref={pop} id={menuId} role="menu" className="profile-menu__pop" style={pos} onKeyDown={onMenuKey}>
          <Link role="menuitem" to="/settings" className="profile-menu__item" onClick={() => close()}><UserRound size={16} aria-hidden />마이페이지</Link>
          <button role="menuitem" type="button" className="profile-menu__item" onClick={toggleTheme}>
            {theme === 'dark' ? <Sun size={16} aria-hidden /> : <Moon size={16} aria-hidden />}{theme === 'dark' ? '라이트 모드로' : '다크 모드로'}
          </button>
          <div className="profile-menu__sep" role="separator" />
          <button role="menuitem" type="button" className="profile-menu__item" disabled={logout.isPending} onClick={() => void signOut()}>
            <LogOut size={16} aria-hidden />{logout.isPending ? '로그아웃하는 중' : '로그아웃'}
          </button>
        </div>,
        document.body,
      )}
    </div>
  );
}

import { useEffect, useId, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ChevronsUpDown, LogOut, Moon, Sun, UserRound } from 'lucide-react';
import { useLogout, useMe } from '@/api/queries';
import { UserAvatar } from '@/components/ui/UserAvatar';
import { getTheme, setTheme, type Theme } from '@/lib/theme';

/**
 * 프로필 메뉴 — 로그아웃이 마이페이지 구석에만 있어 찾기 어려웠다(#438). 프로필을 누르면 바로 보이게 한다.
 * 테마 전환도 여기서 한 번에. 바깥을 누르거나 Esc 를 누르면 닫히고, 닫히면 초점은 프로필 버튼으로 돌아온다.
 */
export function ProfileMenu({ variant }: { variant: 'sidebar' | 'mobile' }) {
  const me = useMe();
  const logout = useLogout();
  const nav = useNavigate();
  const [open, setOpen] = useState(false);
  const [theme, setT] = useState<Theme>(getTheme());
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const menuId = useId();
  const login = me.data?.login ?? '사용자';

  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => { if (!root.current?.contains(e.target as Node)) setOpen(false); };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { setOpen(false); trigger.current?.focus(); } };
    document.addEventListener('pointerdown', onDown);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('pointerdown', onDown); document.removeEventListener('keydown', onKey); };
  }, [open]);

  const toggleTheme = () => { const next = theme === 'dark' ? 'light' : 'dark'; setTheme(next); setT(next); };
  const signOut = async () => { await logout.mutateAsync(); nav('/login'); };

  return (
    <div ref={root} className={`profile-menu profile-menu--${variant}`}>
      <button ref={trigger} type="button" className="profile-menu__trigger" aria-haspopup="menu" aria-expanded={open} aria-controls={menuId}
        aria-label={`${login} 계정 메뉴`} onClick={() => setOpen((x) => !x)}>
        <UserAvatar src={me.data?.avatarUrl} login={login} size={variant === 'sidebar' ? 36 : 28} />
        <span className="profile-menu__name">{me.data?.login ?? '…'}</span>
        {variant === 'sidebar' && <ChevronsUpDown size={15} className="profile-menu__chev" aria-hidden />}
      </button>
      {open && (
        <div id={menuId} role="menu" className="profile-menu__pop">
          <Link role="menuitem" to="/settings" className="profile-menu__item" onClick={() => setOpen(false)}><UserRound size={16} aria-hidden />마이페이지</Link>
          <button role="menuitem" type="button" className="profile-menu__item" onClick={toggleTheme}>
            {theme === 'dark' ? <Sun size={16} aria-hidden /> : <Moon size={16} aria-hidden />}{theme === 'dark' ? '라이트 모드로' : '다크 모드로'}
          </button>
          <div className="profile-menu__sep" role="separator" />
          <button role="menuitem" type="button" className="profile-menu__item profile-menu__item--danger" disabled={logout.isPending} onClick={() => void signOut()}>
            <LogOut size={16} aria-hidden />{logout.isPending ? '로그아웃하는 중' : '로그아웃'}
          </button>
        </div>
      )}
    </div>
  );
}

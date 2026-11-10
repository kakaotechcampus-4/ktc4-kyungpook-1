import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useLogout, useMe } from '@/api/queries';
import { Badge, Button, PageTitle, Skeleton } from '@/components/ui';
import { getTheme, setTheme, type Theme } from '@/lib/theme';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { UserAvatar } from '@/components/ui/UserAvatar';
import { profileSupported } from '@/api/capabilities';
import { ProfileSettingsRow } from '@/features/profile/ProfileSettingsRow';
import { useLogin } from '@/features/auth/useLogin';
import { COLLECTED, COVER_LETTER_COLLECTED, PROFILE_COLLECTED } from '@/lib/legal';
import { LegalLinks } from '@/features/legal/LegalLinks';

/** F2 마이페이지 — 내 정보, 설정, 내 데이터 순서로 묶는다. 지표·크레딧 UI 없음 (과금 단위 미정). */
export function MyPage() {
  useDocumentTitle('마이페이지');
  const me = useMe();
  const login = useLogin();
  const logout = useLogout();
  const nav = useNavigate();
  const [theme, setT] = useState<Theme>(getTheme());
  const pick = (t: Theme) => { setTheme(t); setT(t); };
  const github = login.isDemo ? { kind: 'NEUTRAL' as const, label: '샘플 계정' }
    : me.data?.github.connected ? { kind: 'CONFIRMED' as const, label: '연결됨' }
    : { kind: 'CAUTION' as const, label: '연결 끊김' };
  return (
    <main className="main settings-page set-page">
      <PageTitle>마이페이지</PageTitle>
      {!me.data ? <Skeleton h={90} /> : (
        <div className="set-who">
          <UserAvatar src={me.data.avatarUrl} login={me.data.login} size={64} />
          <div className="set-who__text">
            <span className="set-who__name">{me.data.login}</span>
            <span className="set-who__sub">GitHub 계정으로 로그인했어요</span>
          </div>
        </div>
      )}

      <section className="set-group">
        <h2>내 정보</h2>
        <div className="set-list">
          <ProfileSettingsRow />
          <Link to="/settings/github" className="set-row">
            <div className="set-row__text"><b>GitHub 연결</b><span>공개 레포만 읽어요</span></div>
            {me.data && <Badge kind={github.kind}>{github.label}</Badge>}
            <span className="set-row__go" aria-hidden="true">›</span>
          </Link>
        </div>
      </section>

      <section className="set-group">
        <h2>설정</h2>
        <div className="set-list">
          <div className="set-row">
            <div className="set-row__text"><b>화면 테마</b></div>
            <div className="row" style={{ gap: 8 }} role="radiogroup" aria-label="테마">
              {(['light', 'dark'] as Theme[]).map((t) => (
                <Button key={t} variant={theme === t ? 'primary' : 'secondary'} role="radio" aria-checked={theme === t} onClick={() => pick(t)}>{t === 'light' ? '라이트' : '다크'}</Button>
              ))}
            </div>
          </div>
        </div>
      </section>

      <section className="set-group">
        <h2>내 데이터</h2>
        <div className="set-list">
          <details className="set-details">
            <summary className="set-row">
              <div className="set-row__text"><b>저장과 보관 정보</b><span>어떤 정보를 왜 보관하는지 볼 수 있어요</span></div>
            </summary>
            <div className="set-details__body">
              {[...COLLECTED, ...COVER_LETTER_COLLECTED, ...(profileSupported() ? PROFILE_COLLECTED : [])].map(([name, why, keep]) => (
                <div key={name} className="kv settings-data-row">
                  <div className="settings-data-copy"><span className="w-regular">{name}</span><span className="set-data__why">{why}</span></div>
                  <Badge kind={keep === '보관' ? 'NEUTRAL' : 'CAUTION'}>{keep}</Badge>
                </div>
              ))}
            </div>
          </details>
          <Link to="/settings/leave" className="set-row">
            <div className="set-row__text"><b>탈퇴와 데이터 삭제</b><span>계정과 모든 정보를 지워요</span></div>
            <span className="set-row__go" aria-hidden="true">›</span>
          </Link>
        </div>
      </section>

      <div className="set-group">
        <Button variant="secondary" className="set-logout" loading={logout.isPending} onClick={async () => { await logout.mutateAsync(); nav('/login'); }}>로그아웃</Button>
      </div>
      <LegalLinks report />
    </main>
  );
}

import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useLogout, useMe } from '@/api/queries';
import { Badge, Button, PageTitle, Skeleton } from '@/components/ui';
import { getTheme, setTheme, type Theme } from '@/lib/theme';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { UserAvatar } from '@/components/ui/UserAvatar';
import { profileSupported } from '@/api/capabilities';
import { ProfileSettingsRow } from '@/features/profile/ProfileSettingsRow';
import { COLLECTED, COVER_LETTER_COLLECTED, PROFILE_COLLECTED } from '@/lib/legal';
import { LegalLinks } from '@/features/legal/LegalLinks';

/** F2 마이페이지 — 수집 항목 투명 공개. 지표·크레딧 UI 없음 (과금 단위 미정). */
export function MyPage() {
  useDocumentTitle('마이페이지');
  const me = useMe();
  const logout = useLogout();
  const nav = useNavigate();
  const [theme, setT] = useState<Theme>(getTheme());
  const s = me.data?.stats;
  const pick = (t: Theme) => { setTheme(t); setT(t); };
  return (
    <main className="main settings-page">
      <PageTitle>마이페이지</PageTitle>
      {!me.data ? <Skeleton h={90} /> : (
        <div className="profile-summary">
          <UserAvatar src={me.data.avatarUrl} login={me.data.login} size={64} />
          <div className="stack grow" style={{ gap: 5 }}>
            <span className="w-600" style={{ fontSize: 20 }}>{me.data.login}</span>
          </div>
        </div>
      )}
      <section className="settings-section stack" style={{ gap: 14 }}>
        <div className="row"><span className="w-600" style={{ fontSize: 14 }}>화면 테마</span></div>
        <div className="row" style={{ gap: 8 }} role="radiogroup" aria-label="테마">
          {(['light', 'dark'] as Theme[]).map((t) => (
            <Button key={t} variant={theme === t ? 'primary' : 'outline'} size="sm" role="radio" aria-checked={theme === t} onClick={() => pick(t)}>{t === 'light' ? '라이트' : '다크'}</Button>
          ))}
        </div>
      </section>
      <section className="settings-section settings-links stack">
        <ProfileSettingsRow />
        <Link to="/settings/github" className="kv" style={{ padding: '14px 0' }}>
          <div className="stack grow" style={{ gap: 3 }}><span className="w-600" style={{ fontSize: 14 }}>GitHub 연결 관리</span><span className="t-12 c-2">권한 확인, 다시 연결, 연결 해제</span></div>
          <span className="c-3" style={{ fontSize: 16 }}>›</span>
        </Link>
        <Link to="/settings/leave" className="kv" style={{ padding: '14px 0' }}>
          <div className="stack grow" style={{ gap: 3 }}><span className="w-600" style={{ fontSize: 14 }}>탈퇴와 데이터 삭제</span><span className="t-12 c-2">탈퇴하고 정보 지우기</span></div>
          <span className="c-3" style={{ fontSize: 16 }}>›</span>
        </Link>
        <div className="row" style={{ padding: '16px 0 6px' }}><Button variant="text" loading={logout.isPending} onClick={async () => { await logout.mutateAsync(); nav('/login'); }}>로그아웃</Button></div>
      </section>
      <section className="settings-section stat-grid">
        {[['확정한 카드', s ? `${s.confirmedCards}장` : '—'], ['정리한 레포', s ? `${s.analyzedRepos}개` : '—'], ['작성한 답변', s ? `${s.interviewTurns}개` : '—'], ['남은 후보', s ? `${s.remainingCandidates}개` : '—']].map(([k, v]) => (
          <div key={k} className="stat"><span className="t-12 c-2">{k}</span><b>{v}</b></div>
        ))}
      </section>
      <details className="settings-section settings-storage">
        <summary>저장과 보관 정보</summary>
        <div className="stack" style={{ gap: 12, marginTop: 16 }}>
          {[...COLLECTED, ...COVER_LETTER_COLLECTED, ...(profileSupported() ? PROFILE_COLLECTED : [])].map(([name, why, keep]) => (
            <div key={name} className="kv settings-data-row">
              <div className="settings-data-copy"><span className="w-500">{name}</span><span className="t-12 c-2">{why}</span></div>
              <Badge kind={keep === '보관' ? 'NEUTRAL' : 'CAUTION'}>{keep}</Badge>
            </div>
          ))}
        </div>
      </details>
      <LegalLinks report />
    </main>
  );
}

import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useLogout, useMe } from '@/api/queries';
import { Badge, Button, PageTitle, Skeleton } from '@/components/ui';
import { getTheme, setTheme, type Theme } from '@/lib/theme';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { UserAvatar } from '@/components/ui/UserAvatar';

const COLLECTED = [
  ['GitHub 아이디 · 아바타', '로그인 식별', '보관'],
  ['공개 저장소 원본 (커밋·PR·리뷰)', '재현성 · 근거 검증', '보관'],
  ['확정한 경험 카드 · 버전', '서비스 핵심 데이터', '보관'],
  ['되묻기 답변 원문', '정성 카드의 근거', '보관'],
  ['GitHub 액세스 토큰', '수집에만 사용', '탈퇴 시 즉시 파기'],
] as const;

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
      <PageTitle right="Free Plan">마이페이지</PageTitle>
      {!me.data ? <Skeleton h={90} /> : (
        <div className="profile-summary">
          <UserAvatar src={me.data.avatarUrl} login={me.data.login} size={64} />
          <div className="stack grow" style={{ gap: 5 }}>
            <span className="w-600" style={{ fontSize: 18 }}>{me.data.login}</span>
          </div>
          <Badge kind="NEUTRAL">Free Plan</Badge>
        </div>
      )}
      <section className="settings-section stack">
        <div className="row" style={{ marginBottom: 6 }}><span className="w-700" style={{ fontSize: 14.5 }}>우리가 가지고 있는 것</span><span className="right t-12 c-3">전화번호·학번·실명은 받지 않아요</span></div>
        {COLLECTED.map(([name, why, keep]) => (
          <div key={name} className="kv">
            <span className="w-500" style={{ width: 340 }}>{name}</span>
            <span className="t-12 c-2" style={{ width: 260 }}>{why}</span>
            <span className="right"><Badge kind={keep === '보관' ? 'NEUTRAL' : 'CAUTION'}>{keep}</Badge></span>
          </div>
        ))}
      </section>
      <section className="settings-section stat-grid">
        {[['확정한 카드', s ? `${s.confirmedCards}장` : '-'], ['정리한 레포', s ? `${s.analyzedRepos}개` : '-'], ['되묻기 턴', s ? `${s.interviewTurns}회` : '-'], ['남은 후보', s ? `${s.remainingCandidates}개` : '-']].map(([k, v]) => (
          <div key={k} className="stat"><span className="t-12 c-2">{k}</span><b>{v}</b></div>
        ))}
      </section>
      <section className="settings-section stack" style={{ gap: 14 }}>
        <div className="row"><span className="w-700" style={{ fontSize: 14.5 }}>화면 테마</span><span className="right t-12 c-3">기본은 라이트 · OS 설정을 따라가지 않습니다</span></div>
        <div className="row" style={{ gap: 8 }} role="radiogroup" aria-label="테마">
          {(['light', 'dark'] as Theme[]).map((t) => (
            <Button key={t} variant={theme === t ? 'primary' : 'outline'} size="sm" role="radio" aria-checked={theme === t} onClick={() => pick(t)}>{t === 'light' ? '라이트' : '다크'}</Button>
          ))}
        </div>
      </section>
      <section className="settings-section settings-links stack">
        {[['/settings/github', 'GitHub 연결 관리', '스코프 · 재연동 · 해제'], ['/settings/leave', '탈퇴 · 데이터 삭제', '정책 확정 전입니다']].map(([to, t, d]) => (
          <Link key={to} to={to} className="kv" style={{ padding: '14px 0' }}>
            <div className="stack grow" style={{ gap: 3 }}><span className="w-600" style={{ fontSize: 13.5 }}>{t}</span><span className="t-12 c-2">{d}</span></div>
            <span className="c-3" style={{ fontSize: 16 }}>›</span>
          </Link>
        ))}
      </section>
      <div className="row"><Button variant="text" loading={logout.isPending} onClick={async () => { await logout.mutateAsync(); nav('/login'); }}>로그아웃</Button></div>
    </main>
  );
}

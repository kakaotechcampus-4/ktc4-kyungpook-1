import { useState } from 'react';
import { useDisconnectGithub, useMe, useRepos } from '@/api/queries';
import { endpoints } from '@/api/endpoints';
import { Badge, Button, Note, PageTitle, Skeleton } from '@/components/ui';
import { ConfirmActionDialog } from '@/features/cards/CardDialogs';
import { ymd, ymdhm } from '@/lib/format';
import { toast } from '@/lib/toast';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { UserAvatar } from '@/components/ui/UserAvatar';

const SCOPES = [
  { scope: 'read:user', title: '프로필 읽기', desc: '아이디 · 아바타', granted: true },
  { scope: 'repo (private 포함)', title: '비공개 저장소 읽기', desc: '요청하지 않음 — 스키마에서 저장 자체를 막습니다', granted: false },
];

/** F1 GitHub 연결 관리 — 스코프 표시 · 재연동 · 해제. 쓰기 권한은 요청하지 않는다. */
export function GithubSettingsPage() {
  useDocumentTitle('GitHub 연결');
  const me = useMe();
  const repos = useRepos();
  const disconnect = useDisconnectGithub();
  const [asking, setAsking] = useState(false);
  const gh = me.data?.github;
  const analyzed = (repos.data ?? []).filter((r) => r.lastAnalyzedAt);

  return (
    <main className="main settings-page">
      <PageTitle right="읽기 전용">GitHub 연결</PageTitle>
      {!me.data ? <Skeleton h={90} /> : (
        <div className="profile-summary" style={{ flexWrap: 'wrap' }}>
          <UserAvatar src={me.data.avatarUrl} login={me.data.login} size={48} />
          <div className="stack grow" style={{ gap: 6 }}>
            <div className="row" style={{ gap: 8 }}><span className="w-600" style={{ fontSize: 18 }}>{me.data.login}</span><Badge kind={gh?.connected ? 'CONFIRMED' : 'NEUTRAL'}>{gh?.connected ? '연결됨' : '연결 안 됨'}</Badge></div>
            <span className="t-12l c-2">{gh?.connectedAt ? `${ymd(gh.connectedAt)} 연결` : '연결되어 있지 않습니다 — 새 분석을 하려면 다시 연결하세요'}{gh?.lastCollectedAt ? ` · 마지막 수집 ${ymdhm(gh.lastCollectedAt)}` : ''}</span>
          </div>
          <a href={endpoints.githubStartUrl()} className={`btn ${gh?.connected ? 'btn--outline' : 'btn--primary'}`}>{gh?.connected ? '재연동' : '연결하기'}</a>
        </div>
      )}
      {gh && !gh.connected && <Note strong="연결 해제됨 — 새 정리는 다시 연결 후" tone="inset" />}

      <section className="settings-section stack" style={{ gap: 4 }}>
        <div className="row"><span className="w-700" style={{ fontSize: 14.5 }}>허용한 권한</span><span className="right t-12 c-3">쓰기 권한은 요청하지 않습니다</span></div>
        {SCOPES.map((s) => (
          <div key={s.scope} className={`settings-row ${s.granted ? '' : 'settings-row--muted'}`}>
            <Badge kind={s.granted ? 'PR' : 'NEUTRAL'}>{s.scope}</Badge>
            <div className="stack grow" style={{ gap: 3 }}><span className="w-600" style={{ fontSize: 13, color: s.granted ? undefined : 'var(--text-tertiary)' }}>{s.title}</span><span className="t-12 c-2">{s.desc}</span></div>
            <Badge kind="NEUTRAL">{s.granted ? '허용' : '미요청'}</Badge>
          </div>
        ))}
      </section>

      <section className="settings-section stack">
        <span className="w-700" style={{ fontSize: 14.5, marginBottom: 6 }}>수집 이력</span>
        {analyzed.length === 0 && <span className="t-12l c-3">아직 정리한 레포가 없습니다.</span>}
        {analyzed.map((r) => (
          <div key={r.id} className="kv">
            <span className="w-600" style={{ width: 160 }}>{r.name}</span>
            <span className="t-12 c-2" style={{ width: 280 }}>커밋 {r.contribution.mine} · PR {r.prCount} · 리뷰 {r.reviewCount}</span>
            <span className="t-12 c-3">{r.lastAnalyzedAt && ymdhm(r.lastAnalyzedAt)}</span>
            <span className="right t-12 w-500" style={{ color: 'var(--text-strong)' }}>{r.candidateCount === 0 ? '후보 0 (EMPTY)' : `후보 ${r.candidateCount ?? '-'} · 카드 ${r.cardCount}`}</span>
          </div>
        ))}
      </section>

      <div className="row" style={{ gap: 14, padding: '18px 20px', borderRadius: 12, background: 'var(--state-failed-bg)', flexWrap: 'wrap' }}>
        <div className="stack grow" style={{ gap: 4 }}>
          <span className="w-600" style={{ fontSize: 14, color: 'var(--state-failed-text)' }}>연결 해제</span>
          <span className="t-12l c-2">토큰 즉시 파기 · 카드는 남음</span>
        </div>
        <Button variant="danger" disabled={!gh?.connected} onClick={() => setAsking(true)}>연결 해제</Button>
      </div>

      {asking && (
        <ConfirmActionDialog title="GitHub 연결을 해제할까요?" sub="토큰은 유예 없이 즉시 파기됩니다." actionLabel="연결 해제" danger loading={disconnect.isPending}
          onClose={() => setAsking(false)}
          onConfirm={async () => { await disconnect.mutateAsync(); track('github_disconnected'); toast('GitHub 연결을 해제했습니다. 토큰은 파기됐습니다.'); setAsking(false); }}
          body={<Note strong="카드는 남고, 토큰만 파기됩니다" tone="inset" />} />
      )}
    </main>
  );
}

import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useDisconnectGithub, useMe, useRepos } from '@/api/queries';
import { endpoints } from '@/api/endpoints';
import { Badge, Button, Note, PageTitle, Skeleton } from '@/components/ui';
import { ConfirmActionDialog } from '@/features/cards/CardDialogs';
import { koMd, koYmd } from '@/lib/format';
import { toast } from '@/lib/toast';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { UserAvatar } from '@/components/ui/UserAvatar';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { useLogin } from '@/features/auth/useLogin';

const READS = ['아이디와 프로필 사진', '공개 레포의 커밋, PR, 이슈, 리뷰', '카드로 고른 커밋의 코드 변경 부분'];
const NOT_READS = ['비공개 레포', '기본 브랜치가 아닌 브랜치', '저장소 파일 전체 내용'];

/** F1 GitHub 연결 — 읽는 범위 안내 · 다시 연결 · 해제. 쓰기 권한은 요청하지 않는다. */
export function GithubSettingsPage() {
  useDocumentTitle('GitHub 연결');
  const me = useMe();
  const login = useLogin();
  const repos = useRepos();
  const disconnect = useDisconnectGithub();
  const [asking, setAsking] = useState(false);
  const gh = login.isDemo ? undefined : me.data?.github;
  const analyzed = (repos.data ?? []).filter((r) => r.lastAnalyzedAt);
  const lastRead = analyzed.reduce<string | null>((latest, r) => (!latest || new Date(r.lastAnalyzedAt!) > new Date(latest) ? r.lastAnalyzedAt! : latest), null);
  const reconnect = <a href={endpoints.githubStartUrl()} className={`btn ${gh?.connected ? 'btn--outline' : 'btn--primary'}`}>다시 연결</a>;

  return (
    <main className="main settings-page set-page">
      <PageTitle>GitHub 연결</PageTitle>
      {!me.data ? <Skeleton h={90} /> : (
        <div className="set-card set-status">
          <UserAvatar src={me.data.avatarUrl} login={me.data.login} size={48} />
          <div className="set-status__text">
            <div className="row" style={{ gap: 8 }}>
              <span className="set-who__name" style={{ fontSize: 19 }}>{me.data.login}</span>
              <Badge kind={login.isDemo ? 'NEUTRAL' : gh?.connected ? 'CONFIRMED' : 'CAUTION'}>{login.isDemo ? '샘플 계정' : gh?.connected ? '연결됨' : '연결 끊김'}</Badge>
            </div>
            {login.isDemo && <span className="set-who__sub">샘플 데이터로 연결 관리 화면을 둘러보고 있어요.</span>}
            {gh?.connected && gh.connectedAt && <span className="set-who__sub">{koYmd(gh.connectedAt)}에 연결했어요</span>}
            {gh && !gh.connected && <span className="set-who__sub">다시 연결하면 이어서 쓸 수 있어요</span>}
          </div>
          {gh && reconnect}
        </div>
      )}
      {login.isDemo && <Note strong="샘플 계정이라 실제 GitHub에는 연결되지 않아요" tone="inset" />}

      <section className="set-card">
        <h2>읽는 범위</h2>
        <p>레포를 정리할 때 아래만 읽어요.</p>
        <div className="set-scope">
          <div className="set-scope__yes"><h3>읽어요</h3><ul>{READS.map((t) => <li key={t}>{t}</li>)}</ul></div>
          <div className="set-scope__no"><h3>읽지 않아요</h3><ul>{NOT_READS.map((t) => <li key={t}>{t}</li>)}</ul></div>
        </div>
        <p className="set-note">쓰기 권한은 요청하지 않아요</p>
      </section>

      {repos.isError && <QueryFailure error={repos.error} retry={() => repos.refetch()} pending={repos.isFetching} />}
      {repos.isSuccess && (
        <div className="set-list">
          <Link to="/repos" className="set-row">
            <div className="set-row__text">
              {analyzed.length > 0 ? <><b>정리한 레포 {analyzed.length}개</b><span>마지막으로 읽은 날 {koMd(lastRead!)}</span></> : <b>아직 정리한 레포가 없어요</b>}
            </div>
            <span className="set-row__link">레포에서 보기</span>
            <span className="set-row__go" aria-hidden="true">›</span>
          </Link>
        </div>
      )}

      {!login.isDemo && <section className="set-card set-split">
        <div className="set-status__text">
          <h2>연결 해제</h2>
          <p>토큰은 바로 지우고, 만든 카드는 남아요.</p>
        </div>
        <Button variant="danger" disabled={!gh?.connected} onClick={() => setAsking(true)}>연결 해제</Button>
      </section>}

      {!login.isDemo && asking && (
        <ConfirmActionDialog title="GitHub 연결을 해제할까요?" sub="토큰은 바로 지워요." actionLabel="연결 해제" danger loading={disconnect.isPending}
          onClose={() => setAsking(false)}
          onConfirm={async () => { await disconnect.mutateAsync(); track('github_disconnected'); toast('GitHub 연결을 해제했어요. 토큰은 지워졌어요.'); setAsking(false); }}
          body={<Note strong="카드는 남고, 토큰만 지워져요" tone="inset" />} />
      )}
    </main>
  );
}

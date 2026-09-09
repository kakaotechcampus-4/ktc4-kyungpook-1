import { useEffect } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useJob, useRepo, useStartAnalysis } from '@/api/queries';
import { endpoints } from '@/api/endpoints';
import { Badge, Breadcrumb, Button, PageTitle, RepoContext, Skeleton, Track } from '@/components/ui';
import { eta, minutes } from '@/lib/format';
import { jobErrorTitle } from '@/lib/labels';
import { isTerminal } from '@/api/schemas';
import { unwatchJob, watchJob } from '@/lib/jobWatcher';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

/** B3 분석 진행 · B4 수집 실패(E-1) · B5 요청 한도(E-2 partial) */
export function AnalyzePage() {
  const { repoId = '' } = useParams();
  const [sp] = useSearchParams();
  const jobId = sp.get('job') ?? undefined;
  const repo = useRepo(repoId);
  const job = useJob(jobId);
  const nav = useNavigate();
  const restart = useStartAnalysis();
  useDocumentTitle(repo.data ? `${repo.data.name} 정리 중` : '정리 중');

  // 사라진 Job (새로고침 · 서버 재시작) → 이미 끝난 것으로 보고 보드로 보낸다. 빈 진행 화면에 갇히지 않게.
  useEffect(() => {
    if (job.isError) nav(`/repos/${repoId}/candidates`, { replace: true });
  }, [job.isError, nav, repoId]);

  // 이 화면이 보고 있는 Job 은 전역 알림에서 뺀다. 성공 → 후보 보드로 (후보 0개도 보드가 EMPTY 판정을 보여준다)
  useEffect(() => {
    if (!job.data || !jobId || !isTerminal(job.data.state)) return;
    unwatchJob(jobId);
    if (job.data.state === 'SUCCEEDED') {
      const t = setTimeout(() => nav(`/repos/${repoId}/candidates`, { replace: true }), 600);
      return () => clearTimeout(t);
    }
  }, [job.data, jobId, nav, repoId]);

  const name = repo.data ? `${repo.data.owner} / ${repo.data.name}` : '…';
  const crumbs = [{ label: '경험정리/홈', to: '/' }, { label: repo.data?.name ?? '…', to: '/repos' }];
  const retry = async () => {
    const { jobId: j } = await restart.mutateAsync(repoId);
    track('analysis_started', { repoId, jobId: j, retry: true });
    watchJob({ jobId: j, type: 'ANALYZE', label: `${repo.data?.name ?? '레포'} 정리`, href: `/repos/${repoId}/candidates` });
    nav(`/repos/${repoId}/run?job=${j}`, { replace: true });
  };

  if (!jobId) {
    return (
      <main className="main">
        <Breadcrumb items={[...crumbs, { label: '정리' }]} />
        <PageTitle>정리를 시작할까요</PageTitle>
        <RepoContext name={name} note="진행 중인 작업이 없습니다." right={<Button onClick={retry} loading={restart.isPending}>정리 시작</Button>} />
      </main>
    );
  }

  const j = job.data;
  if (j?.state === 'FAILED' && j.error) {
    // B4 — E-1: 정리 시작 자체를 막는다. 부분 수집분으로 랭킹하지 않는다.
    return (
      <main className="main">
        <Breadcrumb items={[...crumbs, { label: '정리 실패' }]} />
        <PageTitle right="E-1">{jobErrorTitle[j.error.code]}</PageTitle>
        <div className="fail-card" role="alert">
          <span className="fail-card__bang">!</span>
          <div className="stack" style={{ gap: 6 }}>
            <span className="w-600" style={{ fontSize: 17, color: 'var(--state-failed-text)' }}>{j.error.message}</span>
            {j.error.progressNote && <span className="c-2" style={{ fontSize: 13.5, lineHeight: '21px' }}>{j.error.progressNote}</span>}
          </div>
        </div>
        <div className="card stack" style={{ gap: 10, padding: '18px 20px' }}>
          <span className="w-700" style={{ fontSize: 14 }}>무엇을 시도할 수 있나요</span>
          {[
            ['다시 시도', '일시적인 네트워크 오류일 수 있습니다. 읽은 곳부터 다시 시작합니다.', <Button key="a" size="sm" onClick={retry} loading={restart.isPending}>다시 시도</Button>],
            ['다른 저장소 고르기', '특정 레포에서만 반복되면 그 레포의 문제일 수 있습니다.', <Link key="b" to="/repos" className="btn btn--outline btn--sm">이동</Link>],
            ['직접 작성하기', 'GitHub 없이 STAR 템플릿에 바로 쓸 수 있습니다.', <Link key="c" to="/cards/new" className="btn btn--outline btn--sm">이동</Link>],
          ].map(([t, d, b], i) => (
            <div key={i} className="card card--paper row" style={{ gap: 14, padding: '12px 14px', border: 0 }}>
              <div className="stack grow" style={{ gap: 3 }}><span className="w-600" style={{ fontSize: 13 }}>{t}</span><span className="t-12 c-2">{d}</span></div>{b}
            </div>
          ))}
        </div>
      </main>
    );
  }

  if (j?.state === 'PARTIAL' && j.error) {
    // B5 — E-2: partial: true 를 명시. "이게 전부"라고 말하지 않는다.
    return (
      <main className="main main--tight">
        <Breadcrumb items={[...crumbs, { label: '후보 보드' }]} />
        <PageTitle right="E-2 · partial: true">부분 결과가 있습니다</PageTitle>
        <div className="card row" style={{ gap: 16, padding: '18px 20px', background: 'var(--state-partial-bg)', border: 0 }}>
          <Badge kind="NEUTRAL">부분 결과</Badge>
          <div className="stack grow" style={{ gap: 5 }}>
            <span className="w-600" style={{ fontSize: 15 }}>{jobErrorTitle[j.error.code]}</span>
            <span className="c-2" style={{ fontSize: 12.5, lineHeight: '20px' }}>{j.error.progressNote} {j.error.retryAfterSeconds && `약 ${minutes(j.error.retryAfterSeconds)} 뒤 이어서 읽을 수 있습니다.`}</span>
          </div>
          <Button variant="outline" size="sm" onClick={retry} loading={restart.isPending}>{j.error.retryAfterSeconds ? `${minutes(j.error.retryAfterSeconds)} 뒤 이어 읽기` : '이어 읽기'}</Button>
        </div>
        <div className="row" style={{ gap: 8 }}>
          <Button onClick={() => nav(`/repos/${repoId}/candidates`)}>읽은 범위의 후보 보기</Button>
          <Link to="/repos" className="btn btn--outline">다른 저장소</Link>
        </div>
      </main>
    );
  }

  // B3 — 진행. 누를 것이 없다. 떠나도 된다.
  return (
    <main className="main">
      <Breadcrumb items={[...crumbs, { label: '정리 중' }]} />
      <PageTitle right="떠나도 됩니다">읽고 있습니다</PageTitle>
      <RepoContext name={name} note={repo.data ? `커밋 ${repo.data.contribution.mine}개 · PR ${repo.data.prCount}건 · 리뷰 ${repo.data.reviewCount}건을 읽는 중입니다` : '…'} right={<Badge kind={j?.state === 'SUCCEEDED' ? 'CONFIRMED' : 'NEUTRAL'}>{j?.state === 'SUCCEEDED' ? '완료' : '진행 중'}</Badge>} />
      <div className="card stack" style={{ gap: 16, padding: '22px 24px' }}>
        {!j ? <Skeleton h={120} /> : (
          <>
            <div className="row" style={{ gap: 12 }}>
              <span className="w-700" style={{ fontSize: 14 }}>{j.stages.filter((s) => s.status === 'DONE').length} / {j.stages.length} 단계</span>
              <span className="right t-12l c-2">{j.state === 'SUCCEEDED' ? '완료 — 후보 보드로 이동합니다' : eta(j.etaSeconds)}</span>
            </div>
            <Track value={j.progress} label="분석 진행률" />
            <ol className="stages">
              {j.stages.map((s) => (
                <li key={s.key} className={`stage stage--${s.status}`}>
                  <span className="stage__icon" aria-hidden>{s.status === 'DONE' ? '✓' : ''}</span>
                  <div className="stack grow" style={{ gap: 3 }}>
                    <span className="stage__label">{s.label}</span>
                    <span className="stage__detail">{s.detail}</span>
                  </div>
                  <Badge kind={s.status === 'NOW' ? 'CONFIRMED' : 'NEUTRAL'}>{s.status === 'DONE' ? '완료' : s.status === 'NOW' ? '진행 중' : '대기'}</Badge>
                </li>
              ))}
            </ol>
          </>
        )}
      </div>
      <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
        <span className="c-2 t-14">끝나면 알려드릴게요</span>
        <div className="right row" style={{ gap: 8 }}>
          <Link to="/" className="btn btn--outline">다른 작업 하러 가기</Link>
          <Button variant="text" onClick={() => jobId && endpoints.cancelJob(jobId).then(() => nav('/repos'))}>분석 취소</Button>
        </div>
      </div>
    </main>
  );
}

import { Link, useParams } from 'react-router-dom';
import { CheckCircle2, CircleDashed } from 'lucide-react';
import { matchSupported } from '@/api/capabilities';
import { ApiError } from '@/api/client';
import { useMatch } from '@/api/queries';
import type { MatchTag } from '@/api/schemas';
import { Badge, Breadcrumb, EmptyState, IconBox, Note, PageTitle, Skeleton, StickyFooter } from '@/components/ui';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { ymd } from '@/lib/format';
import { cardKindLabel, starFieldShort } from '@/lib/labels';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { FeaturePending, FitBadge, SampleDataNote, daysUntil, safeHttpUrl } from './shared';

/** 기업 하나의 인재상별 근거 — 뒷받침하는 확정 카드 문장과, 근거가 없는 빈 칸을 함께 보여준다. */
export function MatchDetailPage() {
  const { matchId = '' } = useParams();
  const query = useMatch(matchId);
  const match = query.data;
  useDocumentTitle(match ? `${match.company} 매칭` : '기업·직무 매칭');
  if (!matchSupported()) return <FeaturePending />;

  const crumbs = [{ label: '경험정리/홈', to: '/' }, { label: '기업·직무 매칭', to: '/match' }, { label: match?.company ?? '…' }];
  if (query.isError && !match) {
    const gone = query.error instanceof ApiError && query.error.status === 404;
    return (
      <main className="main">
        <Breadcrumb items={crumbs} />
        {gone
          ? <EmptyState title="기업 정보를 찾을 수 없어요" desc="확인일이 지나 추천에서 빠졌거나 삭제된 정보예요."><Link to="/match" className="btn btn--outline">목록으로</Link></EmptyState>
          : <QueryFailure error={query.error} retry={() => query.refetch()} pending={query.isFetching} />}
      </main>
    );
  }
  if (!match) return <main className="main"><Breadcrumb items={crumbs} /><Skeleton h={40} w={320} /><Skeleton h={320} /></main>;

  const url = safeHttpUrl(match.source.url);
  const left = daysUntil(match.source.expiresAt);
  return (
    <main className="main main--footer main--tight">
      <Breadcrumb items={crumbs} />
      <PageTitle sub={<><FitBadge fit={match.fit} /><span className="t-12 c-3">{match.role}</span></>}>{match.company}</PageTitle>
      <Note strong={`인재상 ${match.tagCount}개 중 ${match.supportedTagCount}개를 확정한 카드가 뒷받침해요`} tone="inset">
        {match.summary}. 등급은 합격 가능성이 아니라 확정한 카드 근거가 인재상을 뒷받침하는 정도예요. 근거가 없는 인재상은 지어내지 않고 빈 칸으로 남겨요.
      </Note>
      <div className="card star-read">
        {match.tags.map((tag) => <TagRow key={tag.tag} tag={tag} />)}
      </div>
      <section className="card stack" style={{ gap: 6, padding: '16px 20px' }}>
        <span className="w-600" style={{ fontSize: 13 }}>공개 출처</span>
        {url
          ? <a href={url} target="_blank" rel="noopener noreferrer" className="t-12 c-2" style={{ overflowWrap: 'anywhere', textDecoration: 'underline' }}>{url}</a>
          : <span className="t-12 c-2" style={{ overflowWrap: 'anywhere' }}>{match.source.url}</span>}
        <span className="t-12 c-3">
          확인일 {ymd(match.source.verifiedAt)} · {ymd(match.source.expiresAt)} 이후에는 추천에서 빠져요
          {left <= 14 && <> <Badge kind="CAUTION">곧 만료</Badge></>}
        </span>
      </section>
      <SampleDataNote />
      <StickyFooter strong="이 기업 기준으로 초안을 만들어 볼까요?" sub="확정한 카드 문장만 사용해요">
        <Link to="/match" className="btn btn--outline">목록으로</Link>
        <Link to={`/cover-letter?match=${encodeURIComponent(match.id)}`} className="btn btn--primary btn--lg">자소서 초안 만들기</Link>
      </StickyFooter>
    </main>
  );
}

function TagRow({ tag }: { tag: MatchTag }) {
  const gap = tag.supports.length === 0;
  return (
    <div className={`star-read__row ${gap ? 'star-read__row--gap' : ''}`}>
      <div className="star-row__heading">
        <div className="star-row__label">
          <IconBox icon={gap ? CircleDashed : CheckCircle2} size={28} tone={gap ? 'subtle' : 'ink'} />
          <span className="star__name">{tag.tag}</span>
        </div>
        <div className="star-row__meta">
          <Badge kind={gap ? 'CAUTION' : 'NEUTRAL'}>{gap ? '근거 없음' : `카드 ${tag.supports.length}장`}</Badge>
        </div>
      </div>
      <div className="star-row__body">
        {gap ? (
          <>
            <p className="t-12l c-2">이 인재상을 뒷받침하는 확정 카드가 아직 없어요. 근거가 생기면 그때 채워져요.</p>
            <div><Link to="/cards/new" className="btn btn--outline btn--sm" aria-label={`${tag.tag} 근거를 직접 작성해서 채우기`}>직접 작성해서 채우기</Link></div>
          </>
        ) : tag.supports.map((support) => (
          <div key={support.cardId} className="stack" style={{ gap: 6 }}>
            <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
              <Badge kind="NEUTRAL">{cardKindLabel[support.cardKind]}</Badge>
              <Link to={`/cards/${support.cardId}`} className="w-600" style={{ fontSize: 13 }}>{support.cardTitle}</Link>
              <span className="t-12 c-3">{starFieldShort[support.field]} 칸의 문장</span>
            </div>
            <p className="star__text">{support.sentence}</p>
          </div>
        ))}
      </div>
    </div>
  );
}

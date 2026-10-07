import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Building2 } from 'lucide-react';
import { matchSupported } from '@/api/capabilities';
import { useCards, useMatches } from '@/api/queries';
import type { MatchTarget } from '@/api/schemas';
import { Badge, Chip, EmptyState, IconBox, Note, PageTitle, Skeleton } from '@/components/ui';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { SearchField } from '@/components/ui/SearchField';
import { Select } from '@/components/ui/Select';
import { ymd } from '@/lib/format';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { FeaturePending, FitBadge, NeedsConfirmedCards, SampleDataNote, daysUntil } from './shared';

type Sort = 'fit' | 'recent' | 'name';
const SORT_LABEL: Record<Sort, string> = { fit: '근거 많은 순', recent: '확인일 최신 순', name: '회사 이름 순' };
const ALL_ROLES = 'all';

/** 기업·직무 매칭 — 확정한 카드 근거가 각 기업의 인재상을 얼마나 뒷받침하는지 보여준다. */
export function MatchListPage() {
  useDocumentTitle('기업·직무 매칭');
  const supported = matchSupported();
  const matches = useMatches();
  const cards = useCards();
  const [q, setQ] = useState('');
  const [role, setRole] = useState(ALL_ROLES);
  const [sort, setSort] = useState<Sort>('fit');

  const confirmed = (cards.data ?? []).filter((card) => card.status === 'CONFIRMED').length;
  const roles = useMemo(() => [...new Set((matches.data ?? []).map((match) => match.role))], [matches.data]);
  const list = useMemo(() => {
    const needle = q.trim().toLowerCase();
    // 서버가 확인일이 지난 기업을 빼 주지만, 화면을 열어 둔 사이 만료된 기업까지 방어한다.
    const found = (matches.data ?? []).filter((match) =>
      Date.parse(match.source.expiresAt) > Date.now() && (role === ALL_ROLES || match.role === role)
      && (!needle || [match.company, match.role, match.summary, ...match.matchedTags].some((text) => text.toLowerCase().includes(needle))));
    // 서버가 이미 근거 등급 순으로 준다 — 'fit' 은 그 순서를 그대로 쓴다.
    if (sort === 'recent') return [...found].sort((a, b) => b.source.verifiedAt.localeCompare(a.source.verifiedAt));
    if (sort === 'name') return [...found].sort((a, b) => a.company.localeCompare(b.company));
    return found;
  }, [matches.data, q, role, sort]);

  if (!supported) return <FeaturePending />;
  const loading = matches.isPending || cards.isPending;

  return (
    <main className="main main--tight">
      <div className="list-head">
        <PageTitle>기업·직무 매칭 <span className="c-3 t-14">{matches.data?.length ?? 0}</span></PageTitle>
        <div className="right">
          <SearchField className="list-search" value={q} onChange={setQ} placeholder="회사·직무·인재상 검색" />
          <Select value={role} onChange={setRole} label="직무"
            options={[{ value: ALL_ROLES, label: '전체 직무' }, ...roles.map((value) => ({ value, label: value }))]} />
          <Select value={sort} onChange={setSort} label="정렬" options={(Object.keys(SORT_LABEL) as Sort[]).map((value) => ({ value, label: SORT_LABEL[value] }))} />
        </div>
      </div>
      <Note strong="내가 확정한 카드로만 비교해요" tone="inset">
        등급은 합격 가능성이 아니라 확정한 카드 근거가 인재상을 뒷받침하는 정도예요. 근거가 없는 인재상은 지어내지 않고 빈 칸으로 보여줘요.
      </Note>
      {matches.isError && <QueryFailure error={matches.error} retry={() => matches.refetch()} pending={matches.isFetching} />}
      {cards.isError && !matches.isError && <QueryFailure error={cards.error} retry={() => cards.refetch()} pending={cards.isFetching} />}
      {loading && !matches.isError && !cards.isError && <div className="stack" style={{ gap: 8 }}>{[0, 1, 2].map((i) => <Skeleton key={i} h={88} />)}</div>}
      {!loading && cards.isSuccess && confirmed === 0 && <NeedsConfirmedCards what="기업·직무 매칭" />}
      {!loading && confirmed > 0 && matches.isSuccess && list.length === 0 && <EmptyState title="맞는 기업이 없어요" desc="검색어나 직무 필터를 바꿔 보세요." />}
      {confirmed > 0 && list.length > 0 && (
        <div className="record-list" aria-label="기업·직무 목록">
          {list.map((match) => <MatchRow key={match.id} match={match} />)}
        </div>
      )}
      <SampleDataNote />
      {confirmed > 0 && (
        <div className="row">
          <Link to="/cover-letter" className="btn btn--outline">자소서 초안 만들기</Link>
        </div>
      )}
    </main>
  );
}

function MatchRow({ match }: { match: MatchTarget }) {
  const left = daysUntil(match.source.expiresAt);
  const extra = Math.max(0, match.matchedTags.length - 3);
  return (
    <Link to={`/match/${match.id}`} className="card row repo-row" onClick={() => track('match_opened', { matchId: match.id, fit: match.fit })}>
      <IconBox icon={Building2} size={34} />
      <div className="stack grow" style={{ gap: 5, textAlign: 'left' }}>
        <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
          <span className="w-600" style={{ fontSize: 14 }}>{match.company}</span>
          <span className="t-12 c-3">{match.role}</span>
          <FitBadge fit={match.fit} />
          {left <= 14 && <Badge kind="CAUTION">곧 만료 · {Math.max(left, 0)}일 남음</Badge>}
        </div>
        <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
          {match.matchedTags.slice(0, 3).map((tag) => <Chip key={tag} fill>{tag}</Chip>)}
          {extra > 0 && <span className="t-12 c-3">+{extra}</span>}
          {match.matchedTags.length === 0 && <span className="repo-row__warn">아직 맞닿는 인재상 근거가 없어요</span>}
        </div>
        <span className="repo-row__stats t-12 c-3">
          <span>인재상 {match.supportedTagCount}/{match.tagCount} 근거 확보</span>
          <span>근거 카드 {match.supportingCardCount}장</span>
          <span>확인일 {ymd(match.source.verifiedAt)}</span>
        </span>
      </div>
    </Link>
  );
}

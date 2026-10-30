import { useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useRepo, useRepos, useStartAnalysis } from '@/api/queries';
import type { RepoSummary } from '@/api/schemas';
import { CalendarDays, FileCode2, FolderGit2, GitCommitHorizontal, GitPullRequest } from 'lucide-react';
import { MetaItem } from '@/components/MetaItem';
import { Badge, Button, IconBox, Radio, PageTitle, Skeleton, StickyFooter, Note, EmptyState } from '@/components/ui';
import { AiWait } from '@/components/ui/AiWait';
import { Modal } from '@/components/ui/Modal';
import { PermissionGrid } from '@/features/auth/LandingPage';
import { pct, ym } from '@/lib/format';
import { COUNTING, isCounting } from '@/lib/repoCounts';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { SearchField } from '@/components/ui/SearchField';
import { Select } from '@/components/ui/Select';

type Sort = 'activity' | 'recent' | 'name';
type Filter = 'all' | 'pr' | 'nopr' | 'fresh';
const SORT_LABEL: Record<Sort, string> = { activity: '활동량 순', recent: '최근 활동 순', name: '이름 순' };
const FILTER_LABEL: Record<Filter, string> = { all: '전체', pr: 'PR 있는 레포', nopr: 'PR 0건', fresh: '아직 정리 안 함' };
/** 이만큼 지나도 목록이 안 오면 "GitHub 가 느리다"고 덧붙인다 — 빠른 응답(1~2초)에는 한 줄만 보이게 */
const REPOS_SLOW_MS = 3_000;

/** B1 레포 목록·선택 (페이지) → B2 사전 고지 (모달) → 정리 시작 → B3 */
export function ReposPage() {
  useDocumentTitle('레포 선택');
  const repos = useRepos();
  const [sp, setSp] = useSearchParams();
  const selectedId = sp.get('select');
  const disclose = sp.get('disclose') === '1';
  const [q, setQ] = useState('');
  const [sort, setSort] = useState<Sort>('activity');
  const [filter, setFilter] = useState<Filter>(() => (['all', 'pr', 'nopr', 'fresh'].includes(sp.get('filter') ?? '') ? (sp.get('filter') as Filter) : 'all'));
  const nav = useNavigate();
  const start = useStartAnalysis();

  const list = useMemo(() => {
    let xs = (repos.data ?? []).filter((r) => `${r.owner}/${r.name}`.toLowerCase().includes(q.toLowerCase()));
    if (filter === 'pr') xs = xs.filter((r) => !isCounting(r) && r.prCount > 0);
    if (filter === 'nopr') xs = xs.filter((r) => !isCounting(r) && r.prCount === 0);
    if (filter === 'fresh') xs = xs.filter((r) => !r.lastAnalyzedAt);
    const score = (r: RepoSummary) => r.contribution.mine + r.prCount * 3 + r.reviewCount;
    return xs.sort((a, b) => sort === 'name' ? a.name.localeCompare(b.name) : sort === 'recent' ? b.activeTo.localeCompare(a.activeTo) : score(b) - score(a));
  }, [repos.data, q, sort, filter]);
  const selected = (repos.data ?? []).find((r) => r.id === selectedId) ?? null;
  const detail = useRepo(disclose ? selectedId ?? undefined : undefined);

  // setSp({...}) 는 쿼리스트링 전체를 교체한다 — filter 처럼 URL 에만 남아 있던 값이 select/disclose 조작 한 번에 사라져,
  // 그 상태에서 새로고침하면 필터가 조용히 '전체'로 되돌아간다. 기존 파라미터를 남기고 필요한 키만 바꾼다.
  const select = (id: string) => { setSp((current) => { current.set('select', id); return current; }); track('repo_selected', { repoId: id }); };
  const openDisclose = () => selectedId && setSp((current) => { current.set('select', selectedId); current.set('disclose', '1'); return current; });
  const closeDisclose = () => selectedId && setSp((current) => { current.set('select', selectedId); current.delete('disclose'); return current; });
  const begin = async () => {
    if (!selected) return;
    try {
    const { jobId } = await start.mutateAsync(selected.id);
    track('analysis_started', { repoId: selected.id, jobId });
    nav(`/repos/${selected.id}/run?job=${jobId}`);
    } catch { /* Preserve the action identity for retry; error notification is global. */ }
  };

  return (
    <main className="main main--footer main--tight">
      <ol className="wizard__steps repo-picker__steps" aria-label="진행 단계">
        <li className="wstep wstep--now"><span className="wstep__no">1</span>레포 선택</li>
        <li className="wstep"><span className="wstep__no">2</span>읽는 범위 확인</li>
        <li className="wstep"><span className="wstep__no">3</span>후보 고르기</li>
      </ol>
      <div className="list-head">
        {/* 아직 못 받았으면 개수를 모른다 — 0 으로 보이면 "레포가 없다"로 읽힌다 */}
        <PageTitle>레포 고르기 {repos.data && <span className="c-3 t-14">{repos.data.length}</span>}</PageTitle>
        <div className="right">
          <SearchField className="list-search" value={q} onChange={setQ} placeholder="레포 이름 검색" />
          <Select value={sort} onChange={setSort} label="정렬" options={(Object.keys(SORT_LABEL) as Sort[]).map((value) => ({ value, label: SORT_LABEL[value] }))} />
          <Select value={filter} onChange={setFilter} label="필터" options={(Object.keys(FILTER_LABEL) as Filter[]).map((value) => ({ value, label: FILTER_LABEL[value] }))} />
        </div>
      </div>
      {repos.isError && <QueryFailure error={repos.error} retry={() => repos.refetch()} pending={repos.isFetching} />}

      {/* 목록은 서버가 GitHub 에서 그때그때 읽어 온다 — GitHub 가 느려지면 그만큼 늦게 뜬다. 회색 막대만 두면 멈춘 것처럼 보인다.
          실패하면 자동으로 다시 시도하므로(failureCount) 그 사실도 말한다. 얼마나 남았는지는 모르니 시간·진행률은 만들지 않는다. */}
      {repos.isPending && <>
        <AiWait label="GitHub 에서 내 레포 목록을 불러오는 중이에요" longAfterMs={REPOS_SLOW_MS}
          longLabel={repos.failureCount > 0 ? 'GitHub 응답이 없어 다시 시도하는 중이에요. 잠시만 기다려 주세요.' : 'GitHub 응답이 평소보다 느려요. 이 화면에서 잠시만 기다려 주세요.'} />
        <div className="stack" style={{ gap: 8 }}>{[0, 1, 2].map((i) => <Skeleton key={i} h={72} />)}</div>
      </>}
      {repos.isSuccess && list.length === 0 && <div className="stack" style={{ gap: 12, alignItems: 'center' }}>
        <EmptyState title={q.trim() ? '검색 결과가 없어요' : filter !== 'all' ? '조건에 맞는 레포가 없어요' : '아직 불러온 레포가 없어요'} desc={q.trim() ? '이름을 확인하거나 검색과 필터를 초기화해 주세요.' : filter !== 'all' ? '필터를 바꿔 다른 레포를 확인해 보세요.' : '비공개 레포, 커밋이 없는 레포와 내 프로필 README 레포는 목록에서 제외돼요. GitHub 연결 상태도 확인해 주세요.'} />
        {(q || filter !== 'all') ? <Button variant="outline" onClick={() => { setQ(''); setFilter('all'); }}>검색과 필터 초기화</Button> : <Link to="/settings/github" className="btn btn--outline">GitHub 연결 확인</Link>}
      </div>}
      <div className="record-list" role="radiogroup" aria-label="레포 선택">
        {list.map((r) => <RepoRow key={r.id} r={r} selected={r.id === selectedId} onSelect={() => select(r.id)} />)}
      </div>

      <StickyFooter strong={selected ? `${selected.name}` : '레포를 하나 고르세요'}>
        <Link to="/" className="btn btn--outline">취소</Link>
        <Button size="lg" disabled={!selected} onClick={openDisclose}>다음</Button>
      </StickyFooter>

      {disclose && selected && (
        <Modal title="읽는 범위 확인" width={720} onClose={closeDisclose}
          footer={{ strong: detail.data ? `약 ${detail.data.disclosure.estimatedSeconds}초` : '…',
            actions: <><Button variant="outline" onClick={closeDisclose}>취소</Button><Button onClick={begin} loading={start.isPending} disabled={!detail.data}>정리 시작</Button></> }}>
            <div className="stack" style={{ gap: 14 }}>
              <div className="card card--paper repo-ctx" style={{ padding: '14px 16px' }}>
                <IconBox icon={FolderGit2} size={36} />
                <div className="stack grow" style={{ gap: 3 }}>
                  <span className="w-600" style={{ fontSize: 14 }}>{selected.owner} / {selected.name}</span>
                  <span className="t-12 c-2">{isCounting(selected) ? `기여 개수를 ${COUNTING}이에요 — 0건이 아니라 집계 전이에요` : `내 커밋 ${selected.contribution.mine} / 팀 ${selected.contribution.team}, PR ${selected.prCount}, 리뷰 ${selected.reviewCount}`}</span>
                </div>
                <Badge kind="NEUTRAL">READ ONLY</Badge>
              </div>
              {detail.isPending ? <Skeleton h={160} /> : detail.data && <PermissionGrid compact reads={detail.data.disclosure.reads} skips={detail.data.disclosure.skips} />}
              {detail.isError && <QueryFailure error={detail.error} retry={() => detail.refetch()} pending={detail.isFetching} />}
              {selected.lastAnalyzedAt && <Note strong="전에 정리한 레포예요" tone="inset">다시 정리해 새 기록을 확인할 수 있어요.</Note>}
            </div>
        </Modal>
      )}
    </main>
  );
}

function RepoRow({ r, selected, onSelect }: { r: RepoSummary; selected: boolean; onSelect: () => void }) {
  const counting = isCounting(r); // 개수 0 이 실제 0 이 아니다 — 기여 경고·PR 0건 표시를 하지 않는다
  const low = !counting && (r.contribution.level === 'PARTIAL' || r.contribution.level === 'NONE');
  return (
    <button type="button" role="radio" aria-checked={selected} onClick={onSelect}
      className={`card row repo-row ${selected ? 'card--selected repo-row--sel' : ''}`}>
      <Radio checked={selected} />
      <IconBox icon={FolderGit2} size={34} />
      <div className="stack grow" style={{ gap: 5, textAlign: 'left' }}>
        <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
          <span className="w-600" style={{ fontSize: 14 }}>{r.owner} / {r.name}</span>
          {r.recommended && !r.lastAnalyzedAt && <Badge kind="PR">추천</Badge>}
          {low && <Badge kind="CAUTION">내 기여 {pct(r.contribution.ratio)}</Badge>}
          {counting && <Badge kind="NEUTRAL">{COUNTING}</Badge>}
          {!counting && r.prCount === 0 && <Badge kind="NEUTRAL">PR 0건</Badge>}
          {r.lastAnalyzedAt && <span className="repo-row__status"><Badge kind="NEUTRAL">정리 완료</Badge><Badge kind="NEUTRAL">카드 {r.cardCount}건</Badge></span>}
        </div>
        <div className="row" style={{ gap: 10, flexWrap: 'wrap' }}>
          <span className={`mine-chip ${low ? 'mine-chip--low' : ''}`}><GitCommitHorizontal size={14} aria-hidden />{counting ? <>내 커밋 <span>{COUNTING}</span></> : <>내 커밋 {r.contribution.mine} <span>/ 팀 {r.contribution.team}</span></>}</span>
          <span className="repo-row__stats t-12 c-3">
            <MetaItem icon={GitPullRequest}>{counting ? `PR과 리뷰 ${COUNTING}` : `PR ${r.prCount}, 리뷰 ${r.reviewCount}`}</MetaItem>
            <MetaItem icon={FileCode2}>{r.language ?? '-'}</MetaItem>
            <MetaItem icon={CalendarDays}>{ym(r.activeFrom)} – {ym(r.activeTo)}</MetaItem>
          </span>
        </div>
        {/* 배지만 두면 왜 조심해야 하는지 안 읽힌다 — 한 줄로 풀어 쓴다 */}
        {low && <span className="repo-row__warn">확인되는 기여가 적어요. 맡은 일은 답변해서 채울 수 있어요.</span>}
        {counting && <span className="t-12 c-3">아직 개수를 세지 못했어요. 0건이 아니라 집계 전이에요</span>}
        {!counting && !low && r.prCount === 0 && <span className="repo-row__warn">PR이 없어서 커밋을 묶어 후보를 만들어 드려요</span>}
      </div>
    </button>
  );
}

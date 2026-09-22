import { useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useRepo, useRepos, useStartAnalysis } from '@/api/queries';
import type { RepoSummary } from '@/api/schemas';
import { FolderGit2 } from 'lucide-react';
import { Badge, Button, IconBox, Radio, SectionHead, Skeleton, StickyFooter, Note, EmptyState } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { PermissionGrid } from '@/features/auth/LandingPage';
import { pct, ym } from '@/lib/format';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { QueryFailure } from '@/components/ui/QueryFailure';

type Sort = 'activity' | 'recent' | 'name';
type Filter = 'all' | 'pr' | 'nopr' | 'fresh';
const SORT_LABEL: Record<Sort, string> = { activity: '활동량 순', recent: '최근 활동 순', name: '이름 순' };
const FILTER_LABEL: Record<Filter, string> = { all: '전체', pr: 'PR 있는 레포', nopr: 'PR 0건', fresh: '아직 정리 안 함' };

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
    if (filter === 'pr') xs = xs.filter((r) => r.prCount > 0);
    if (filter === 'nopr') xs = xs.filter((r) => r.prCount === 0);
    if (filter === 'fresh') xs = xs.filter((r) => !r.lastAnalyzedAt);
    const score = (r: RepoSummary) => r.contribution.mine + r.prCount * 3 + r.reviewCount;
    return xs.sort((a, b) => sort === 'name' ? a.name.localeCompare(b.name) : sort === 'recent' ? b.activeTo.localeCompare(a.activeTo) : score(b) - score(a));
  }, [repos.data, q, sort, filter]);
  const selected = (repos.data ?? []).find((r) => r.id === selectedId) ?? null;
  const detail = useRepo(disclose ? selectedId ?? undefined : undefined);

  const select = (id: string) => { setSp({ select: id }); track('repo_selected', { repoId: id }); };
  const openDisclose = () => selectedId && setSp({ select: selectedId, disclose: '1' });
  const closeDisclose = () => selectedId && setSp({ select: selectedId });
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
      <ol className="wizard__steps" style={{ flexDirection: 'row', gap: 22 }} aria-label="진행 단계">
        <li className="wstep wstep--now"><span className="wstep__no">1</span>레포 선택</li>
        <li className="wstep"><span className="wstep__no">2</span>읽는 범위 확인</li>
        <li className="wstep"><span className="wstep__no">3</span>후보 고르기</li>
      </ol>
      <div className="list-head">
        <h1>{repos.isSuccess ? `레포 ${repos.data.length}개 중에서 하나만 골라 주세요` : '레포 고르기'}</h1>
        <div className="right">
          <label className="list-search"><span className="c-3">⌕</span><input value={q} onChange={(e) => setQ(e.target.value)} placeholder="레포 이름 검색" aria-label="레포 이름 검색" /></label>
          <label className="chip chip--select"><select value={sort} onChange={(e) => setSort(e.target.value as Sort)} aria-label="정렬">{(Object.keys(SORT_LABEL) as Sort[]).map((k) => <option key={k} value={k}>{SORT_LABEL[k]}</option>)}</select></label>
          <label className="chip chip--select"><select value={filter} onChange={(e) => setFilter(e.target.value as Filter)} aria-label="필터">{(Object.keys(FILTER_LABEL) as Filter[]).map((k) => <option key={k} value={k}>{FILTER_LABEL[k]}</option>)}</select></label>
        </div>
      </div>
      <SectionHead label={FILTER_LABEL[filter]} count={list.length} />
      {repos.isError && <QueryFailure error={repos.error} retry={() => repos.refetch()} pending={repos.isFetching} />}

      {repos.isPending && <div className="stack" style={{ gap: 8 }}>{[0, 1, 2].map((i) => <Skeleton key={i} h={72} />)}</div>}
      {repos.isSuccess && list.length === 0 && <EmptyState title="맞는 레포가 없어요" desc="비공개 저장소는 목록에 나오지 않아요. 권한부터 요청하지 않기 때문입니다." />}
      <div className="record-list" role="radiogroup" aria-label="레포 선택">
        {list.map((r) => <RepoRow key={r.id} r={r} selected={r.id === selectedId} onSelect={() => select(r.id)} />)}
      </div>

      <StickyFooter strong={selected ? `${selected.name}` : '레포를 하나 고르세요'}>
        <Link to="/" className="btn btn--outline">취소</Link>
        <Button size="lg" disabled={!selected} onClick={openDisclose}>다음 →</Button>
      </StickyFooter>

      {disclose && selected && (
        <Modal title="읽기 전에 알려 드릴게요" width={720} onClose={closeDisclose}
          footer={{ strong: detail.data ? `약 ${detail.data.disclosure.estimatedSeconds}초` : '…',
            actions: <><Button variant="outline" onClick={closeDisclose}>취소</Button><Button onClick={begin} loading={start.isPending} disabled={!detail.data}>정리 시작</Button></> }}>
          <div className="wizard">
            <ol className="wizard__steps" aria-label="진행 단계">
              <li className="wstep wstep--done"><span className="wstep__no">✓</span>레포 선택</li>
              <li className="wstep wstep--now"><span className="wstep__no">2</span>읽는 범위 확인</li>
              <li className="wstep"><span className="wstep__no">3</span>후보 고르기</li>
            </ol>
            <div className="stack" style={{ gap: 14 }}>
              <div className="card card--paper repo-ctx" style={{ padding: '14px 16px' }}>
                <IconBox icon={FolderGit2} size={36} />
                <div className="stack grow" style={{ gap: 3 }}>
                  <span className="w-700" style={{ fontSize: 14 }}>{selected.owner} / {selected.name}</span>
                  <span className="t-12 c-2">내 커밋 {selected.contribution.mine} / 팀 {selected.contribution.team} · PR {selected.prCount} · 리뷰 {selected.reviewCount}</span>
                </div>
                <Badge kind="NEUTRAL">READ ONLY</Badge>
              </div>
              {detail.isPending ? <Skeleton h={160} /> : detail.data && <PermissionGrid compact reads={detail.data.disclosure.reads} skips={detail.data.disclosure.skips} />}
              {detail.isError && <QueryFailure error={detail.error} retry={() => detail.refetch()} pending={detail.isFetching} />}
              {selected.lastAnalyzedAt && <Note strong="전에 정리한 레포예요" tone="inset">이번엔 새로 올라온 커밋만 이어서 읽습니다.</Note>}
            </div>
          </div>
        </Modal>
      )}
    </main>
  );
}

function RepoRow({ r, selected, onSelect }: { r: RepoSummary; selected: boolean; onSelect: () => void }) {
  const low = r.contribution.level === 'PARTIAL' || r.contribution.level === 'NONE';
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
          {r.prCount === 0 && <Badge kind="NEUTRAL">PR 0건</Badge>}
          {r.lastAnalyzedAt && <Badge kind="NEUTRAL">정리함 · 카드 {r.cardCount}</Badge>}
        </div>
        <div className="row" style={{ gap: 10, flexWrap: 'wrap' }}>
          <span className={`mine-chip ${low ? 'mine-chip--low' : ''}`}>내 커밋 {r.contribution.mine} <span>/ 팀 {r.contribution.team}</span></span>
          <span className="t-12 c-3">PR {r.prCount} · 리뷰 {r.reviewCount} · {r.language ?? '-'} · {ym(r.activeFrom)} - {ym(r.activeTo)}</span>
        </div>
        {/* 배지만 두면 왜 조심해야 하는지 안 읽힌다 — 한 줄로 풀어 쓴다 */}
        {low && <span className="repo-row__warn">내 몫이 적어서, 카드로 만들면 부풀린 것처럼 보일 수 있어요</span>}
        {!low && r.prCount === 0 && <span className="repo-row__warn">PR이 없어서 커밋을 묶어 후보를 만들어 드려요</span>}
      </div>
    </button>
  );
}

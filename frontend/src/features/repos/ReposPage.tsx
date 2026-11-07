import { useMemo, useState, type ReactNode } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useRepo, useRepos, useStartAnalysis } from '@/api/queries';
import type { RepoSummary } from '@/api/schemas';
import { FolderGit2 } from 'lucide-react';
import { Badge, Button, IconBox, Radio, PageTitle, SectionHead, Skeleton, StickyFooter, Note, EmptyState } from '@/components/ui';
import { AiWait } from '@/components/ui/AiWait';
import { Modal } from '@/components/ui/Modal';
import { PermissionGrid } from '@/features/auth/LandingPage';
import { COUNTING, isCounting } from '@/lib/repoCounts';
import { groupRepos } from '@/lib/repoGroups';
import { repoMeta, repoSentence } from '@/lib/repoSentence';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { SearchField } from '@/components/ui/SearchField';
import { Select } from '@/components/ui/Select';

type Sort = 'activity' | 'recent' | 'name';
const SORT_LABEL: Record<Sort, string> = { activity: '활동량 순', recent: '최근 활동 순', name: '이름 순' };
/** 이만큼 지나도 목록이 안 오면 "GitHub 가 느리다"고 덧붙인다 — 빠른 응답(1~2초)에는 한 줄만 보이게 */
const REPOS_SLOW_MS = 3_000;

/** B1 레포 목록·선택 (페이지) → B2 사전 고지 (모달) → 정리 시작 → B3 */
export function ReposPage() {
  useDocumentTitle('레포');
  const repos = useRepos();
  const [sp, setSp] = useSearchParams();
  const selectedId = sp.get('select');
  const disclose = sp.get('disclose') === '1';
  const [q, setQ] = useState('');
  const [sort, setSort] = useState<Sort>('activity');
  const nav = useNavigate();
  const start = useStartAnalysis();

  // 정렬은 묶음 안에서만 한다 — 묶음 순서(추천 → 아직 안 정리 → 정리 완료)는 정렬을 바꿔도 그대로다
  const groups = useMemo(() => {
    const score = (r: RepoSummary) => r.contribution.mine + r.prCount * 3 + r.reviewCount;
    const order = (a: RepoSummary, b: RepoSummary) => (sort === 'name' ? a.name.localeCompare(b.name) : sort === 'recent' ? b.activeTo.localeCompare(a.activeTo) : score(b) - score(a));
    const { recommended, fresh, done } = groupRepos((repos.data ?? []).filter((r) => `${r.owner}/${r.name}`.toLowerCase().includes(q.toLowerCase())));
    return { recommended: recommended.sort(order), fresh: fresh.sort(order), done: done.sort(order) };
  }, [repos.data, q, sort]);
  const picking = groups.recommended.length + groups.fresh.length;
  const shown = picking + groups.done.length;
  const selected = (repos.data ?? []).find((r) => r.id === selectedId) ?? null;
  const detail = useRepo(disclose ? selectedId ?? undefined : undefined);

  // setSp({...}) 는 쿼리스트링 전체를 교체한다 — URL 에만 남아 있던 다른 값이 select/disclose 조작 한 번에 사라지지 않게
  // 기존 파라미터를 남기고 필요한 키만 바꾼다.
  const select = (id: string) => { setSp((current) => { current.set('select', id); return current; }); track('repo_selected', { repoId: id }); };
  const pick = (r: RepoSummary) => <RepoRow key={r.id} r={r} selected={r.id === selectedId} onSelect={() => select(r.id)} />;
  const openDisclose = () => selectedId && setSp((current) => { current.set('select', selectedId); current.set('disclose', '1'); return current; });
  // 정리한 레포는 고르는 줄이 없어서 창만 닫으면 아래 막대에만 '골랐어요'가 남는다 — 취소하면 고른 상태도 푼다
  const closeDisclose = () => selectedId && setSp((current) => { current.delete('disclose'); if (selected?.lastAnalyzedAt) current.delete('select'); else current.set('select', selectedId); return current; });
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
        <PageTitle>레포 {repos.data && <span className="c-3 t-14">{repos.data.length}</span>}</PageTitle>
        <div className="right">
          <SearchField className="list-search" value={q} onChange={setQ} placeholder="레포 이름 검색" />
          <Select value={sort} onChange={setSort} label="정렬" options={(Object.keys(SORT_LABEL) as Sort[]).map((value) => ({ value, label: SORT_LABEL[value] }))} />
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
      {repos.isSuccess && shown === 0 && <div className="stack" style={{ gap: 12, alignItems: 'center' }}>
        <EmptyState title={q.trim() ? '검색 결과가 없어요' : '아직 불러온 레포가 없어요'} desc={q.trim() ? '이름을 확인하거나 검색을 초기화해 주세요.' : '비공개 레포, 커밋이 없는 레포와 내 프로필 README 레포는 목록에서 제외돼요. GitHub 연결 상태도 확인해 주세요.'} />
        {q ? <Button variant="outline" onClick={() => setQ('')}>검색 초기화</Button> : <Link to="/settings/github" className="btn btn--outline">GitHub 연결 확인</Link>}
      </div>}
      {shown > 0 && (
        <div className="repo-groups">
          {picking > 0 && (
            <div className="repo-groups__pick" role="radiogroup" aria-label="레포 선택">
              {groups.recommended.length > 0 && <Group label="내 기여가 많은 레포" count={groups.recommended.length}><div className="record-list">{groups.recommended.map(pick)}</div></Group>}
              {groups.fresh.length > 0 && <Group label="아직 정리하지 않은 레포" count={groups.fresh.length}><div className="record-list">{groups.fresh.map(pick)}</div></Group>}
            </div>
          )}
          {groups.done.length > 0 && <Group label="이미 정리한 레포" count={groups.done.length}><ul className="record-list repo-done">{groups.done.map((r) => <DoneRow key={r.id} r={r} />)}</ul></Group>}
        </div>
      )}

      <StickyFooter strong={selected ? `${selected.name} 을 골랐어요` : '레포를 하나 골라 주세요'} sub={selected ? '다음은 읽는 범위 확인이에요' : undefined}>
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
                <Badge kind="NEUTRAL">읽기 전용</Badge>
              </div>
              {detail.isPending ? <Skeleton h={160} /> : detail.data && <PermissionGrid compact reads={detail.data.disclosure.reads} skips={detail.data.disclosure.skips} />}
              {detail.isError && <QueryFailure error={detail.error} retry={() => detail.refetch()} pending={detail.isFetching} />}
              <p className="t-14 c-2">정리한 뒤에 새 커밋이 생기면 Gitory가 다시 읽어 카드 초안을 써 둬요</p>
              {selected.lastAnalyzedAt && <Note strong="전에 정리한 레포예요" tone="inset">다시 정리해 새 기록을 확인할 수 있어요. 지난 결과만 보려면 <Link to={`/repos/${selected.id}/candidates`}>지난 후보 보기</Link></Note>}
            </div>
        </Modal>
      )}
    </main>
  );
}

function Group({ label, count, children }: { label: string; count: number; children: ReactNode }) {
  return (
    <section className="repo-group" aria-label={label}>
      <SectionHead label={label} count={count} />
      {children}
    </section>
  );
}

/** 읽는 이름은 'owner / name' 그대로 둔다 — 레포를 이름으로 찾는 시험·보조기기가 이 모양에 기댄다 */
function RepoTitle({ r }: { r: RepoSummary }) {
  return <span className="repo-row__title"><span className="repo-row__owner">{r.owner}</span><span className="sr-only"> / </span><span className="repo-row__name">{r.name}</span></span>;
}

function RepoRow({ r, selected, onSelect }: { r: RepoSummary; selected: boolean; onSelect: () => void }) {
  const line = repoSentence(r);
  const meta = repoMeta(r);
  return (
    <button type="button" role="radio" aria-checked={selected} onClick={onSelect}
      className={`card row repo-row ${selected ? 'card--selected repo-row--sel' : ''}`}>
      <Radio checked={selected} />
      <IconBox icon={FolderGit2} size={34} />
      <div className="stack grow repo-row__body">
        <RepoTitle r={r} />
        {r.description && <span className="repo-row__desc">{r.description}</span>}
        <span className="repo-row__line">
          {line.before}{line.caution && <span className="repo-row__caution">{line.caution}</span>}{line.after}
          {line.hint && <>{'. '}<span className="repo-row__hint">{line.hint}</span></>}
        </span>
        {meta && <span className="repo-row__foot"><span className="repo-row__meta">{meta}</span></span>}
      </div>
    </button>
  );
}

/** 정리한 레포는 고르는 줄이 아니라 이어서 할 일로 보내는 줄이다 — 다시 정리는 홈에서 오는 것과 같은 select·disclose 주소로 읽는 범위 확인 창을 연다 */
function DoneRow({ r }: { r: RepoSummary }) {
  const id = encodeURIComponent(r.id);
  return (
    <li className="repo-done__row">
      <IconBox icon={FolderGit2} size={34} />
      <div className="stack grow repo-row__body">
        <RepoTitle r={r} />
        {r.description && <span className="repo-row__desc">{r.description}</span>}
      </div>
      <div className="repo-done__side">
        <span className="repo-done__cards">카드 {r.cardCount}장</span>
        <Link className="repo-done__link" to={`/repos/${id}/candidates`}>후보 보기</Link>
        <Link className="repo-done__link" to={`/repos?select=${id}&disclose=1`}>다시 정리</Link>
      </div>
    </li>
  );
}

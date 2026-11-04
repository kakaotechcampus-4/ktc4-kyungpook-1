import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useActiveJobs, useAddCandidate, useCandidates, useCreateCards, usePatchCandidate, useRepo, useRepoCommits, useStartAnalysis } from '@/api/queries';
import { isTerminal, type Candidate } from '@/api/schemas';
import { Badge, Breadcrumb, Button, Check, Chip, Field, Input, Note, PageTitle, RepoContext, Skeleton, StickyFooter, Textarea } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { PermissionGrid } from '@/features/auth/LandingPage';
import { candidateRefLabel } from '@/lib/labels';
import { shortSha, ymdhm } from '@/lib/format';
import { toast } from '@/lib/toast';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { useActionGate } from '@/lib/useActionGate';
import { CARD_LIMIT_MESSAGE, MAX_CARDS_PER_REQUEST, splitRecommended } from '@/lib/candidatePicks';
import { ExternalLink } from 'lucide-react';
import './CandidateBoard.css';

const selKey = (repoId: string) => `gitory.sel.${repoId}`;
const loadSel = (repoId: string): Set<string> => {
  try {
    const value: unknown = JSON.parse(sessionStorage.getItem(selKey(repoId)) ?? '[]');
    return new Set(Array.isArray(value) ? value.filter((id): id is string => typeof id === 'string') : []);
  } catch { return new Set(); }
};

/** C1 후보 보드 〔게이트 1〕 · C2 후보 0개(EMPTY) · C3 커밋 묶음 펼치기(E-4) · C5 직접 추가(모달) · B5 partial 배너 */
export function CandidateBoardPage() {
  const { repoId = '' } = useParams();
  return <CandidateBoard key={repoId} repoId={repoId} />;
}

function CandidateBoard({ repoId }: { repoId: string }) {
  const [sp, setSp] = useSearchParams();
  const repo = useRepo(repoId);
  const board = useCandidates(repoId);
  // 분석이 아직 도는 중이면 보드가 비어 있어도 '후보 0개' 판정이 아니다(전역 감시가 이미 이 목록을 가져온다)
  const active = useActiveJobs();
  const running = active.data?.jobs.find((x) => x.userRepositoryId === repoId && x.type === 'ANALYZE' && !isTerminal(x.state)) ?? null;
  const patch = usePatchCandidate(repoId);
  const create = useCreateCards(repoId);
  const actions = useActionGate();
  const nav = useNavigate();
  const [selected, setSelected] = useState<Set<string>>(() => loadSel(repoId));
  const [expanded, setExpanded] = useState<string | null>(null);
  const [showMore, setShowMore] = useState(false);
  useDocumentTitle(repo.data ? `${repo.data.name} 후보 보드` : '후보 보드');

  // 선택은 새로고침·이탈 후 복귀에도 남는다 (되돌릴 수 있는 결정이라 서버엔 안 쓴다)
  useEffect(() => { try { sessionStorage.setItem(selKey(repoId), JSON.stringify([...selected])); } catch { /* noop */ } }, [selected, repoId]);
  useEffect(() => { if (board.data && board.data.verdict !== 'EMPTY') track('candidates_shown', { repoId, count: board.data.candidates.length, partial: board.data.partial }); }, [board.data, repoId]);

  const cands = board.data?.candidates ?? [];
  // 이미 카드로 만든(USED) 후보는 다시 고를 수 없으니 고르는 타일에서 빼고 아래 '이미 카드로 만든 일' 한 줄 목록에 둔다
  const open = useMemo(() => cands.filter((c) => c.status !== 'EXCLUDED' && c.status !== 'USED'), [cands]);
  const { recommended, more } = splitRecommended(open);
  const usedList = cands.filter((c) => c.status === 'USED');
  const excluded = cands.filter((c) => c.status === 'EXCLUDED');
  const selectable = new Set(cands.filter((c) => c.status === 'NEW').map((c) => c.id));
  const chosen = [...selected].filter((id) => selectable.has(id));
  const busy = actions.running || patch.isPending || create.isPending;
  const name = repo.data ? `${repo.data.owner} / ${repo.data.name}` : '…';
  const crumbs = [{ label: '홈', to: '/' }, { label: repo.data?.name ?? '…', to: '/repos' }, { label: '후보 보드' }];

  const overLimit = chosen.length > MAX_CARDS_PER_REQUEST;
  const toggle = (id: string) => {
    if (!selected.has(id) && chosen.length >= MAX_CARDS_PER_REQUEST) {
      toast(CARD_LIMIT_MESSAGE);
      return;
    }
    setSelected((s) => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n; });
  };
  // 예전에 '제외'로 뺀 후보가 남아 있는 보드에서만 되돌리는 길을 둔다
  const restoreExcluded = async () => {
    await actions.run(async () => {
      const results = await Promise.allSettled(excluded.map((c) => patch.mutateAsync({ id: c.id, status: 'NEW' })));
      const failures = results.filter((result) => result.status === 'rejected').length;
      if (failures) toast(`${results.length - failures}개 복원, ${failures}개는 다시 시도해 주세요`, { tone: 'danger' });
    });
  };
  const tile = (c: Candidate) => (
    <CandidateTile key={c.id} c={c} disabled={busy} selected={selected.has(c.id)} onToggle={() => toggle(c.id)}
      expanded={expanded === c.id} onExpand={() => setExpanded(expanded === c.id ? null : c.id)}
      onCommitToggle={(shas) => { void actions.run(async () => {
        await patch.mutateAsync({ id: c.id, excludedShas: shas });
        track('cluster_commit_excluded', { candidateId: c.id, excluded: shas.length });
      }); }} />
  );
  const makeCards = async () => {
    if (chosen.length === 0) return;
    if (overLimit) {
      toast(CARD_LIMIT_MESSAGE);
      return;
    }
    await actions.run(async () => {
    const { cardIds, jobId } = await create.mutateAsync(chosen);
    track('candidates_confirmed', { repoId, count: chosen.length, jobId });
    setSelected(new Set());
    nav(cardIds.length ? `/cards/${encodeURIComponent(cardIds[0])}` : `/cards/repo/${encodeURIComponent(repoId)}`, { state: cardIds.length ? { cardOrder: cardIds } : undefined });
    });
  };

  if (board.isPending) return <main className="main"><Breadcrumb items={crumbs} /><Skeleton h={40} w={240} /><Skeleton h={300} /></main>;
  // 백그라운드 리페치만 실패했고 캐시에 보드가 있으면(방금 PATCH 응답을 합쳐 둔 상태) 보드를 그대로 보여준다.
  if (board.isError && !board.data) return <main className="main"><QueryFailure error={board.error} retry={() => board.refetch()} pending={board.isFetching} /><Link to="/repos" className="btn btn--outline">레포 목록</Link></main>;
  const b = board.data!;

  // 직접 추가한 후보는 바로 골라 둔다 — 추가한 사람이 그 후보로 카드를 만들려는 것이라 다시 찾아 체크하지 않게 한다
  // 이미 한 번에 만들 수 있는 만큼 골랐으면 고르지 않는다 — 고르면 카드 만들기가 막힌다
  // 직접 추가한 후보는 점수가 낮아 접힌 쪽에 들어가기 쉬워서, 추가하면 나머지를 펼쳐 바로 보이게 한다
  const openAdd = () => setSp((current) => { current.set('add', '1'); return current; });
  const addDialog = sp.get('add') === '1' && <AddCandidateDialog repoId={repoId}
    onAdded={(c) => {
      setShowMore(true);
      if (chosen.length >= MAX_CARDS_PER_REQUEST) {
        toast(`"${c.title}" 을 후보에 추가했어요. ${CARD_LIMIT_MESSAGE}`);
        return;
      }
      setSelected((current) => new Set(current).add(c.id));
      toast(`"${c.title}" 을 후보에 추가하고 골라 두었어요`, { tone: 'success' });
    }}
    onClose={() => setSp((current) => { current.delete('add'); return current; })} />;

  // 분석 중: 결과가 아직 없을 뿐이다. 진행 화면으로 돌아가는 길을 준다
  if (b.verdict === 'EMPTY' && running) {
    return (
      <main className="main main--tight">
        <Breadcrumb items={crumbs} />
        <PageTitle>아직 후보를 찾는 중이에요</PageTitle>
        <RepoContext name={name} note="정리가 끝나면 여기에 후보가 나와요."
          right={<Link to={`/repos/${repoId}/run?job=${encodeURIComponent(running.jobId)}`} className="btn btn--primary">진행 상황 보기</Link>} />
      </main>
    );
  }
  // C2 — 후보 0개: 에러가 아니라 판정. 판정 근거 3줄 + 다음 행동 3개.
  // 한 번도 정리하지 않은 레포도 서버는 EMPTY 로 준다. 그때는 떠올릴 자료(저장된 내 커밋)도 없으므로 '파일 보며 찾기' 대신 정리부터 권한다
  if (b.verdict === 'EMPTY') {
    const neverAnalyzed = !!repo.data && !repo.data.lastAnalyzedAt;
    return (
      <main className="main main--tight">
        <Breadcrumb items={crumbs} />
        <PageTitle>{neverAnalyzed ? '아직 정리하지 않은 레포예요' : '자동으로 찾은 후보가 없어요'}</PageTitle>
        <RepoContext name={name} note={neverAnalyzed ? '아직 정리 전' : '후보 0개'} />
        <div className="verdict">
          <div className="stack grow" style={{ gap: 8 }}>
            <span className="w-600" style={{ fontSize: 16 }}>{neverAnalyzed ? '정리하면 커밋과 PR에서 후보를 찾아 드려요' : '파일을 보며 경험을 떠올려 보세요'}</span>
            <div className="verdict__reasons">{b.emptyReasons.map((r) => <span key={r}>• {r}</span>)}</div>
          </div>
          <div className="stack" style={{ gap: 8, alignItems: 'flex-end' }}>
            <div className="row" style={{ gap: 8 }}>
              <Button variant="outline" size="sm" onClick={openAdd}>+ 직접 추가</Button>
              {neverAnalyzed
                ? <Link to={`/repos/${repoId}/run`} className="btn btn--primary btn--sm">정리하러 가기</Link>
                : <Link to={`/repos/${repoId}/recall`} className="btn btn--outline btn--sm">파일 보며 찾기</Link>}
            </div>
            <div className="row" style={{ gap: 8 }}>
              <Link to={`/cards/new?repo=${encodeURIComponent(repoId)}`} className="btn btn--outline btn--sm">직접 작성</Link>
              <Link to="/repos" className="btn btn--outline btn--sm">다른 레포</Link>
            </div>
          </div>
        </div>
        {addDialog}
      </main>
    );
  }

  const shown = showMore ? [...recommended, ...more] : recommended;
  return (
    <main className="main main--footer main--tight candidate-board">
      <Breadcrumb items={crumbs} />
      <ol className="wizard__steps repo-picker__steps" aria-label="진행 단계">
        <li className="wstep wstep--done"><span className="wstep__no">1</span>레포 선택</li>
        <li className="wstep wstep--done"><span className="wstep__no">2</span>읽는 범위 확인</li>
        <li className="wstep wstep--now" aria-current="step"><span className="wstep__no">3</span>후보 고르기</li>
      </ol>
      <div className="cand-head">
        <PageTitle lead="카드로 남길 일을 골라 주세요. 고른 것마다 초안을 써 드려요.">{`${repo.data?.name ?? '…'} 에서 찾은 일`}</PageTitle>
        <div className="cand-head__acts">
          <Button variant="text" onClick={() => setSp((current) => { current.set('criteria', '1'); return current; })}>정리 기준</Button>
          <Button variant="text" disabled={busy} onClick={openAdd}>+ 직접 추가</Button>
          <Link to={`/cards/new?repo=${encodeURIComponent(repoId)}`} className="btn btn--text">경험 카드 직접 쓰기</Link>
          {excluded.length > 0 && <Button variant="text" disabled={busy} onClick={() => void restoreExcluded()}>제외 {excluded.length}개 복원</Button>}
        </div>
      </div>

      {b.partial && (
        <div className="card row" style={{ gap: 16, padding: '16px 20px', background: 'var(--state-partial-bg)', border: 0, flexWrap: 'wrap' }}>
          <Badge kind="NEUTRAL">부분 결과</Badge>
          <div className="stack grow" style={{ gap: 4 }}>
            <span className="w-600" style={{ fontSize: 14 }}>읽은 기록에서 찾은 후보예요</span>
            {b.readCoverage && <span className="t-12l c-2">{`커밋 ${b.readCoverage.commitsRead}/${b.readCoverage.commitsTotal}, PR ${b.readCoverage.prsRead}/${b.readCoverage.prsTotal} 읽음`}</span>}
          </div>
          {sp.get('job') && <Link className="btn btn--outline btn--sm" to={`/repos/${repoId}/run?job=${encodeURIComponent(sp.get('job')!)}`}>작업 상태 보기</Link>}
        </div>
      )}

      <section className="cand-grp" aria-labelledby="cand-grp-open">
        <h2 className="cand-grp__h" id="cand-grp-open"><span className="cand-grp__n" aria-hidden>1</span>먼저 카드로 만들어 볼 경험
          <span className="cand-grp__meta">{more.length > 0 && !showMore ? `${recommended.length}개 · 전체 ${open.length}개` : `${open.length}개`}</span></h2>
        {open.length > 0
          ? <div className="cand-tiles">{shown.map(tile)}</div>
          : <Note strong="남은 후보가 없어요" tone="inset">후보를 모두 카드로 만들었어요. 아래에서 만든 카드를 볼 수 있어요.</Note>}
        {more.length > 0 && (
          <Button variant="text" className="cand-grp__more" aria-expanded={showMore} onClick={() => setShowMore((v) => !v)}>
            {showMore ? `처음 ${recommended.length}개만 보기` : `나머지 ${more.length}개 더 보기`}
          </Button>
        )}
      </section>

      {usedList.length > 0 && (
        <section className="cand-grp" aria-labelledby="cand-grp-used">
          <h2 className="cand-grp__h" id="cand-grp-used"><span className="cand-grp__n" aria-hidden>2</span>이미 카드로 만든 일<span className="cand-grp__meta">{usedList.length}개</span></h2>
          <ul className="cand-made">
            {usedList.map((c) => (
              <li key={c.id} className="cand-made__row">
                <span className="cand-made__title">{c.title}</span>
                <span className="cand-kind">{candidateRefLabel(c.type, c.ref)}</span>
                {c.usedByCardId && <Link to={`/cards/${c.usedByCardId}`} className="btn btn--text cand-made__link">카드 보기</Link>}
              </li>
            ))}
          </ul>
        </section>
      )}

      <StickyFooter strong={chosen.length ? `${chosen.length}개 골랐어요` : '카드로 남길 일을 골라 주세요'}
        sub={overLimit ? CARD_LIMIT_MESSAGE : chosen.length ? `${MAX_CARDS_PER_REQUEST}개까지 고를 수 있고, 하나에 1~2분쯤 걸려요` : `${MAX_CARDS_PER_REQUEST}개까지 고를 수 있어요`}>
        <Button size="lg" disabled={chosen.length === 0 || overLimit || busy} loading={create.isPending} onClick={makeCards}>카드 만들기</Button>
      </StickyFooter>

      {addDialog}
      {sp.get('criteria') === '1' && repo.data && <CriteriaDialog repoId={repoId} partial={b.partial} onClose={() => setSp((current) => { current.delete('criteria'); return current; })} />}
    </main>
  );
}

/** 후보 하나 = 타일 하나, 타일 전체가 고르기 버튼이다 — 커밋 목록 버튼은 고르기 버튼 밖에 두어 눌러도 고르기가 바뀌지 않는다 */
function CandidateTile({ c, disabled, selected, onToggle, expanded, onExpand, onCommitToggle }: {
  c: Candidate; disabled: boolean; selected: boolean; onToggle: () => void; expanded: boolean; onExpand: () => void; onCommitToggle: (excludedShas: string[]) => void;
}) {
  const commits = c.type === 'COMMIT_CLUSTER' && c.commits && c.commits.length > 0 ? c.commits : null;
  return (
    <div className={`cand-tile${selected ? ' cand-tile--on' : ''}${commits && expanded ? ' cand-tile--open' : ''}`}>
      <button type="button" className="cand-tile__pick" aria-pressed={selected} disabled={disabled} onClick={onToggle}>
        <span className="cand-tile__head"><span className="cand-tile__title">{c.title}</span><span className="cand-kind">{candidateRefLabel(c.type, c.ref)}</span></span>
        <span className="cand-tile__reason">{c.reason}</span>
        {c.weak && <span className="cand-tile__weak">추천도 낮음</span>}
        <span className="cand-tile__foot"><span className="cand-tile__chk" aria-hidden>✓</span><span className="cand-tile__state">{selected ? '골랐어요' : '고르기'}</span></span>
      </button>
      {commits && (
        <Button variant="text" size="sm" className="cand-tile__commits" aria-expanded={expanded} onClick={onExpand}>
          {expanded ? '커밋 목록 접기' : `커밋 ${commits.length}개 보기`}
        </Button>
      )}
      {commits && expanded && <div className="cand-tile__details"><ClusterCommits c={c} disabled={disabled} onChange={onCommitToggle} /></div>}
    </div>
  );
}

/** C3 — 군집화 오탐(E-4) 방어. 후보 확정 게이트가 이미 방어선: 잘못 묶여도 초안까지 가지 않는다. */
function ClusterCommits({ c, disabled, onChange }: { c: Candidate; disabled: boolean; onChange: (excludedShas: string[]) => void }) {
  const commits = c.commits ?? [];
  const toggle = (sha: string) => {
    const ex = commits.filter((x) => !x.included).map((x) => x.sha);
    onChange(ex.includes(sha) ? ex.filter((s) => s !== sha) : [...ex, sha]);
  };
  return (
    <div className="cluster-commits">
      {commits.map((cm) => (
        <div key={cm.sha} className={`commit-row commit-row--cluster ${cm.included ? '' : 'commit-row--ex'}`}>
          <Check checked={cm.included} disabled={disabled} onChange={() => toggle(cm.sha)} label={`${shortSha(cm.sha)} 포함`} />
          <div className="commit-row__body">
            <div className="commit-row__heading"><span className="commit-row__sha">{shortSha(cm.sha)}</span>{!cm.included && <Badge kind="NEUTRAL">제외됨</Badge>}</div>
            <span className="commit-row__msg">{cm.message}</span>
            <span className="commit-row__path">{cm.path}, {cm.at.slice(5, 16).replace('T', ' ')}</span>
          </div>
          <a href={cm.url} target="_blank" rel="noreferrer" className="commit-row__link" aria-label={`${shortSha(cm.sha)} GitHub에서 보기`}><ExternalLink size={16} aria-hidden /></a>
        </div>
      ))}
    </div>
  );
}

/** "정리 기준 보기" — 이 레포에서 무엇을 읽고 안 읽었는지 · 언제 정리했는지 · 다시 정리 */
function CriteriaDialog({ repoId, partial, onClose }: { repoId: string; partial: boolean; onClose: () => void }) {
  const repo = useRepo(repoId);
  const start = useStartAnalysis();
  const actions = useActionGate();
  const nav = useNavigate();
  const r = repo.data!;
  const again = async () => {
    if (partial) return;
    await actions.run(async () => {
    const { jobId } = await start.mutateAsync(repoId);
    track('analysis_started', { repoId, jobId, again: true });
    nav(`/repos/${repoId}/run?job=${jobId}`);
    });
  };
  return (
    <Modal title="이 보드의 정리 기준" width={720} onClose={() => { if (!start.isPending) onClose(); }}
      sub={r.lastAnalyzedAt ? `${ymdhm(r.lastAnalyzedAt)} 에 읽은 결과예요.` : '아직 정리 기록이 없어요.'}
      footer={{ strong: partial ? '재시도 가능 여부는 작업 상태에서 확인해 주세요' : '후보의 추천 이유와 근거를 확인해 주세요', actions: <><Button variant="outline" disabled={start.isPending} onClick={onClose}>닫기</Button><Button onClick={again} disabled={partial} loading={start.isPending}>다시 정리하기</Button></> }}>
      <PermissionGrid compact reads={r.disclosure.reads} skips={r.disclosure.skips} />
      <p className="t-12l c-2">추천 이유와 연결된 커밋을 확인한 뒤 카드로 만들 후보를 골라 주세요.</p>
    </Modal>
  );
}

/** C5 — GitHub 에 없는 작업 덩어리. 커밋을 붙이면 COMMIT, 안 붙이면 USER_STATED 근거. */
function AddCandidateDialog({ repoId, onAdded, onClose }: { repoId: string; onAdded: (c: Candidate) => void; onClose: () => void }) {
  const add = useAddCandidate(repoId);
  const actions = useActionGate();
  const [title, setTitle] = useState('');
  const [summary, setSummary] = useState('');
  const [cq, setCq] = useState('');
  const [shas, setShas] = useState<{ sha: string; message: string }[]>([]);
  const search = useRepoCommits(repoId, cq);
  const submit = async () => {
    if (!title.trim() || !summary.trim()) return;
    await actions.run(async () => {
    const c = await add.mutateAsync({ title: title.trim(), summary: summary.trim(), shas: shas.map((s) => s.sha) });
    track('candidate_added_manually', { repoId, withCommits: shas.length });
    onAdded(c);
    onClose();
    });
  };
  return (
    <Modal title="후보 직접 추가" onClose={() => { if (!add.isPending) onClose(); }}
      footer={{ strong: shas.length ? '기술 카드로 만들어져요' : '경험을 직접 쓰는 카드(정성 카드)로 만들어져요', sub: shas.length ? `커밋 ${shas.length}개가 붙어 있어요` : undefined,
        actions: <><Button variant="outline" disabled={add.isPending} onClick={onClose}>취소</Button><Button disabled={!title.trim() || !summary.trim()} loading={add.isPending} onClick={submit}>후보 추가</Button></> }}>
      <Field label="후보 이름"><Input autoFocus disabled={add.isPending} value={title} onChange={(e) => setTitle(e.target.value)} placeholder="예) 배포 파이프라인 직접 구축" maxLength={80} /></Field>
      <Field label="어떤 일을 했나요"><Textarea disabled={add.isPending} rows={3} value={summary} onChange={(e) => setSummary(e.target.value)} placeholder="한 줄로 적어 주세요." maxLength={300} /></Field>
      <Field label="관련 커밋 (선택)">
        <Input disabled={add.isPending} value={cq} onChange={(e) => setCq(e.target.value)} placeholder="커밋 메시지나 sha 로 검색" />
      </Field>
      {cq.trim().length >= 2 && (
        <div className="stack" style={{ gap: 6, maxHeight: 200, overflowY: 'auto' }}>
          {search.isPending && <Skeleton h={40} />}
          {search.isError && <QueryFailure error={search.error} retry={() => search.refetch()} pending={search.isFetching} />}
          {search.data?.length === 0 && <span className="t-12 c-3">맞는 커밋이 없어요</span>}
          {search.data?.map((cm) => {
            const on = shas.some((s) => s.sha === cm.sha);
            return (
              <button key={cm.sha} type="button" disabled={add.isPending} className={`commit-row commit-row--picker ${on ? 'card--selected' : ''}`}
                onClick={() => setShas((xs) => on ? xs.filter((s) => s.sha !== cm.sha) : [...xs, { sha: cm.sha, message: cm.message }])} aria-pressed={on}>
                <span className="commit-row__heading">
                  <span className="commit-row__sha">{shortSha(cm.sha)}</span>
                  {cm.candidateId && <Badge kind="NEUTRAL">다른 후보에 있음</Badge>}
                </span>
                <span className="commit-row__msg">{cm.message}</span>
              </button>
            );
          })}
        </div>
      )}
      {shas.length > 0 && <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>{shas.map((s) => <Chip key={s.sha} fill disabled={add.isPending} onClick={() => setShas((xs) => xs.filter((x) => x.sha !== s.sha))}>{shortSha(s.sha)} {s.message.slice(0, 18)} ✕</Chip>)}</div>}
    </Modal>
  );
}

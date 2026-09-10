import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useAddCandidate, useCandidates, useCreateCards, usePatchCandidate, useRepo, useRepoCommits, useStartAnalysis } from '@/api/queries';
import type { Candidate } from '@/api/schemas';
import { Badge, Breadcrumb, Button, Check, Chip, Field, Input, Note, PageTitle, RepoContext, SectionHead, Skeleton, StickyFooter, Textarea, Toolbar } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { PermissionGrid } from '@/features/auth/LandingPage';
import { candidateRefLabel, candidateStatusLabel } from '@/lib/labels';
import { minutes, shortSha, ymdhm } from '@/lib/format';
import { toast } from '@/lib/toast';
import { track } from '@/lib/track';
import { watchJob } from '@/lib/jobWatcher';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

const selKey = (repoId: string) => `gitory.sel.${repoId}`;
const loadSel = (repoId: string): Set<string> => { try { return new Set(JSON.parse(sessionStorage.getItem(selKey(repoId)) ?? '[]')); } catch { return new Set(); } };

/** C1 후보 보드 〔게이트 1〕 · C2 후보 0개(EMPTY) · C3 커밋 묶음 펼치기(E-4) · C5 직접 추가(모달) · B5 partial 배너 */
export function CandidateBoardPage() {
  const { repoId = '' } = useParams();
  const [sp, setSp] = useSearchParams();
  const repo = useRepo(repoId);
  const board = useCandidates(repoId);
  const patch = usePatchCandidate(repoId);
  const create = useCreateCards(repoId);
  const nav = useNavigate();
  const [q, setQ] = useState('');
  const [hideUsed, setHideUsed] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(() => loadSel(repoId));
  const [expanded, setExpanded] = useState<string | null>(null);
  useDocumentTitle(repo.data ? `${repo.data.name} 후보 보드` : '후보 보드');

  // 선택은 새로고침·이탈 후 복귀에도 남는다 (되돌릴 수 있는 결정이라 서버엔 안 쓴다)
  useEffect(() => { try { sessionStorage.setItem(selKey(repoId), JSON.stringify([...selected])); } catch { /* noop */ } }, [selected, repoId]);
  useEffect(() => { if (board.data && board.data.verdict !== 'EMPTY') track('candidates_shown', { repoId, count: board.data.candidates.length, partial: board.data.partial }); }, [board.data, repoId]);

  const cands = board.data?.candidates ?? [];
  const visible = useMemo(() => cands.filter((c) => c.status !== 'EXCLUDED' && (!hideUsed || c.status !== 'USED') && (c.title + c.reason).toLowerCase().includes(q.toLowerCase())), [cands, hideUsed, q]);
  const excluded = cands.filter((c) => c.status === 'EXCLUDED');
  const selectable = new Set(cands.filter((c) => c.status === 'NEW').map((c) => c.id));
  const chosen = [...selected].filter((id) => selectable.has(id));
  const name = repo.data ? `${repo.data.owner} / ${repo.data.name}` : '…';
  const crumbs = [{ label: '경험정리/홈', to: '/' }, { label: repo.data?.name ?? '…', to: '/repos' }, { label: '후보 보드' }];

  const toggle = (id: string) => setSelected((s) => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n; });
  const exclude = (c: Candidate) => {
    patch.mutate({ id: c.id, status: 'EXCLUDED' });
    setSelected((s) => { const n = new Set(s); n.delete(c.id); return n; });
    track('candidate_excluded', { candidateId: c.id, type: c.type });
    toast(`"${c.title}" 을 제외했습니다`, { action: { label: '실행 취소', onClick: () => { patch.mutate({ id: c.id, status: 'NEW' }); track('candidate_restored', { candidateId: c.id }); } } });
  };
  const makeCards = async () => {
    const { cardIds, jobId } = await create.mutateAsync(chosen);
    track('candidates_confirmed', { repoId, count: chosen.length, jobId });
    watchJob({ jobId, type: 'DRAFT', label: `카드 초안 ${cardIds.length}장`, href: `/cards/${cardIds[0]}` });
    setSelected(new Set());
    nav(`/cards/${cardIds[0]}`);
  };

  if (board.isPending) return <main className="main"><Breadcrumb items={crumbs} /><Skeleton h={40} w={240} /><Skeleton h={300} /></main>;
  const b = board.data!;

  // C2 — 후보 0개: 에러가 아니라 판정. 판정 근거 3줄 + 다음 행동 3개.
  if (b.verdict === 'EMPTY') {
    return (
      <main className="main main--tight">
        <Breadcrumb items={crumbs} />
        <PageTitle right="verdict: EMPTY · 200">찾지 못했습니다</PageTitle>
        <RepoContext name={name} note="후보 0개" />
        <div className="verdict">
          <div className="stack grow" style={{ gap: 8 }}>
            <span className="w-600" style={{ fontSize: 15 }}>카드로 만들 덩어리가 없어요</span>
            <div className="verdict__reasons">{b.emptyReasons.map((r) => <span key={r}>· {r}</span>)}</div>
          </div>
          <div className="stack" style={{ gap: 8, alignItems: 'flex-end' }}>
            <Link to={`/repos/${repoId}/recall`} className="btn btn--outline btn--sm">파일 기준으로 회상 도와주기</Link>
            <div className="row" style={{ gap: 8 }}>
              <Link to="/cards/new" className="btn btn--outline btn--sm">직접 입력</Link>
              <Link to="/repos" className="btn btn--outline btn--sm">다른 저장소</Link>
            </div>
          </div>
        </div>
      </main>
    );
  }

  return (
    <main className="main main--footer main--tight">
      <Breadcrumb items={crumbs} />
      <PageTitle right="사람 개입 1 · 되돌릴 수 있습니다">{b.partial ? `후보 ${cands.length}개 (전체 아님)` : `후보 ${cands.length}개`}</PageTitle>
      <RepoContext name={name} note="카드로 만들 것만 골라 주세요. 나중에 다시 고를 수 있어요."
        right={<Button variant="outline" size="sm" onClick={() => setSp({ criteria: '1' })}>분석 기준</Button>} />

      {b.partial && (
        <div className="card row" style={{ gap: 16, padding: '16px 20px', background: 'var(--state-partial-bg)', border: 0, flexWrap: 'wrap' }}>
          <Badge kind="NEUTRAL">부분 결과</Badge>
          <div className="stack grow" style={{ gap: 4 }}>
            <span className="w-600" style={{ fontSize: 14 }}>GitHub 요청 한도에 걸려 끝까지 읽지 못했습니다</span>
            <span className="t-12l c-2">{b.readCoverage && `커밋 ${b.readCoverage.commitsRead}/${b.readCoverage.commitsTotal} · PR ${b.readCoverage.prsRead}/${b.readCoverage.prsTotal} 읽음`} — "이게 전부"가 아닙니다</span>
          </div>
          {b.retryAfterSeconds && <Button variant="outline" size="sm" onClick={() => setSp({ criteria: '1' })}>{minutes(b.retryAfterSeconds)} 뒤 이어 읽기</Button>}
        </div>
      )}

      <Toolbar placeholder="후보 검색 (제목·추천 이유)" value={q} onChange={setQ} sort="추천 순">
        <Button variant="text" size="sm" onClick={() => setHideUsed((v) => !v)} aria-pressed={hideUsed}>{hideUsed ? '사용됨 보이기' : '사용됨 숨기기'}</Button>
        <Button onClick={() => setSp({ add: '1' })}>+ 직접 추가</Button>
      </Toolbar>
      <SectionHead label="전체" count={visible.length} right={
        <span className="row" style={{ gap: 12 }}>
          {excluded.length > 0 && <button type="button" className="t-12 c-3" onClick={() => excluded.forEach((c) => patch.mutate({ id: c.id, status: 'NEW' }))} title="제외한 후보를 전부 되돌립니다">제외 {excluded.length}개 · 되돌리기</button>}
          <button type="button" className="t-12 w-600 c-2" onClick={() => setSelected(new Set(visible.filter((c) => c.status === 'NEW' && !c.weak).map((c) => c.id)))} title="카드감 낮음을 뺀 나머지를 전부 선택">카드감 있는 것 모두 선택</button>
          {chosen.length > 0 && <button type="button" className="t-12 c-3" onClick={() => setSelected(new Set())}>선택 해제</button>}
        </span>
      } />

      <div className="record-list">
        {visible.map((c) => (
          <CandidateRow key={c.id} c={c} selected={selected.has(c.id)} onToggle={() => toggle(c.id)} onExclude={() => exclude(c)}
            expanded={expanded === c.id} onExpand={() => setExpanded(expanded === c.id ? null : c.id)}
            onCommitToggle={(shas) => { patch.mutate({ id: c.id, excludedShas: shas }); track('cluster_commit_excluded', { candidateId: c.id, excluded: shas.length }); }} />
        ))}
        {visible.length === 0 && <Note strong="보이는 후보가 없어요" tone="inset" />}
      </div>

      <StickyFooter strong={`${chosen.length}개 선택`} sub={chosen.length ? `카드 ${chosen.length}장 · 약 ${chosen.length}분` : undefined}>
        <Link to="/" className="btn btn--outline">취소</Link>
        <Button size="lg" disabled={chosen.length === 0} loading={create.isPending} onClick={makeCards}>
          {chosen.length ? `선택한 ${chosen.length}개로 카드 만들기` : '후보를 선택하세요'}
        </Button>
      </StickyFooter>

      {sp.get('add') === '1' && <AddCandidateDialog repoId={repoId} onClose={() => setSp({})} />}
      {sp.get('criteria') === '1' && repo.data && <CriteriaDialog repoId={repoId} onClose={() => setSp({})} />}
    </main>
  );
}

function CandidateRow({ c, selected, onToggle, onExclude, expanded, onExpand, onCommitToggle }: {
  c: Candidate; selected: boolean; onToggle: () => void; onExclude: () => void; expanded: boolean; onExpand: () => void; onCommitToggle: (excludedShas: string[]) => void;
}) {
  const used = c.status === 'USED';
  const cluster = c.type === 'COMMIT_CLUSTER';
  const included = c.commits && c.commits.length > 0 ? c.commits.filter((x) => x.included).length : c.meta.commits;
  return (
    <div className={`card cand ${used ? 'card--used cand--used' : 'cand--pick'} ${selected ? 'card--selected' : ''}`}
      onClick={used ? undefined : (e) => { if ((e.target as HTMLElement).closest('button, a, input')) return; onToggle(); }} aria-selected={selected}>
      <Check checked={selected} onChange={onToggle} label={`${c.title} 선택`} disabled={used} />
      <div className="stack grow" style={{ gap: 8 }}>
        <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
          <Badge kind={c.type}>{candidateRefLabel(c.type, c.ref)}</Badge>
          <span className="cand__title">{c.title}</span>
          {used && <Badge kind="USED">{candidateStatusLabel.USED}</Badge>}
          {c.weak && !used && <Badge kind="NEUTRAL" title="문서·설정 변경 위주라 STAR 로 만들기엔 재료가 얇습니다">카드감 낮음</Badge>}
          <span className="right row" style={{ gap: 12 }}>
            {used && c.usedByCardId && <Link to={`/cards/${c.usedByCardId}`} className="t-12 w-600 c-2">카드 보기</Link>}
            {!used && <button type="button" className="btn btn--text btn--sm cand__exclude" onClick={onExclude}>제외</button>}
          </span>
        </div>
        <p className="cand__reason">추천 이유 · {c.reason}</p>
        <span className="cand__meta">커밋 {included}개 · 변경 파일 {c.meta.files}개{c.meta.reviewComments ? ` · 리뷰 코멘트 ${c.meta.reviewComments}건` : ''}{c.meta.codeRatio != null ? ` · 코드 비율 ${Math.round(c.meta.codeRatio * 100)}%` : ''}</span>
        {cluster && (
          <div className="cand__warn">
            <strong>PR이 아닙니다</strong>
            <span>시간대·디렉터리로 묶음</span>
            {c.commits && c.commits.length > 0 && <button type="button" className="right w-600" style={{ fontSize: 11, color: 'var(--text-strong)' }} onClick={onExpand} aria-expanded={expanded}>{expanded ? '접기' : `커밋 ${c.commits.length}개 펼치기`}</button>}
          </div>
        )}
        {cluster && expanded && c.commits && <ClusterCommits c={c} onChange={onCommitToggle} />}
      </div>
    </div>
  );
}

/** C3 — 군집화 오탐(E-4) 방어. 후보 확정 게이트가 이미 방어선: 잘못 묶여도 초안까지 가지 않는다. */
function ClusterCommits({ c, onChange }: { c: Candidate; onChange: (excludedShas: string[]) => void }) {
  const commits = c.commits ?? [];
  const toggle = (sha: string) => {
    const ex = commits.filter((x) => !x.included).map((x) => x.sha);
    onChange(ex.includes(sha) ? ex.filter((s) => s !== sha) : [...ex, sha]);
  };
  return (
    <div className="stack" style={{ gap: 8 }}>
      {commits.map((cm) => (
        <div key={cm.sha} className={`commit-row ${cm.included ? '' : 'commit-row--ex'}`}>
          <Check checked={cm.included} onChange={() => toggle(cm.sha)} label={`${shortSha(cm.sha)} 포함`} />
          <span className="commit-row__sha">{shortSha(cm.sha)}</span>
          <div className="stack grow" style={{ gap: 2 }}>
            <span className="commit-row__msg">{cm.message}</span>
            <span className="commit-row__path">{cm.path} · {cm.at.slice(5, 16).replace('T', ' ')}</span>
          </div>
          {!cm.included && <Badge kind="NEUTRAL">제외됨</Badge>}
          <a href={cm.url} target="_blank" rel="noreferrer" className="c-3" aria-label="GitHub에서 보기">↗</a>
        </div>
      ))}
    </div>
  );
}

/** "분석 기준 보기" — 이 레포에서 무엇을 읽고 안 읽었는지 · 언제 정리했는지 · 다시 정리 */
function CriteriaDialog({ repoId, onClose }: { repoId: string; onClose: () => void }) {
  const repo = useRepo(repoId);
  const start = useStartAnalysis();
  const nav = useNavigate();
  const r = repo.data!;
  const again = async () => {
    const { jobId } = await start.mutateAsync(repoId);
    track('analysis_started', { repoId, jobId, again: true });
    watchJob({ jobId, type: 'ANALYZE', label: `${r.name} 정리`, href: `/repos/${repoId}/candidates` });
    nav(`/repos/${repoId}/run?job=${jobId}`);
  };
  return (
    <Modal title="이 보드의 분석 기준" width={720} onClose={onClose}
      sub={r.lastAnalyzedAt ? `${ymdhm(r.lastAnalyzedAt)} 에 읽은 결과입니다. 다시 정리하면 새 커밋만 이어서 읽습니다.` : '아직 정리 기록이 없습니다.'}
      footer={{ strong: '랭킹은 메타데이터만 봤습니다', sub: '코드 원문은 후보를 확정한 다음에만 읽습니다 (2단 읽기)', actions: <><Button variant="outline" onClick={onClose}>닫기</Button><Button onClick={again} loading={start.isPending}>다시 정리하기</Button></> }}>
      <PermissionGrid compact reads={r.disclosure.reads} skips={r.disclosure.skips} />
      <div className="stack" style={{ gap: 6 }}>
        <span className="w-600" style={{ fontSize: 13 }}>순위를 매긴 기준</span>
        {['규칙으로 먼저 압축합니다 — 리뷰 코멘트 수 · 변경 파일 · 열려 있던 기간 · 코드 비율(문서만 바꾼 작업은 뒤로)', 'PR 이 없으면 같은 날 같은 디렉터리의 커밋을 한 덩어리로 묶습니다 — 이건 PR 이 아니라 "커밋 묶음"으로 표시합니다', '추천 이유를 못 쓰는 후보는 올리지 않습니다'].map((t) => (
          <div key={t} className="row" style={{ gap: 8, alignItems: 'flex-start' }}><span className="c-3">·</span><span className="t-12l c-2">{t}</span></div>
        ))}
      </div>
    </Modal>
  );
}

/** C5 — GitHub 에 없는 작업 덩어리. 커밋을 붙이면 COMMIT, 안 붙이면 USER_STATED 근거. */
function AddCandidateDialog({ repoId, onClose }: { repoId: string; onClose: () => void }) {
  const add = useAddCandidate(repoId);
  const [title, setTitle] = useState('');
  const [summary, setSummary] = useState('');
  const [cq, setCq] = useState('');
  const [shas, setShas] = useState<{ sha: string; message: string }[]>([]);
  const search = useRepoCommits(repoId, cq);
  const submit = async () => {
    const c = await add.mutateAsync({ title: title.trim(), summary: summary.trim(), shas: shas.map((s) => s.sha) });
    track('candidate_added_manually', { repoId, withCommits: shas.length });
    toast(`"${c.title}" 을 후보에 추가했습니다`, { tone: 'success' });
    onClose();
  };
  return (
    <Modal title="후보 직접 추가" onClose={onClose}
      footer={{ strong: shas.length ? '기술 카드로 생성됩니다' : '정성 카드로 생성됩니다', sub: shas.length ? `커밋 ${shas.length}건이 붙어 있습니다` : '근거는 내가 말한 것(USER_STATED)만 남습니다',
        actions: <><Button variant="outline" onClick={onClose}>취소</Button><Button disabled={!title.trim() || !summary.trim()} loading={add.isPending} onClick={submit}>후보 추가</Button></> }}>
      <Field label="후보 이름"><Input autoFocus value={title} onChange={(e) => setTitle(e.target.value)} placeholder="예) 배포 파이프라인 직접 구축" maxLength={80} /></Field>
      <Field label="무엇을 한 작업인가요"><Textarea rows={3} value={summary} onChange={(e) => setSummary(e.target.value)} placeholder="한 줄로 적어주세요." maxLength={300} /></Field>
      <Field label="관련 커밋 (선택)">
        <Input value={cq} onChange={(e) => setCq(e.target.value)} placeholder="커밋 메시지나 sha 로 검색" />
      </Field>
      {cq.trim().length >= 2 && (
        <div className="stack" style={{ gap: 6, maxHeight: 200, overflowY: 'auto' }}>
          {search.isPending && <Skeleton h={40} />}
          {search.data?.length === 0 && <span className="t-12 c-3">맞는 커밋이 없습니다</span>}
          {search.data?.map((cm) => {
            const on = shas.some((s) => s.sha === cm.sha);
            return (
              <button key={cm.sha} type="button" className={`commit-row ${on ? 'card--selected' : ''}`} style={{ padding: '8px 12px', width: '100%', textAlign: 'left' }}
                onClick={() => setShas((xs) => on ? xs.filter((s) => s.sha !== cm.sha) : [...xs, { sha: cm.sha, message: cm.message }])} aria-pressed={on}>
                <span className="commit-row__sha">{shortSha(cm.sha)}</span>
                <span className="commit-row__msg grow" style={{ fontSize: 12.5 }}>{cm.message}</span>
                {cm.candidateId && <Badge kind="NEUTRAL">다른 후보에 있음</Badge>}
              </button>
            );
          })}
        </div>
      )}
      {shas.length > 0 && <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>{shas.map((s) => <Chip key={s.sha} fill onClick={() => setShas((xs) => xs.filter((x) => x.sha !== s.sha))}>{shortSha(s.sha)} {s.message.slice(0, 18)} ✕</Chip>)}</div>}
    </Modal>
  );
}

import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useAnswerRecall, useRecall, useRepo } from '@/api/queries';
import { Breadcrumb, Button, Chip, Note, PageTitle, Skeleton, Textarea } from '@/components/ui';
import { track } from '@/lib/track';
import { toast } from '@/lib/toast';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { useUnsavedChanges } from '@/lib/useUnsavedChanges';
import { ConfirmActionDialog } from '@/features/cards/CardDialogs';

/** C4 회상 도우미 — 후보 0개일 때의 우회 경로. 파일 기준으로 기억을 끌어낸다. 답변은 USER_STATED 근거가 된다. */
export function RecallPage() {
  const { repoId = '' } = useParams();
  return <RecallSession key={repoId} repoId={repoId} />;
}

function RecallSession({ repoId }: { repoId: string }) {
  const repo = useRepo(repoId);
  const recall = useRecall(repoId);
  const answer = useAnswerRecall(repoId);
  const nav = useNavigate();
  const [open, setOpen] = useState(0);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [pendingPath, setPendingPath] = useState<string | null>(null);
  const submitting = useRef(false);
  const selected = useRef(0);
  const mounted = useRef(true);
  const latestDrafts = useRef(drafts);
  latestDrafts.current = drafts;
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const { blocker, allowNavigation } = useUnsavedChanges(Object.values(drafts).some((value) => value.trim()) || pendingPath !== null);
  const [done, setDone] = useState<Set<string>>(new Set());
  useDocumentTitle('회상 도우미');

  const dirs = recall.data?.dirs ?? [];
  const cur = dirs[open];
  const text = cur ? drafts[cur.path] ?? '' : '';
  const setText = (value: string) => { if (cur) setDrafts((previous) => ({ ...previous, [cur.path]: value })); };
  const goTo = (i: number) => { selected.current = i; setOpen(i); };
  const finish = (excludedPath?: string) => {
    const pending = dirs.findIndex((dir) => dir.path !== excludedPath && !done.has(dir.path) && latestDrafts.current[dir.path]?.trim());
    if (pending >= 0) goTo(pending);
    else if (Object.entries(latestDrafts.current).some(([path, value]) => path !== excludedPath && value.trim())) return;
    else if (excludedPath) { allowNavigation(); nav(`/repos/${repoId}/candidates`); }
    else nav(`/repos/${repoId}/candidates`);
  };
  const submit = async () => {
    if (!cur || !text.trim() || done.has(cur.path) || submitting.current) return;
    const path = cur.path;
    const index = open;
    submitting.current = true;
    setPendingPath(path);
    setErrors((previous) => ({ ...previous, [path]: '' }));
    try {
      await answer.mutateAsync({ path, text });
      if (!mounted.current) return;
      track('recall_answered', { repoId, path });
      toast('후보로 추가했습니다', { tone: 'success' });
      setDrafts((previous) => { const next = { ...previous }; delete next[path]; return next; });
      setDone((d) => new Set(d).add(path));
      if (selected.current === index) {
        if (index < dirs.length - 1) goTo(index + 1); else finish(path);
      }
    } catch {
      if (!mounted.current) return;
      setErrors((previous) => ({ ...previous, [path]: '후보를 만들지 못했어요. 입력은 보관했으니 다시 시도해 주세요.' }));
    } finally {
      if (mounted.current) { submitting.current = false; setPendingPath(null); }
    }
  };

  return (
    <main className="main main--tight recall-page">
      <Breadcrumb items={[{ label: '홈', to: '/' }, { label: repo.data?.name ?? '…', to: `/repos/${repoId}/candidates` }, { label: '회상 도우미' }]} />
      <PageTitle>파일부터 되짚어 볼까요</PageTitle>
      {recall.data && <Note strong="파일을 보며 경험을 떠올려 보세요">{recall.data.note}</Note>}
      {recall.isPending && <Skeleton h={200} />}
      {recall.isError && <QueryFailure error={recall.error} retry={() => recall.refetch()} pending={recall.isFetching} />}
      <div className="stack" style={{ gap: 10 }}>
        {dirs.map((d, i) => {
          const active = i === open;
          return (
            <div key={d.path} className={`card dir-card ${active ? 'card--selected' : ''}`}>
              <div className="row" style={{ gap: 10, flexWrap: 'wrap' }}>
                <span className="repo-ctx__icon" style={{ width: 16, height: 16, borderRadius: 4 }} aria-hidden />
                <span className="w-600" style={{ fontSize: 15 }}>{d.path}</span>
                <span className="t-12 c-3">변경 파일 {d.filesChanged}개, 내 커밋 {d.myCommits}개</span>
                <button type="button" className="right t-12 w-600 c-2" onClick={() => goTo(i)}>{done.has(d.path) ? '답함' : active ? '답하는 중' : '펼치기'}</button>
              </div>
              <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>{d.files.map((f) => <Chip key={f} fill>{f}</Chip>)}</div>
              {active && (
                <div className="stack" style={{ gap: 10, padding: '14px 16px', borderRadius: 10, background: 'var(--bg-canvas)' }}>
                  <span id={`recall-question-${i}`} className="w-600" style={{ fontSize: 15, lineHeight: '23px' }}>{d.question}</span>
                  {done.has(d.path) ? (
                    <span className="t-12 c-2">이미 후보로 만들었어요 — 다른 디렉터리를 골라 주세요.</span>
                  ) : (
                    <>
                      <span className="t-12 c-2">기억나는 만큼만 한 줄로</span>
                      <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>{d.options.map((o) => <Chip key={o} disabled={pendingPath === d.path} onClick={() => setText(o)}>{o}</Chip>)}</div>
                      <Textarea aria-labelledby={`recall-question-${i}`} disabled={pendingPath === d.path} rows={2} value={text} onChange={(e) => setText(e.target.value)} placeholder="직접 입력 — 보기를 누르면 채워지고, 그대로 고칠 수 있습니다" />
                      {errors[d.path] && <span role="alert" className="t-12 c-2">{errors[d.path]}</span>}
                    </>
                  )}
                  <div className="recall-actions">
                    <span className="t-12l c-2">디렉터리 {i + 1} / {dirs.length}</span>
                    <div className="recall-actions__buttons">
                      <Button variant="text" onClick={() => (i < dirs.length - 1 ? goTo(i + 1) : finish())}>건너뛰기</Button>
                      {!done.has(d.path) && <Button disabled={!text.trim() || pendingPath !== null} loading={pendingPath === d.path} onClick={submit}>후보로 만들기</Button>}
                    </div>
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>
      <div className="row"><Link to={`/repos/${repoId}/candidates`} className="btn btn--outline">후보 보드로</Link></div>
      {blocker.state === 'blocked' && <ConfirmActionDialog title="입력한 내용을 버리고 이동할까요?" sub="제출하지 않은 회상 입력은 저장되지 않습니다. 진행 중인 요청은 완료될 수 있습니다." body={<p>계속 작성하려면 이 화면에 머물러 주세요.</p>} actionLabel="입력 버리고 이동" danger onClose={() => blocker.reset()} onConfirm={() => blocker.proceed()} />}
    </main>
  );
}

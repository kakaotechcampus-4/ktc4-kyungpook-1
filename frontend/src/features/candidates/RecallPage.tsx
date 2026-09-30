import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useAnswerRecall, useRecall, useRepo } from '@/api/queries';
import { Breadcrumb, Button, Chip, Note, PageTitle, Skeleton, Textarea } from '@/components/ui';
import { track } from '@/lib/track';
import { toast } from '@/lib/toast';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { QueryFailure } from '@/components/ui/QueryFailure';

/** C4 회상 도우미 — 후보 0개일 때의 우회 경로. 파일 기준으로 기억을 끌어낸다. 답변은 USER_STATED 근거가 된다. */
export function RecallPage() {
  const { repoId = '' } = useParams();
  const repo = useRepo(repoId);
  const recall = useRecall(repoId);
  const answer = useAnswerRecall(repoId);
  const nav = useNavigate();
  const [open, setOpen] = useState(0);
  const [text, setText] = useState('');
  const [done, setDone] = useState<Set<string>>(new Set());
  useDocumentTitle('회상 도우미');

  const dirs = recall.data?.dirs ?? [];
  const cur = dirs[open];
  // text 는 디렉터리 하나에 공용이다 — 다른 디렉터리로 넘어갈 때 지우지 않으면 이전 답이 새 질문칸에 그대로 남아
  // 엉뚱한 디렉터리의 근거로 제출될 수 있다. 펼치기/건너뛰기/제출 뒤 이동은 전부 이 함수를 거친다.
  const goTo = (i: number) => { setOpen(i); setText(''); };
  const submit = async () => {
    if (!cur || done.has(cur.path)) return;
    await answer.mutateAsync({ path: cur.path, text });
    track('recall_answered', { repoId, path: cur.path });
    toast('후보로 추가했습니다 — 답변은 그대로 근거(USER_STATED)가 됩니다', { tone: 'success' });
    setDone((d) => new Set(d).add(cur.path));
    if (open < dirs.length - 1) goTo(open + 1); else nav(`/repos/${repoId}/candidates`);
  };

  return (
    <main className="main main--tight">
      <Breadcrumb items={[{ label: '경험정리/홈', to: '/' }, { label: repo.data?.name ?? '…', to: `/repos/${repoId}/candidates` }, { label: '회상 도우미' }]} />
      <PageTitle right="후보 0개일 때의 우회 경로">파일부터 되짚어 볼까요</PageTitle>
      {recall.data && <Note strong="커밋 메시지로는 못 찾았습니다">{recall.data.note}</Note>}
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
                <span className="t-12 c-3">변경 파일 {d.filesChanged}개 · 내 커밋 {d.myCommits}개</span>
                <button type="button" className="right t-12 w-600 c-2" onClick={() => goTo(i)}>{done.has(d.path) ? '답함' : active ? '답하는 중' : '펼치기'}</button>
              </div>
              <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>{d.files.map((f) => <Chip key={f} fill>{f}</Chip>)}</div>
              {active && (
                <div className="stack" style={{ gap: 10, padding: '14px 16px', borderRadius: 10, background: 'var(--bg-canvas)' }}>
                  <span className="w-600" style={{ fontSize: 15, lineHeight: '23px' }}>{d.question}</span>
                  {done.has(d.path) ? (
                    <span className="t-12 c-2">이미 후보로 만들었어요 — 다른 디렉터리를 골라 주세요.</span>
                  ) : (
                    <>
                      <span className="t-12 c-2">기억나는 만큼만 한 줄로</span>
                      <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>{d.options.map((o) => <Chip key={o} onClick={() => setText(o)}>{o}</Chip>)}</div>
                      <Textarea rows={2} value={text} onChange={(e) => setText(e.target.value)} placeholder="직접 입력 — 보기를 누르면 채워지고, 그대로 고칠 수 있습니다" />
                    </>
                  )}
                  <div className="row" style={{ gap: 10 }}>
                    <span className="t-12l c-2">디렉터리 {i + 1} / {dirs.length}</span>
                    <div className="right row" style={{ gap: 8 }}>
                      <Button variant="text" onClick={() => (i < dirs.length - 1 ? goTo(i + 1) : nav(`/repos/${repoId}/candidates`))}>건너뛰기</Button>
                      {!done.has(d.path) && <Button disabled={!text.trim()} loading={answer.isPending} onClick={submit}>후보로 만들기</Button>}
                    </div>
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>
      <div className="row"><Link to={`/repos/${repoId}/candidates`} className="btn btn--outline">후보 보드로</Link></div>
    </main>
  );
}

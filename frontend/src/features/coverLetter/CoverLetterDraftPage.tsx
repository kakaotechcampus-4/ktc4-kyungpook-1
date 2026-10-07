import { useCallback, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { Sparkles, UserRound } from 'lucide-react';
import { matchSupported } from '@/api/capabilities';
import { ApiError } from '@/api/client';
import { useCoverLetter, useSaveCoverLetter } from '@/api/queries';
import type { CoverLetter } from '@/api/schemas';
import { SaveStatus, UnsavedChangesDialog } from '@/components/SaveStatus';
import { Badge, Breadcrumb, Button, EmptyState, IconBox, Note, PageTitle, Skeleton, StickyFooter, Textarea } from '@/components/ui';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { FeaturePending } from '@/features/match/shared';
import { copyText } from '@/lib/exportCard';
import { coverLetterQuestionLabel } from '@/lib/labels';
import { toast } from '@/lib/toast';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { useDraftAutosave } from '@/lib/useDraftAutosave';
import { useUnsavedChanges } from '@/lib/useUnsavedChanges';

const titleOf = (letter: CoverLetter) => `${letter.company ?? '지원 대상 없음'} · ${coverLetterQuestionLabel[letter.question]}`;

/** 자소서 초안 — 문장마다 출처(내 카드 / AI 연결 문장)를 보여주고, 직접 고치면 자동 저장한다. */
export function CoverLetterDraftPage() {
  const { letterId = '' } = useParams();
  const [sp, setSp] = useSearchParams();
  const query = useCoverLetter(letterId);
  const letter = query.data;
  useDocumentTitle(letter ? `자소서 · ${titleOf(letter)}` : '자소서 초안');
  if (!matchSupported()) return <FeaturePending />;

  const crumbs = [{ label: '경험정리/홈', to: '/' }, { label: '자소서 초안', to: '/cover-letter' }, { label: letter ? titleOf(letter) : '…' }];
  if (query.isError && !letter) {
    const gone = query.error instanceof ApiError && query.error.status === 404;
    return (
      <main className="main">
        <Breadcrumb items={crumbs} />
        {gone
          ? <EmptyState title="자소서 초안을 찾을 수 없어요" desc="삭제됐거나 접근할 수 없는 초안이에요."><Link to="/cover-letter" className="btn btn--outline">초안 목록으로</Link></EmptyState>
          : <QueryFailure error={query.error} retry={() => query.refetch()} pending={query.isFetching} />}
      </main>
    );
  }
  if (!letter) return <main className="main"><Breadcrumb items={crumbs} /><Skeleton h={40} w={320} /><Skeleton h={320} /></main>;

  const editing = sp.get('mode') === 'edit';
  const closeEditor = () => setSp((current) => { current.delete('mode'); return current; });
  const copy = async () => {
    if (await copyText(letter.text)) { track('cover_letter_copied', { letterId: letter.id }); toast('자소서 초안을 복사했어요', { tone: 'success' }); }
    else toast('복사하지 못했어요. 직접 선택해서 복사해 주세요.', { tone: 'danger' });
  };
  const retry = `/cover-letter?${new URLSearchParams({ ...(letter.matchId ? { match: letter.matchId } : {}), question: letter.question })}`;

  return (
    <main className={`main main--tight ${editing ? 'main--footer' : ''}`}>
      <Breadcrumb items={crumbs} />
      <PageTitle sub={<><Badge kind="DRAFT">{letter.edited ? '직접 고침' : 'AI 초안'}</Badge><span className="t-12 c-3">{letter.role ?? '지원 직무 없음'}</span></>}
        right={!editing && (
          <span className="row" style={{ gap: 8 }}>
            <Button variant="outline" size="sm" onClick={() => void copy()}>복사</Button>
            <Button variant="outline" size="sm" onClick={() => setSp({ mode: 'edit' })}>초안 고치기</Button>
          </span>
        )}>{titleOf(letter)}</PageTitle>
      {letter.stale && (
        <Note strong="초안을 만든 뒤 사용한 카드가 바뀌었어요" tone="caution">
          카드의 내용·마스킹이 이 초안에 반영되지 않았을 수 있어요. 복사하기 전에 <Link to={retry} className="w-600" style={{ textDecoration: 'underline' }}>다시 만들어</Link> 주세요.
        </Note>
      )}
      {letter.gaps.length > 0 && (
        <Note strong={`근거가 없어 초안에 넣지 않은 인재상 ${letter.gaps.length}개`} tone="caution">
          {letter.gaps.join(' · ')} — 지어내지 않았어요. <Link to="/cards/new" className="w-600" style={{ textDecoration: 'underline' }}>카드를 더 써서 채우기</Link>
        </Note>
      )}
      {letter.notUsed.length > 0 && (
        <Note strong={`근거 카드는 있지만 이번에 고르지 않은 인재상 ${letter.notUsed.length}개`} tone="inset">
          {letter.notUsed.join(' · ')} — <Link to={retry} className="w-600" style={{ textDecoration: 'underline' }}>카드를 골라 다시 만들기</Link>
        </Note>
      )}
      {editing
        ? <Editor key={letter.id} letter={letter} onDone={closeEditor} />
        : <ReadView letter={letter} retry={retry} />}
    </main>
  );
}

function ReadView({ letter, retry }: { letter: CoverLetter; retry: string }) {
  return (
    <>
      {letter.edited ? (
        <div className="card stack" style={{ gap: 12, padding: '20px' }}>
          {letter.text.split(/\n{2,}/).map((paragraph, index) => <p key={index} className="star__text">{paragraph}</p>)}
        </div>
      ) : (
        <div className="card star-read">
          {letter.paragraphs.map((paragraph, index) => (
            <div key={index} className="star-read__row">
              <div className="star-row__heading">
                <div className="star-row__label">
                  <IconBox icon={paragraph.kind === 'EVIDENCE' ? UserRound : Sparkles} size={28} tone={paragraph.kind === 'EVIDENCE' ? 'ink' : 'subtle'} />
                  {paragraph.kind === 'EVIDENCE' && paragraph.cardId
                    ? <Link to={`/cards/${paragraph.cardId}`} className="star__name">내 경험 · {paragraph.cardTitle}</Link>
                    : <span className="star__name">연결 문장</span>}
                </div>
                <div className="star-row__meta"><Badge kind="NEUTRAL">{paragraph.kind === 'EVIDENCE' ? '확정한 카드 문장' : 'AI가 이은 문장'}</Badge></div>
              </div>
              <div className="star-row__body"><p className="star__text">{paragraph.text}</p></div>
            </div>
          ))}
        </div>
      )}
      {letter.edited && (
        <Note strong="직접 고친 초안이라 문장별 출처 표시는 숨겼어요" tone="inset">
          처음에 쓴 카드: {letter.paragraphs.filter((p) => p.cardId).map((p, i) => <span key={p.cardId}>{i > 0 && ', '}<Link to={`/cards/${p.cardId}`} className="w-600" style={{ textDecoration: 'underline' }}>{p.cardTitle}</Link></span>)}
        </Note>
      )}
      <div className="row" style={{ gap: 8 }}>
        <Link to="/cover-letter" className="btn btn--outline">초안 목록</Link>
        <Link to={retry} className="btn btn--text">다시 만들기</Link>
      </div>
    </>
  );
}

function Editor({ letter, onDone }: { letter: CoverLetter; onDone: () => void }) {
  const saveMutation = useSaveCoverLetter(letter.id);
  const [text, setText] = useState(letter.text);
  const save = useCallback((value: string) => saveMutation.mutateAsync(value), [saveMutation]);
  const autosave = useDraftAutosave(text, save);
  const { flush, saving, failed } = autosave;
  const guard = useUnsavedChanges(autosave.dirty || saving);
  const done = async () => {
    if (!await flush()) return;
    if (text !== letter.text) track('cover_letter_edited', { letterId: letter.id, length: text.length }); // 고치지 않고 닫은 건 편집이 아니다
    guard.allowNavigation();
    onDone();
  };
  return (
    <>
      <Textarea className="input--lg" rows={16} maxLength={10_000} value={text} aria-label="자소서 초안" onChange={(event) => setText(event.target.value)} />
      <SaveStatus status={autosave.status} retry={flush} />
      <StickyFooter strong={`${text.length.toLocaleString()}자`} sub={failed ? '연결을 확인하고 다시 눌러 주세요' : '쓰는 동안 알아서 저장돼요'}>
        <Button size="lg" loading={saving} onClick={done}>{failed ? '다시 저장하고 닫기' : '저장하고 닫기'}</Button>
      </StickyFooter>
      <UnsavedChangesDialog blocker={guard.blocker} saving={saving} flush={flush} />
    </>
  );
}

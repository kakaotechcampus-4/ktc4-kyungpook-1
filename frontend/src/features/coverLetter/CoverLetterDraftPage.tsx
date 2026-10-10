import { useCallback, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { PenLine, Sparkles, UserRound } from 'lucide-react';
import { matchSupported } from '@/api/capabilities';
import { ApiError } from '@/api/client';
import { useCoverLetter, useSaveCoverLetter } from '@/api/queries';
import type { CoverLetter } from '@/api/schemas';
import { SaveStatus, UnsavedChangesDialog } from '@/components/SaveStatus';
import { Badge, Breadcrumb, Button, EmptyState, IconBox, Note, PageTitle, Skeleton, StickyFooter, Textarea } from '@/components/ui';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { FeaturePending } from '@/features/match/shared';
import { charCount, hasEmptySlot } from '@/lib/coverLetter';
import { copyText } from '@/lib/exportCard';
import { coverLetterQuestionLabel } from '@/lib/labels';
import { toast } from '@/lib/toast';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { useDraftAutosave } from '@/lib/useDraftAutosave';
import { useUnsavedChanges } from '@/lib/useUnsavedChanges';

const titleOf = (letter: CoverLetter) => `${letter.company ?? '지원 대상 없음'} · ${coverLetterQuestionLabel[letter.question]}`;

/** 글자 수(공백 포함) — 제한이 있으면 `현재 / 제한`. */
const countLabel = (text: string, limit: number | null) => `${charCount(text).toLocaleString()}자${limit !== null ? ` / ${limit.toLocaleString()}자` : ''}`;
const isOver = (text: string, limit: number | null) => limit !== null && charCount(text) > limit;
function CharCount({ text, limit }: { text: string; limit: number | null }) {
  return <Badge kind={isOver(text, limit) ? 'CAUTION' : 'NEUTRAL'}>{countLabel(text, limit)}</Badge>;
}

/** 직접 쓸 칸이 비어 있거나 글자 수 제한을 넘었을 때의 안내 — 읽기 화면은 저장된 본문, 편집 중에는 지금 쓰는 글 기준이다. */
function LengthNotes({ text, limit }: { text: string; limit: number | null }) {
  const count = charCount(text);
  return (
    <>
      {hasEmptySlot(text) && (
        <Note strong="지원 동기 칸이 아직 비어 있어요" tone="caution">
          지원 동기는 AI가 대신 쓰지 않았어요. 표식 자리에 지원하는 이유를 직접 써 주세요.
        </Note>
      )}
      {limit !== null && count > limit && (
        <Note strong={`글자 수 제한을 ${(count - limit).toLocaleString()}자 넘었어요`} tone="caution">
          공백 포함 {count.toLocaleString()}자예요(제한 {limit.toLocaleString()}자). 카드 문장을 줄이거나 직접 고쳐서 맞춰 주세요.
        </Note>
      )}
    </>
  );
}

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
    if (await copyText(letter.text)) {
      track('cover_letter_copied', { letterId: letter.id });
      toast(hasEmptySlot(letter.text) ? '복사했어요. 지원 동기 칸은 아직 비어 있어요' : '자소서 초안을 복사했어요', { tone: 'success' });
    }
    else toast('복사하지 못했어요. 직접 선택해서 복사해 주세요.', { tone: 'danger' });
  };
  const retry = `/cover-letter?${new URLSearchParams({ ...(letter.matchId ? { match: letter.matchId } : {}), question: letter.question, ...(letter.charLimit ? { limit: String(letter.charLimit) } : {}) })}`;

  return (
    <main className={`main main--tight ${editing ? 'main--footer' : ''}`}>
      <Breadcrumb items={crumbs} />
      <PageTitle sub={<><Badge kind="DRAFT">{letter.edited ? '직접 고침' : 'AI 초안'}</Badge><span className="t-12 c-3">{letter.role ?? '지원 직무 없음'}</span>{!editing && <CharCount text={letter.text} limit={letter.charLimit} />}</>}
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
      {!editing && <LengthNotes text={letter.text} limit={letter.charLimit} />}
      {editing
        ? <Editor key={letter.id} letter={letter} onDone={closeEditor} />
        : <ReadView letter={letter} retry={retry} onEdit={() => setSp({ mode: 'edit' })} />}
    </main>
  );
}

function ReadView({ letter, retry, onEdit }: { letter: CoverLetter; retry: string; onEdit: () => void }) {
  return (
    <>
      {letter.edited ? (
        <div className="card stack" style={{ gap: 12, padding: '20px' }}>
          {letter.text.split(/\n{2,}/).map((paragraph, index) => <p key={index} className="star__text">{paragraph}</p>)}
        </div>
      ) : (
        <div className="card star-read">
          {letter.paragraphs.map((paragraph, index) => {
            const slot = paragraph.kind === 'SLOT';
            const evidence = paragraph.kind === 'EVIDENCE';
            return (
              <div key={index} className={`star-read__row ${slot ? 'star-read__row--gap' : ''}`}>
                <div className="star-row__heading">
                  <div className="star-row__label">
                    <IconBox icon={slot ? PenLine : evidence ? UserRound : Sparkles} size={28} tone={evidence ? 'ink' : 'subtle'} />
                    {evidence && paragraph.cardId
                      ? <Link to={`/cards/${paragraph.cardId}`} className="star__name">내 경험 · {paragraph.cardTitle}</Link>
                      : <span className="star__name">{slot ? '직접 쓸 칸 · 지원 동기' : '연결 문장'}</span>}
                  </div>
                  <div className="star-row__meta"><Badge kind={slot ? 'CAUTION' : 'NEUTRAL'}>{slot ? '아직 비어 있어요' : evidence ? '확정한 카드 문장' : 'AI가 이은 문장'}</Badge></div>
                </div>
                <div className="star-row__body">
                  {slot
                    ? (
                      <>
                        <p className="t-12l c-2">지원 동기는 AI가 대신 쓰지 않아요. {paragraph.text} 자리에 직접 써 주세요.</p>
                        <div><Button variant="outline" size="sm" onClick={onEdit}>직접 쓰러 가기</Button></div>
                      </>
                    )
                    : <p className="star__text">{paragraph.text}</p>}
                </div>
              </div>
            );
          })}
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
      <LengthNotes text={text} limit={letter.charLimit} />
      <span className="sr-only" role="status">{[hasEmptySlot(text) && '지원 동기 칸이 아직 비어 있어요', isOver(text, letter.charLimit) && '글자 수 제한을 넘었어요'].filter(Boolean).join('. ')}</span>
      <Textarea className="input--lg" rows={16} maxLength={10_000} value={text} aria-label="자소서 초안" onChange={(event) => setText(event.target.value)} />
      <SaveStatus status={autosave.status} retry={flush} />
      <StickyFooter strong={countLabel(text, letter.charLimit)}
        sub={failed ? '연결을 확인하고 다시 눌러 주세요' : isOver(text, letter.charLimit) ? '글자 수 제한을 넘었어요 · 쓰는 동안 알아서 저장돼요' : '쓰는 동안 알아서 저장돼요'}>
        <Button size="lg" loading={saving} onClick={done}>{failed ? '다시 저장하고 닫기' : '저장하고 닫기'}</Button>
      </StickyFooter>
      <UnsavedChangesDialog blocker={guard.blocker} saving={saving} flush={flush} />
    </>
  );
}

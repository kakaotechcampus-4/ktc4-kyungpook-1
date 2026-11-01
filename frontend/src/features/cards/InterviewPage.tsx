import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';
import { errorView } from '@/api/errorView';
import { useAnswerInterview, useAskInterview, useCard, useInterview, useSkipInterview } from '@/api/queries';
import { StarField as StarFieldSchema, isOpenTurn, type EvidenceType, type StarField } from '@/api/schemas';
import { Badge, Breadcrumb, Button, EvidenceList, Note, PageTitle, Skeleton, Textarea } from '@/components/ui';
import { AiWait } from '@/components/ui/AiWait';
import { STAR_FIELDS, evidenceTypeLabel, fieldKey, starFieldName, starFieldShort } from '@/lib/labels';
import { CONFIG } from '@/lib/config';
import { toast } from '@/lib/toast';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { useUnsavedChanges } from '@/lib/useUnsavedChanges';
import { StarRow } from '@/components/ui/StarRow';
import { UnsavedChangesDialog } from '@/components/SaveStatus';

/**
 * D5 되묻기 — 레퍼런스(TIO 경험 정리) 구조: 좌측 캔버스에 카드가 채워지고, 우측 패널이 한 번에 하나씩 묻는다.
 * 코드에서 찾은 것 / 없는 것을 먼저 보여주고 한 줄만 묻는다. 보기 선택 = USER_SELECTED · 직접 입력 = USER_STATED.
 * 답변은 가공 없이 그대로 저장(매 턴 새 버전). 꼬리질문은 상한(Q6)까지.
 */
export function InterviewPage() {
  const { cardId = '' } = useParams();
  const [sp] = useSearchParams();
  const location = useLocation();
  const nav = useNavigate();
  const { card: q } = useCard(cardId);
  const turns = useInterview(cardId);
  const ask = useAskInterview(cardId);
  const answer = useAnswerInterview(cardId);
  const skip = useSkipInterview(cardId);
  const [text, setText] = useState('');
  const [picked, setPicked] = useState<string | null>(null);
  const guard = useUnsavedChanges(!!text.trim() || answer.isPending);
  const [folded, setFolded] = useState(true); // 모바일에서는 미리보기를 접고 질문부터 보여준다
  const autoAsked = useRef(false);
  // AI 가 더 물을 것이 없다고 끝낸 상태. 사용자가 칸을 골라 다시 물으면 풀린다
  const [aiDone, setAiDone] = useState(false);
  useDocumentTitle(q.data ? `답변하기 - ${q.data.title}` : '답변하기');

  const parsedField = StarFieldSchema.safeParse(sp.get('field'));
  const field: StarField | null = parsedField.success ? parsedField.data : null;
  const allTurns = turns.data ?? [];
  const open = allTurns.find(isOpenTurn) ?? null;
  const finished = allTurns.filter((t) => !isOpenTurn(t));
  // 넘기거나 '측정 안 함' 으로 끝난 칸은 서버가 다시 묻지 않으므로(409 INTERVIEW_FIELD_CLOSED) 고를 칸에서 뺀다
  const closedFields = new Set(allTurns.filter((t) => t.outcome === 'SKIPPED' || t.outcome === 'NOT_MEASURED').map((t) => t.field));
  // 열린 턴이 이 화면의 submit() 이 아니라 백그라운드 리페치(다른 탭·기기가 먼저 답함, 포커스 복귀)로 바뀌면
  // 입력칸의 옛 텍스트가 새 질문 아래 그대로 남아, 엉뚱한 turnNo/field 에 제출될 수 있다 — 턴이 바뀌면 비운다.
  useEffect(() => { setText(''); setPicked(null); }, [open?.turnNo]);
  // 질문 상한은 서버 정책이다. 화면은 응답에 실려 온 값을 쓰고, 응답 전에만 기본값으로 버틴다.
  const maxTurns = open?.maxTurns ?? allTurns[allTurns.length - 1]?.maxTurns ?? CONFIG.INTERVIEW_MAX_TURNS_FALLBACK;
  // 넘긴 질문도 질문 상한에 센다(서버 규칙)
  const capReached = allTurns.length >= maxTurns;

  // 칸을 정해 들어오면 그 칸을, 처음인데 칸이 없으면 AI 가 고른 칸을 묻는다
  useEffect(() => {
    if (turns.isSuccess && !open && !autoAsked.current && !capReached && (field || allTurns.length === 0)) {
      autoAsked.current = true;
      ask.mutate(field ?? undefined, {
        onSuccess: (turn) => turn ? track('interview_asked', { cardId, field: turn.field }) : setAiDone(true),
      });
    }
  }, [turns.isSuccess, open, field, ask, cardId, capReached, allTurns.length]);

  // 답하거나 넘긴 뒤에는 칸을 정하지 않고 묻는다. AI 가 다음 칸을 고르거나, 더 물을 것이 없으면 null 로 끝낸다
  const askNext = (turnsAfter: number) => {
    if (turnsAfter >= maxTurns) return;
    ask.mutate(undefined, {
      onSuccess: (turn) => turn ? track('interview_asked', { cardId, field: turn.field, followUp: true }) : setAiDone(true),
    });
  };

  const card = q.data;
  const submit = async () => {
    if (!open || !text.trim() || answer.isPending) return false;
    try {
    const source: EvidenceType = picked === text ? 'USER_SELECTED' : 'USER_STATED';
    const saved = await answer.mutateAsync({ turnNo: open.turnNo, text, source });
    track('interview_answered', { cardId, field: open.field, source, turnNo: open.turnNo });
    toast(`${starFieldShort[open.field]} 답변을 저장했어요`, { tone: 'success' });
    setText(''); setPicked(null);
    // 답으로 네 칸이 다 차면 서버가 바로 확정하고(#624) 확정 카드는 더 물을 수 없으므로 다음 질문을 요청하지 않는다
    if (saved.status !== 'CONFIRMED') askNext(allTurns.length);
    return true;
    } catch { return false; }
  };
  const skipOpen = () => {
    if (!open || skip.isPending || answer.isPending) return;
    skip.mutate(open.turnNo, {
      onSuccess: (saved) => {
        toast(`${starFieldShort[open.field]} 질문을 넘겼어요`);
        setText(''); setPicked(null);
        if (saved.status !== 'CONFIRMED') askNext(allTurns.length);
      },
    });
  };
  const askMore = (f: StarField) => { setAiDone(false); ask.mutate(f, { onSuccess: () => track('interview_asked', { cardId, field: f, followUp: true }) }); };

  if ((q.isError && !q.data) || (turns.isError && !turns.data)) return <main className="main"><QueryFailure error={q.error ?? turns.error} retry={() => Promise.all([q.refetch(), turns.refetch()])} pending={q.isFetching || turns.isFetching} /><Link to="/cards" className="btn btn--outline">경험 카드 목록</Link></main>;
  if (!card || turns.isPending) return <main className="main"><Skeleton h={16} w={300} /><Skeleton h={300} /></main>;
  const total = allTurns.length;
  const crumbs = [{ label: '홈', to: '/' }, ...(card.repo ? [{ label: card.repo.name, to: `/repos/${card.repo.id}/candidates` }] : []), { label: card.title, to: `/cards/${cardId}`, state: location.state }, { label: '답변하기' }];
  const askable = STAR_FIELDS.filter((f) => !closedFields.has(f));
  const weakFields = askable.filter((f) => !card.version[fieldKey[f]] || card.lowConfidenceFields.some((l) => l.field === f));
  const anyFilled = STAR_FIELDS.some((f) => card.version[fieldKey[f]]);

  return (
    <main className="main main--tight">
      <Breadcrumb items={crumbs} />
      <PageTitle right={`질문 ${total} / ${maxTurns}`}>답변하기</PageTitle>

      <div className="iv">
        {/* 좌측 캔버스 — 답할수록 채워지는 카드 */}
        <section className={`card iv__canvas ${folded ? 'iv__canvas--folded' : ''}`} aria-label="카드 미리보기">
          <div className="row" style={{ gap: 10, padding: '16px 20px', borderBottom: '1px solid var(--border-default)' }}>
            <span className="w-600" style={{ fontSize: 16 }}>{card.title}</span>
            <button type="button" className="iv__fold right" aria-expanded={!folded} onClick={() => setFolded((x) => !x)}>
              {folded ? '지금까지 쓴 카드 보기' : '접기'}
            </button>
          </div>
          {!anyFilled && <div className="iv__empty">이곳에 답변이 채워져요</div>}
          <div className="star-read">
            {STAR_FIELDS.map((f) => {
              const t = card.version[fieldKey[f]];
              const pending = open?.field === f;
              const ev = card.evidence.filter((e) => e.field === f);
              return (
                <StarRow key={f} field={f} label={starFieldName[f]} dropped={!t}
                  className={`${!t ? 'star-read__row--gap' : ''} ${pending ? 'star-read__row--now' : ''}`}
                  metadata={pending && <Badge kind="PR">지금 채우는 칸</Badge>}>
                  {t ? <p className="star__text">{t}</p> : <span className="c-3" style={{ fontSize: 13 }}>{pending ? '질문에 답하면 이 칸에 저장돼요' : '비어 있음'}</span>}
                  <EvidenceList items={ev} label={starFieldName[f]} sentence={t} />
                </StarRow>
              );
            })}
          </div>
        </section>

        {/* 우측 패널 — 한 번에 하나만 묻는다 */}
        <aside className="card iv__panel">
          <div className="row" style={{ gap: 8 }}>
            <Link to={`/cards/${cardId}`} state={location.state} className="btn btn--ghost btn--sm"><ArrowLeft size={14} aria-hidden /> 카드로</Link>
          </div>
          {answer.isError && <Note strong="답변이 저장되지 않았어요" tone="danger">{errorView(answer.error).message}</Note>}
          {skip.isError && <Note strong="질문을 넘기지 못했어요" tone="danger">{errorView(skip.error).message}</Note>}
          {ask.isError && <QueryFailure error={ask.error} retry={() => ask.variables && askMore(ask.variables)} pending={ask.isPending} />}

          {finished.map((t) => (
            <div key={t.turnNo} className="stack" style={{ gap: 6, padding: '10px 12px', borderRadius: 16, background: 'var(--bg-paper)' }}>
              <div className="row" style={{ gap: 8 }}><span className="turn__no" style={{ width: 20, height: 20, fontSize: 11 }}>{t.turnNo}</span><Badge kind="NEUTRAL">{t.field} 칸</Badge><span className="t-12 c-2">{t.question}</span></div>
              {t.answer ? (
                <>
                  <span style={{ fontSize: 13, lineHeight: '20px' }}>{t.answer.text}</span>
                  <span className="t-12 c-3">{evidenceTypeLabel[t.answer.source]}, 답변 저장됨</span>
                </>
              ) : (
                <span className="t-12 c-3">{t.outcome === 'SKIPPED' ? '넘긴 질문' : '측정하지 않음으로 기록'}</span>
              )}
            </div>
          ))}

          {open ? (
            <>
              <div className="stack" style={{ gap: 6 }}>
                <div className="row" style={{ gap: 8 }}><span className="turn__no turn__no--now" style={{ width: 22, height: 22 }}>{open.turnNo}</span><Badge kind={card.lowConfidenceFields.some((l) => l.field === open.field) ? 'CAUTION' : 'PR'}>{open.field} 칸</Badge><span className="t-12 c-2">{starFieldName[open.field]}</span></div>
                <h2 className="iv__q">Q{open.turnNo}. {open.question}</h2>
                <details className="interview-evidence"><summary>질문 근거 보기</summary>
                <div className="found" style={{ gridTemplateColumns: '1fr' }}>
                  <div className="found__box"><h4>코드에서 찾은 것</h4>{open.found.map((f, i) => <span key={i}>• {f}</span>)}</div>
                  <div className="found__box found__box--miss"><h4>코드에 없는 것</h4>{open.missing.map((m, i) => <span key={i}>• {m}</span>)}</div>
                </div>
                </details>
              </div>
              {open.options.length > 0 && (
                <div className="stack" style={{ gap: 6 }}>
                  <span className="t-12 w-600 c-3" style={{ fontSize: 11 }}>이 중에 고르셔도 돼요</span>
                  {open.options.map((o) => <button key={o} type="button" className="chip chip--option" disabled={answer.isPending} aria-pressed={picked === o} onClick={() => { setPicked(o); setText(o); }}>{o}</button>)}
                </div>
              )}
              <Textarea rows={4} value={text} disabled={answer.isPending} onChange={(e) => { setText(e.target.value); if (picked && e.target.value !== picked) setPicked(null); }}
                onKeyDown={(e) => { if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') void submit(); }}
                placeholder="한 문장으로" aria-label="답변" maxLength={500} />
              <div className="row" style={{ gap: 8 }}>
                <span className="t-12 c-3 grow">{text ? `${text.length}/500` : ''}</span>
                <Button variant="ghost" size="sm" loading={skip.isPending} disabled={answer.isPending} onClick={skipOpen}>건너뛰기</Button>
                <Button size="lg" disabled={!text.trim()} loading={answer.isPending} onClick={submit}>답변 저장</Button>
              </div>
              {answer.isPending && <AiWait label={`답변을 ${starFieldShort[open.field]} 칸 문장으로 다듬어 카드에 넣는 중이에요`} />}
            </>
          ) : ask.isPending ? <AiWait label={`카드와 커밋을 읽고 ${ask.variables ? starFieldShort[ask.variables] : ''} 칸에 맞는 질문을 만드는 중이에요`} /> : (
            <>
              {capReached ? (
                <Note strong={`질문 ${maxTurns}개를 다 썼어요`} tone="inset">남은 칸은 직접 고쳐서 채우시면 돼요</Note>
              ) : (
                <div className="stack" style={{ gap: 8 }}>
                  {aiDone && <Note strong="AI 가 더 물을 것이 없다고 판단했어요" tone="inset">카드를 확인하고 확정하세요. 더 채우고 싶은 칸이 있으면 골라서 물을 수 있어요.</Note>}
                  <span className="w-600" style={{ fontSize: 14 }}>{aiDone ? '더 묻고 싶은 칸' : finished.length ? '이어서 물을까요?' : '어느 칸을 채울까요?'}</span>
                  <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                    {!aiDone && <Button variant="outline" size="sm" onClick={() => askNext(allTurns.length)}>AI 가 고르게 하기</Button>}
                    {(weakFields.length ? weakFields : askable).map((f) => <Button key={f} variant="outline" size="sm" onClick={() => askMore(f)}>{starFieldShort[f]}{card.version[fieldKey[f]] ? (card.lowConfidenceFields.some((l) => l.field === f) ? ' (확인 필요)' : '') : ' (비어 있음)'}</Button>)}
                  </div>
                </div>
              )}
              <div className="row" style={{ gap: 8 }}>
                {aiDone || capReached
                  ? <Button onClick={() => nav(`/cards/${cardId}?confirm=1`, { state: location.state })}>카드 확인하고 확정하기</Button>
                  : <Button onClick={() => nav(`/cards/${cardId}`, { state: location.state })}>카드로 돌아가기</Button>}
                <Button variant="ghost" onClick={() => nav(`/cards/${cardId}?mode=edit`, { state: location.state })}>직접 수정</Button>
              </div>
            </>
          )}
        </aside>
      </div>
      <UnsavedChangesDialog blocker={guard.blocker} saving={answer.isPending} flush={submit} />
    </main>
  );
}

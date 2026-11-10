import { Fragment, useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';
import { errorView } from '@/api/errorView';
import { useAnswerInterview, useAskInterview, useCard, useInterview, useReplyInterview, useRestoreVersion, useSaveDraft, useSkipInterview } from '@/api/queries';
import { keys } from '@/api/keys';
import { StarField as StarFieldSchema, isOpenTurn, type DraftFields, type EvidenceType, type StarField } from '@/api/schemas';
import { Bubble, ChatFrom, Composer } from '@/components/chat/Chat';
import { scrollChatTo } from '@/components/chat/chatScroll';
import { Badge, Breadcrumb, Button, EvidenceList, Note, PageTitle, Skeleton } from '@/components/ui';
import { AiWait } from '@/components/ui/AiWait';
import { STAR_FIELDS, evidenceTypeLabel, fieldKey, starFieldName, starFieldShort, starFieldSpoken } from '@/lib/labels';
import { askJudgement, cleanAiText } from '@/lib/interviewReason';
import { CONFIG } from '@/lib/config';
import { toast } from '@/lib/toast';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { useReducedMotion } from '@/lib/useLiveText';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { useUnsavedChanges } from '@/lib/useUnsavedChanges';
import { cardFolderPath, folderPath } from '@/lib/cardFolders';
import { StarRow } from '@/components/ui/StarRow';
import { UnsavedChangesDialog } from '@/components/SaveStatus';
import { AiJudgement, AiLive } from './AiJudgement';
import { HeldReply, PlacedBlock, type Held, type Placed } from './InterviewReplyBlocks';
import './InterviewChat.css';

/**
 * D5 되묻기 — 레퍼런스(TIO 경험 정리) 구조: 좌측 캔버스에 카드가 채워지고, 우측 패널이 말풍선 대화로 한 번에 하나씩 묻는다.
 * 한 번에 한 줄만 묻는다. 보기 선택 = USER_SELECTED · 직접 입력 = USER_STATED.
 * 답은 AI 가 먼저 읽고(reply API) 카드에 넣었는지 여부를 outcome 으로 돌려준다. 넣은 문장은 고치거나 되돌릴 수 있다.
 */
const DONT_REMEMBER = '잘 기억나지 않아요';

export function InterviewPage() {
  const { cardId = '' } = useParams();
  const [sp] = useSearchParams();
  const location = useLocation();
  const nav = useNavigate();
  const { card: q } = useCard(cardId);
  const turns = useInterview(cardId);
  const ask = useAskInterview(cardId);
  const answer = useAnswerInterview(cardId);
  const reply = useReplyInterview(cardId);
  const saveDraft = useSaveDraft(cardId);
  const restore = useRestoreVersion(cardId);
  const qc = useQueryClient();
  const skip = useSkipInterview(cardId);
  const [text, setText] = useState('');
  const [picked, setPicked] = useState<string | null>(null);
  const busy = answer.isPending || reply.isPending;
  const guard = useUnsavedChanges(!!text.trim() || busy);
  // 방금 카드에 넣은 답과 넣지 않은 답은 질문 목록에 없는 화면의 상태다. 다음 답을 보내거나 질문이 닫히면 비운다
  const [placed, setPlaced] = useState<Placed | null>(null);
  const [held, setHeld] = useState<Held[]>([]);
  const [announce, setAnnounce] = useState('');
  const [folded, setFolded] = useState(true); // 모바일에서는 미리보기를 접고 질문부터 보여준다
  const autoAsked = useRef(false);
  const threadRef = useRef<HTMLDivElement>(null);
  const reduced = useReducedMotion();
  // AI 가 더 물을 것이 없다고 끝낸 상태. 사용자가 칸을 골라 다시 물으면 풀린다
  const [aiDone, setAiDone] = useState(false);
  // 이 화면에서 한 답이 카드를 작성 중에서 확정으로 바꿨다. 처음부터 확정이던 카드와 달리 확정 안내를 보여 주고 더 묻지 않는다
  const [settledHere, setSettledHere] = useState(false);
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
  useEffect(() => { setText(''); setPicked(null); setHeld([]); }, [open?.turnNo]);
  // 질문 상한은 서버 정책이다. 화면은 응답에 실려 온 값을 쓰고, 응답 전에만 기본값으로 버틴다.
  const maxTurns = open?.maxTurns ?? allTurns[allTurns.length - 1]?.maxTurns ?? CONFIG.INTERVIEW_MAX_TURNS_FALLBACK;
  // 넘긴 질문도 질문 상한에 센다(서버 규칙)
  const capReached = allTurns.length >= maxTurns;

  // 칸을 정해 들어오면 그 칸을, 처음인데 칸이 없으면 AI 가 고른 칸을 묻는다. 확정 카드도 같다
  // 카드를 받기 전에는 묻지 않는다 — 카드를 못 읽었는데 질문부터 나가면 어떤 카드의 질문인지 화면이 모른다
  const cardReady = !!q.data;
  useEffect(() => {
    if (cardReady && turns.isSuccess && !open && !autoAsked.current && !capReached && (field || allTurns.length === 0)) {
      autoAsked.current = true;
      ask.mutate(field ?? undefined, {
        onSuccess: (turn) => turn ? track('interview_asked', { cardId, field: turn.field }) : setAiDone(true),
      });
    }
  }, [cardReady, turns.isSuccess, open, field, ask, cardId, capReached, allTurns.length]);

  // 줄이 늘거나 기다림이 바뀌면 대화 영역만 굴린다 — 새 질문이 칸보다 길어도 첫 줄이 보이게 맞춘다(좁은 화면에서는 이 영역이 스크롤되지 않아 페이지도 그대로다)
  useEffect(() => {
    const box = threadRef.current;
    if (box) scrollChatTo(box, box, { pad: 8, smooth: !reduced });
  }, [q.isSuccess, allTurns.length, open?.turnNo, busy, ask.isPending, aiDone, q.data?.interviewEndReason, held.length, placed, reduced]);

  // 답하거나 넘긴 뒤에는 칸을 정하지 않고 묻는다. AI 가 다음 칸을 고르거나, 더 물을 것이 없으면 null 로 끝낸다
  const askNext = (turnsAfter: number) => {
    if (turnsAfter >= maxTurns) return;
    ask.mutate(undefined, {
      onSuccess: (turn) => turn ? track('interview_asked', { cardId, field: turn.field, followUp: true }) : setAiDone(true),
    });
  };

  const card = q.data;
  // 보낸 글이 카드에 들어갔거나 질문이 닫혔으면 true. 넣지 않았거나 다듬지 못한 답은 입력칸을 비우고 false 로 남긴다
  const submit = async (again?: Held) => {
    const sending = again?.text ?? text;
    if (!open || !sending.trim() || busy) return false;
    try {
      const source: EvidenceType = again ? again.source : picked === text ? 'USER_SELECTED' : 'USER_STATED';
      const wasDraft = q.data?.status === 'DRAFT';
      setPlaced(null);
      const r = await reply.mutateAsync({ turnNo: open.turnNo, text: sending, source });
      setHeld((all) => all.filter((h) => h !== again));
      setText(''); setPicked(null);
      track('interview_answered', { cardId, field: open.field, source, turnNo: open.turnNo, outcome: r.outcome });
      if (r.outcome === 'APPLIED') {
        setPlaced({ turnNo: open.turnNo, written: r.written, restoreVersionNo: r.restoreVersionNo, edited: false, restored: false });
        setAnnounce('답을 읽고 카드 문장으로 썼어요');
        // 답으로 상황·행동이 차서 서버가 확정했으면 확정 안내를 보여 주고, 남은 칸은 카드 화면의 안내에서 이어 받는다
        if (wasDraft && r.card.status === 'CONFIRMED') setSettledHere(true);
        else askNext(allTurns.length);
        return true;
      }
      if (r.outcome === 'ASK_MORE') { setAnnounce('아직 카드에 넣지 않았어요'); return true; }
      if (r.outcome === 'SKIPPED') {
        toast(`${starFieldShort[open.field]} 질문을 넘겼어요`);
        askNext(allTurns.length);
        return true;
      }
      setHeld((all) => [...all, { text: sending, kind: r.outcome === 'NOT_USED' ? 'NOT_USED' : 'UNPOLISHED', reason: r.reason, source }]);
      setAnnounce(r.outcome === 'NOT_USED' ? '이 답은 카드에 넣지 않았어요' : '지금은 문장으로 다듬지 못했어요');
      return false;
    } catch { return false; }
  };
  // 다듬지 못했을 때 '그대로 넣기' — 예전 저장 API 로 친 글을 그대로 카드에 넣고 답했을 때처럼 이어 간다
  const forceStore = async (h: Held) => {
    if (!open || busy) return;
    try {
      const wasDraft = q.data?.status === 'DRAFT';
      const saved = await answer.mutateAsync({ turnNo: open.turnNo, text: h.text, source: h.source });
      track('interview_answered', { cardId, field: open.field, source: h.source, turnNo: open.turnNo });
      toast(`${starFieldShort[open.field]} 답변을 저장했어요`, { tone: 'success' });
      if (wasDraft && saved.status === 'CONFIRMED') setSettledHere(true);
      else askNext(allTurns.length);
    } catch { /* 오류는 위쪽 안내가 보여 준다 */ }
  };
  const skipOpen = () => {
    if (!open || skip.isPending || busy) return;
    skip.mutate(open.turnNo, {
      onSuccess: () => {
        toast(`${starFieldShort[open.field]} 질문을 넘겼어요`);
        setText(''); setPicked(null);
        askNext(allTurns.length);
      },
    });
  };
  // 고친 글은 다시 읽지 않고 적은 그대로 그 칸에만 들어간다 — 임시 저장 API 는 네 칸을 함께 받으므로 나머지는 카드의 지금 글을 보낸다
  const saveEdited = async (field: StarField, edited: string) => {
    if (!card) return false;
    try {
      const fields = Object.fromEntries(STAR_FIELDS.map((f) => [fieldKey[f], f === field ? edited : card.version[fieldKey[f]] ?? null])) as DraftFields;
      await saveDraft.mutateAsync(fields);
      await qc.invalidateQueries({ queryKey: keys.card(cardId) });
      setPlaced((p) => (p ? { ...p, edited: true, written: p.written.map((w) => (w.field === field ? { ...w, text: edited.trim() } : w)) } : p));
      return true;
    } catch { return false; }
  };
  const undoPlaced = () => {
    if (!placed || placed.restoreVersionNo === null || restore.isPending) return;
    restore.mutate(placed.restoreVersionNo, { onSuccess: () => { setPlaced((p) => (p ? { ...p, restored: true } : p)); setAnnounce('넣기 전으로 되돌렸어요'); } });
  };
  const askMore = (f: StarField) => { setAiDone(false); ask.mutate(f, { onSuccess: () => track('interview_asked', { cardId, field: f, followUp: true }) }); };

  if ((q.isError && !q.data) || (turns.isError && !turns.data)) return <main className="main"><QueryFailure error={q.error ?? turns.error} retry={() => Promise.all([q.refetch(), turns.refetch()])} pending={q.isFetching || turns.isFetching} /><Link to="/cards" className="btn btn--outline">경험 카드 목록</Link></main>;
  if (!card || turns.isPending) return <main className="main"><Skeleton h={16} w={300} /><Skeleton h={300} /></main>;
  const total = allTurns.length;
  const crumbs = [{ label: '홈', to: '/' }, ...(card.repo ? [{ label: card.repo.name, to: folderPath.repo(card.repo.id) }] : card.activity ? [{ label: card.activity.name, to: folderPath.activity(card.activity.id) }] : []), { label: card.title, to: `/cards/${cardId}`, state: location.state }, { label: '답변하기' }];
  const askable = STAR_FIELDS.filter((f) => !closedFields.has(f));
  const weakFields = askable.filter((f) => !card.version[fieldKey[f]] || card.lowConfidenceFields.some((l) => l.field === f));
  const confirmed = card.status === 'CONFIRMED';
  const anyFilled = STAR_FIELDS.some((f) => card.version[fieldKey[f]]);
  // 'AI 판단'(#430): AI 가 칸을 고른 질문의 이유(사용자가 칸을 골랐으면 없다)와 AI 가 끝낸 이유. 이유가 없으면 아무것도 더하지 않는다.
  // 끝낸 이유는 카드에 남아 있으므로(새 질문을 만들면 서버가 지운다) 화면을 다시 열어도 AI 가 끝낸 상태로 본다
  // 앞 답이 모자라 같은 칸을 한 번 더 묻는 질문이면, 다음 답을 앞 답과 합쳐 넣는다고 알린다
  const followUp = !!open && allTurns.some((t) => t.outcome === 'INSUFFICIENT' && t.field === open.field && t.turnNo < open.turnNo);
  const judged = open ? askJudgement(open, allTurns) : null;
  const endReason = cleanAiText(card.interviewEndReason);
  const ended = aiDone || endReason !== null;
  const showEnded = !open && !ask.isPending && (settledHere || (!capReached && ended));

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
              // 방금 넣은 칸 표시 — 다음 답을 보내거나 되돌리면 사라진다
              const put = placed && !placed.restored && placed.written.some((w) => w.field === f) ? (placed.edited && placed.written[0].field === f ? '직접 고쳤어요' : '방금 넣었어요') : null;
              return (
                <StarRow key={f} field={f} label={starFieldName[f]} dropped={!t}
                  className={`${!t ? 'star-read__row--gap' : ''} ${pending ? 'star-read__row--now' : ''}`}
                  action={put && <span className="iv__put-tag">{put}</span>}
                  metadata={pending && <Badge kind="PR">지금 채우는 칸</Badge>}>
                  {t ? <p className={put ? 'star__text iv__put-text' : 'star__text'}>{t}</p> : <span className="c-3" style={{ fontSize: 13 }}>{pending ? '질문에 답하면 이 칸에 저장돼요' : '비어 있음'}</span>}
                  <EvidenceList items={ev} label={starFieldName[f]} sentence={t} />
                </StarRow>
              );
            })}
          </div>
        </section>

        {/* 우측 패널 — 한 번에 하나만 묻는다 */}
        <aside className="card iv__panel" aria-label="대화">
          <div className="iv__head">
            <p className="iv__title"><strong>대화</strong><span>편하게 말하면 카드 문장으로 다듬어 넣어요</span></p>
            {/* 질문이 없을 때는 아래 "카드로 돌아가기"가 같은 길이라, 여기서는 질문 중(또는 질문을 만드는 중)에만 둔다 */}
            {(open || ask.isPending) && <Link to={`/cards/${cardId}`} state={location.state} className="btn btn--ghost btn--sm"><ArrowLeft size={14} aria-hidden /> 카드로</Link>}
          </div>
          {(reply.isError || answer.isError) && <Note strong="답변이 저장되지 않았어요" tone="danger">{errorView(reply.error ?? answer.error).message}</Note>}
          {skip.isError && <Note strong="질문을 넘기지 못했어요" tone="danger">{errorView(skip.error).message}</Note>}
          {ask.isError && <QueryFailure error={ask.error} retry={() => ask.variables && askMore(ask.variables)} pending={ask.isPending} />}

          <div className="chat-thread iv__thread" ref={threadRef}>
            {/* 질문 뒤에는 늘 내 답이나 넘김 줄이 와서, 질문마다 Gitory 의 말이 새로 시작된다 */}
            {finished.map((t) => (
              <Fragment key={t.turnNo}>
                <ChatFrom />
                <Bubble side="it"><span className="chat-tag">{starFieldShort[t.field]} 칸</span><p>{t.question}</p></Bubble>
                {t.outcome === 'INSUFFICIENT' && t.answer ? (
                  <>
                    <Bubble side="me">{t.answer.original ?? t.answer.text}</Bubble>
                    <p className="chat-line chat-line--left">아직 카드에 넣지 않았어요</p>
                  </>
                ) : t.answer?.original ? (
                  <>
                    <Bubble side="me">{t.answer.original}</Bubble>
                    <PlacedBlock written={placed?.turnNo === t.turnNo ? placed.written : [{ field: t.field, text: t.answer.text }]}
                      live={placed?.turnNo === t.turnNo ? { placed, saving: saveDraft.isPending, restoring: restore.isPending, failed: saveDraft.isError, onSave: (edited) => saveEdited(t.field, edited), onRestore: undoPlaced } : undefined} />
                  </>
                ) : t.answer ? (
                  <>
                    <Bubble side="me">{t.answer.text}</Bubble>
                    <p className="iv__saved">{evidenceTypeLabel[t.answer.source]}, 저장됨</p>
                  </>
                ) : (
                  <p className="chat-line">{t.outcome === 'SKIPPED' ? '넘긴 질문' : '측정하지 않음으로 기록'}</p>
                )}
              </Fragment>
            ))}

            {/* AI 판단 — 새 질문의 이유나 끝난 알림이 오면 한 번만 읽어 주는 낭독 영역이 여기 하나다 */}
            <AiLive>
              {announce && <span className="sr-only">{announce}</span>}
              {open && judged && <AiJudgement key={open.turnNo} head={judged.head} lead>{judged.reason}</AiJudgement>}
              {showEnded && (
                <div className="interview-complete">
                  <div className="interview-complete__summary">
                    <strong>{settledHere ? '카드를 확정했어요' : '추가 질문을 마쳤어요'}</strong>
                    <p>{settledHere ? '상황과 행동이 있어서 바로 쓸 수 있어요.' : confirmed ? '카드는 확정된 그대로 쓸 수 있어요.' : '작성된 카드를 검토해 주세요. 필요한 내용은 질문을 더 받거나 직접 수정할 수 있어요.'}</p>
                  </div>
                  {endReason && <details className="interview-complete__reason" open><summary>질문을 마친 이유</summary><AiJudgement>{endReason}</AiJudgement></details>}
                </div>
              )}
            </AiLive>

            {open && (
              <Fragment key={open.turnNo}>
                <ChatFrom />
                <Bubble side="it" enter>
                  <span className={card.lowConfidenceFields.some((l) => l.field === open.field) ? 'chat-tag chat-tag--caution' : 'chat-tag'}>{starFieldShort[open.field]} 칸</span>
                  <h2 className="iv__q">Q{open.turnNo}. {open.question}</h2>
                </Bubble>
                {followUp && <p className="iv__helper">다음 답과 합쳐서 한 문장으로 넣을게요</p>}
                {held.length === 0 && open.options.length > 0 && (
                  <div className="stack" style={{ gap: 6 }}>
                    <span className="t-12 w-600 c-3" style={{ fontSize: 13 }}>이 중에서 골라도 돼요</span>
                    {[...open.options, DONT_REMEMBER].map((o) => <button key={o} type="button" className="chip chip--option" disabled={busy} aria-pressed={picked === o} onClick={() => { setPicked(o); setText(o); }}>{o}</button>)}
                  </div>
                )}
              </Fragment>
            )}
            {open && held.map((h, i) => <HeldReply key={`${i}-${h.text}`} held={h} last={i === held.length - 1} busy={busy || skip.isPending}
              onSkip={skipOpen} onForce={() => void forceStore(h)} onRetry={() => void submit(h)} />)}
            {open && reply.isPending && (
              <>
                <Bubble side="me" enter>{reply.variables?.text}</Bubble>
                <AiWait label="답을 읽고 카드 문장으로 쓰는 중이에요" />
              </>
            )}
            {!open && ask.isPending && <AiWait label={`카드와 커밋을 읽고 ${ask.variables ? starFieldShort[ask.variables] : ''} 칸에 맞는 질문을 만드는 중이에요`} />}
          </div>

          {open && (
            <div className="iv__dock">
              <Composer value={text} onChange={(next) => { setText(next); if (picked && next !== picked) setPicked(null); }}
                onSend={() => void submit()} canSend={!!text.trim() && !busy} disabled={busy}
                label="답변" sendLabel="답 보내기" placeholder={starFieldSpoken[open.field]} maxLength={500} />
              <div className="iv__sub">
                <span>{text ? `${text.length}/500` : ''}</span>
                <Button variant="ghost" size="sm" loading={skip.isPending} disabled={busy} onClick={skipOpen}>건너뛰기</Button>
              </div>
            </div>
          )}
          {!open && !ask.isPending && (
            <>
              {settledHere ? null : capReached ? (
                <Note strong={`질문 ${maxTurns}개를 다 썼어요`} tone="inset">남은 칸은 직접 고쳐서 채울 수 있어요</Note>
              ) : (
                <div className="stack" style={{ gap: 8 }}>
                  <span className="w-600" style={{ fontSize: 15 }}>{ended ? '더 보완하고 싶다면' : finished.length ? '이어서 물을까요?' : '어느 칸을 채울까요?'}</span>
                  <div className="chat-quick">
                    {!ended && <button type="button" onClick={() => askNext(allTurns.length)}>AI 가 고르게 하기</button>}
                    {(weakFields.length ? weakFields : askable).map((f) => <button key={f} type="button" onClick={() => askMore(f)}>{starFieldShort[f]} 질문 받기{!card.version[fieldKey[f]] ? ' · 미작성' : card.lowConfidenceFields.some((l) => l.field === f) ? ' · 확인 필요' : ''}</button>)}
                  </div>
                </div>
              )}
              <div className="row" style={{ gap: 8 }}>
                {settledHere
                  ? <Button onClick={() => nav(cardFolderPath(card), { state: { confirmed: cardId } })}>폴더로 돌아가기</Button>
                  : confirmed
                  ? <Button onClick={() => nav(`/cards/${cardId}`, { state: location.state })}>카드로 돌아가기</Button>
                  : ended || capReached
                  ? <Button onClick={() => nav(`/cards/${cardId}?confirm=1`, { state: location.state })}>카드 검토하기</Button>
                  : <Button onClick={() => nav(`/cards/${cardId}`, { state: location.state })}>카드로 돌아가기</Button>}
                {settledHere
                  ? <Button variant="outline" onClick={() => nav(`/cards/${cardId}`, { state: location.state })}>카드 보기</Button>
                  : confirmed ? null
                  : <Button variant="ghost" onClick={() => nav(`/cards/${cardId}?mode=edit`, { state: location.state })}>직접 수정하기</Button>}
              </div>
            </>
          )}
        </aside>
      </div>
      <UnsavedChangesDialog blocker={guard.blocker} saving={busy} flush={() => submit()} />
    </main>
  );
}

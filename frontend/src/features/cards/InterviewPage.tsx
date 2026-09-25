import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';
import { useAnswerInterview, useAskInterview, useCard, useInterview } from '@/api/queries';
import { StarField as StarFieldSchema, type EvidenceType, type StarField } from '@/api/schemas';
import { Badge, Breadcrumb, Button, EvidenceStrip, Note, PageTitle, Skeleton, StarKey, Textarea } from '@/components/ui';
import { STAR_FIELDS, evidenceTypeLabel, fieldKey, starFieldName, starFieldShort } from '@/lib/labels';
import { CONFIG } from '@/lib/config';
import { toast } from '@/lib/toast';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { useUnsavedChanges } from '@/lib/useUnsavedChanges';
import { UnsavedChangesDialog } from '@/components/SaveStatus';

/**
 * D5 되묻기 — 레퍼런스(TIO 경험 정리) 구조: 좌측 캔버스에 카드가 채워지고, 우측 패널이 한 번에 하나씩 묻는다.
 * 코드에서 찾은 것 / 없는 것을 먼저 보여주고 한 줄만 묻는다. 보기 선택 = USER_SELECTED · 직접 입력 = USER_STATED.
 * 답변은 가공 없이 그대로 저장(매 턴 새 버전). 꼬리질문은 상한(Q6)까지.
 */
export function InterviewPage() {
  const { cardId = '' } = useParams();
  const [sp] = useSearchParams();
  const nav = useNavigate();
  const { card: q } = useCard(cardId);
  const turns = useInterview(cardId);
  const ask = useAskInterview(cardId);
  const answer = useAnswerInterview(cardId);
  const [text, setText] = useState('');
  const [picked, setPicked] = useState<string | null>(null);
  const guard = useUnsavedChanges(!!text.trim() || answer.isPending);
  const [folded, setFolded] = useState(true); // 모바일에서는 미리보기를 접고 질문부터 보여준다
  const autoAsked = useRef(false);
  useDocumentTitle(q.data ? `되묻기 · ${q.data.title}` : '되묻기');

  const parsedField = StarFieldSchema.safeParse(sp.get('field'));
  const field: StarField | null = parsedField.success ? parsedField.data : null;
  const open = turns.data?.find((t) => !t.answer) ?? null;
  const answered = (turns.data ?? []).filter((t) => t.answer);
  // 질문 상한은 서버 정책이다. 화면은 응답에 실려 온 값을 쓰고, 응답 전에만 기본값으로 버틴다.
  const maxTurns = open?.maxTurns ?? answered[answered.length - 1]?.maxTurns ?? CONFIG.INTERVIEW_MAX_TURNS_FALLBACK;
  const capReached = answered.length >= maxTurns;

  useEffect(() => {
    if (turns.isSuccess && !open && field && !autoAsked.current && !capReached) {
      autoAsked.current = true;
      ask.mutate(field, { onSuccess: () => track('interview_asked', { cardId, field }) });
    }
  }, [turns.isSuccess, open, field, ask, cardId, capReached]);

  const card = q.data;
  const submit = async () => {
    if (!open || !text.trim() || answer.isPending) return false;
    try {
    const source: EvidenceType = picked === text ? 'USER_SELECTED' : 'USER_STATED';
    await answer.mutateAsync({ turnNo: open.turnNo, text, source });
    track('interview_answered', { cardId, field: open.field, source, turnNo: open.turnNo });
    toast(`${starFieldShort[open.field]} 칸에 저장했습니다 — 다듬지 않고 그대로`, { tone: 'success' });
    setText(''); setPicked(null);
    return true;
    } catch { return false; }
  };
  const askMore = (f: StarField) => ask.mutate(f, { onSuccess: () => track('interview_asked', { cardId, field: f, followUp: true }) });

  if ((q.isError && !q.data) || (turns.isError && !turns.data)) return <main className="main"><QueryFailure error={q.error ?? turns.error} retry={() => Promise.all([q.refetch(), turns.refetch()])} pending={q.isFetching || turns.isFetching} /><Link to="/cards" className="btn btn--outline">경험 카드 목록</Link></main>;
  if (!card || turns.isPending) return <main className="main"><Skeleton h={16} w={300} /><Skeleton h={300} /></main>;
  const total = answered.length + (open ? 1 : 0);
  const crumbs = [{ label: '경험정리/홈', to: '/' }, ...(card.repo ? [{ label: card.repo.name, to: `/repos/${card.repo.id}/candidates` }] : []), { label: card.title, to: `/cards/${cardId}` }, { label: '되묻기' }];
  const weakFields = STAR_FIELDS.filter((f) => !card.version[fieldKey[f]] || card.lowConfidenceFields.some((l) => l.field === f));
  const anyFilled = STAR_FIELDS.some((f) => card.version[fieldKey[f]]);

  return (
    <main className="main main--tight">
      <Breadcrumb items={crumbs} />
      <PageTitle right={`질문 ${total} / ${maxTurns} · 답할 때마다 저장돼요`}>되묻기</PageTitle>

      <div className="iv">
        {/* 좌측 캔버스 — 답할수록 채워지는 카드 */}
        <section className={`card iv__canvas ${folded ? 'iv__canvas--folded' : ''}`} aria-label="카드 미리보기">
          <div className="row" style={{ gap: 10, padding: '16px 20px', borderBottom: '1px solid var(--border-default)' }}>
            <span className="w-700" style={{ fontSize: 15 }}>{card.title}</span>
            <Badge kind="DRAFT">v{card.version.versionNo}</Badge>
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
                <div key={f} className={`star-read__row ${!t ? 'star-read__row--gap' : ''} ${pending ? 'star-read__row--now' : ''}`}>
                  <StarKey field={f} dropped={!t} />
                  <div className="stack grow" style={{ gap: 8 }}>
                    <div className="row" style={{ gap: 8 }}><span className="star__name">{starFieldName[f]}</span>{pending && <Badge kind="PR">지금 채우는 칸</Badge>}</div>
                    {t ? <p className="star__text">{t}</p> : <span className="c-3" style={{ fontSize: 13 }}>{pending ? '질문에 답하면 이 칸에 저장돼요' : '비어 있음'}</span>}
                    {ev.map((e, i) => <EvidenceStrip key={i} e={e} />)}
                  </div>
                </div>
              );
            })}
          </div>
        </section>

        {/* 우측 패널 — 한 번에 하나만 묻는다 */}
        <aside className="card iv__panel">
          <div className="row" style={{ gap: 8 }}>
            <Link to={`/cards/${cardId}`} className="btn btn--text btn--sm"><ArrowLeft size={14} /> 카드로</Link>
            <span className="right t-12 c-3">{open ? `남은 질문 ${Math.max(0, maxTurns - total)}개` : ''}</span>
          </div>
          <div className="iv__greet"><span>코드에 없는 것만 한 줄씩 여쭤볼게요</span></div>
          {answer.isError && <Note strong="답변이 저장되지 않았어요" tone="danger">입력은 그대로 남아 있어요. 연결을 확인하고 다시 저장해 주세요.</Note>}
          {ask.isError && <QueryFailure error={ask.error} retry={() => ask.variables && askMore(ask.variables)} pending={ask.isPending} />}

          {answered.map((t) => (
            <div key={t.turnNo} className="stack" style={{ gap: 6, padding: '10px 12px', borderRadius: 12, background: 'var(--bg-paper)' }}>
              <div className="row" style={{ gap: 8 }}><span className="turn__no" style={{ width: 20, height: 20, fontSize: 10 }}>{t.turnNo}</span><Badge kind="NEUTRAL">{t.field} 칸</Badge><span className="t-12 c-2">{t.question}</span></div>
              <span style={{ fontSize: 13, lineHeight: '20px' }}>{t.answer!.text}</span>
              <span className="t-12 c-3">{evidenceTypeLabel[t.answer!.source]} · 고치지 않고 그대로 넣었어요</span>
            </div>
          ))}

          {open ? (
            <>
              <div className="stack" style={{ gap: 6 }}>
                <div className="row" style={{ gap: 8 }}><span className="turn__no turn__no--now" style={{ width: 22, height: 22 }}>{open.turnNo}</span><Badge kind={card.lowConfidenceFields.some((l) => l.field === open.field) ? 'CAUTION' : 'PR'}>{open.field} 칸</Badge><span className="t-12 c-2">{starFieldName[open.field]}</span></div>
                <h2 className="iv__q">Q{open.turnNo}. {open.question}</h2>
                <details className="interview-evidence"><summary>질문 근거 보기</summary>
                <div className="found" style={{ gridTemplateColumns: '1fr' }}>
                  <div className="found__box"><h4>코드에서 찾은 것</h4>{open.found.map((f, i) => <span key={i}>· {f}</span>)}</div>
                  <div className="found__box found__box--miss"><h4>코드에 없는 것</h4>{open.missing.map((m, i) => <span key={i}>· {m}</span>)}</div>
                </div>
                </details>
              </div>
              {open.options.length > 0 && (
                <div className="stack" style={{ gap: 6 }}>
                  <span className="t-12 w-600 c-3" style={{ fontSize: 10.5 }}>이 중에 고르셔도 돼요</span>
                  {open.options.map((o) => <button key={o} type="button" className="chip chip--option" disabled={answer.isPending} aria-pressed={picked === o} onClick={() => { setPicked(o); setText(o); }}>{o}</button>)}
                </div>
              )}
              <Textarea rows={4} value={text} disabled={answer.isPending} onChange={(e) => { setText(e.target.value); if (picked && e.target.value !== picked) setPicked(null); }}
                onKeyDown={(e) => { if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') void submit(); }}
                placeholder="한 문장으로" aria-label="답변" maxLength={500} />
              <div className="row" style={{ gap: 8 }}>
                <span className="t-12 c-3 grow">{text ? `${text.length}/500` : ''}</span>
                <Link to={`/cards/${cardId}`} className="btn btn--text btn--sm">건너뛰기</Link>
                <Button size="lg" disabled={!text.trim()} loading={answer.isPending} onClick={submit}>다음으로 →</Button>
              </div>
            </>
          ) : ask.isPending ? <Skeleton h={200} /> : (
            <>
              {capReached ? (
                <Note strong={`질문 ${maxTurns}개를 다 썼어요`} tone="inset">남은 칸은 직접 고쳐서 채우시면 돼요</Note>
              ) : (
                <div className="stack" style={{ gap: 8 }}>
                  <span className="w-600" style={{ fontSize: 14 }}>{answered.length ? '이어서 물을까요?' : '어느 칸을 채울까요?'}</span>
                  <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
                    {(weakFields.length ? weakFields : STAR_FIELDS).map((f) => <Button key={f} variant="outline" size="sm" onClick={() => askMore(f)}>{starFieldShort[f]}{card.version[fieldKey[f]] ? (card.lowConfidenceFields.some((l) => l.field === f) ? ' ⚑' : '') : ' · 비어 있음'}</Button>)}
                  </div>
                </div>
              )}
              <div className="row" style={{ gap: 8 }}>
                <Button onClick={() => nav(`/cards/${cardId}`)}>카드로 돌아가기</Button>
                <Button variant="text" onClick={() => nav(`/cards/${cardId}?mode=edit`)}>직접 수정</Button>
              </div>
            </>
          )}
        </aside>
      </div>
      <UnsavedChangesDialog blocker={guard.blocker} saving={answer.isPending} flush={submit} />
    </main>
  );
}

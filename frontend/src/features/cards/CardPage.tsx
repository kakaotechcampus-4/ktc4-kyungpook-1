import { Copy, ExternalLink, History, ListChecks, Pencil, Printer } from 'lucide-react';
import { DraftProgress } from './DraftProgress';
import { CardNavigation } from './CardNavigation';
import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { CardTags } from '@/components/cards/CardTags';
import { CardDeleteAction } from '@/components/cards/CardDeleteAction';
import { CardStatusMark, cardStarStates } from '@/components/cards/CardStatusMark';
import { useCard, useRegenerateField, useReopen, useRetryDraft } from '@/api/queries';
import { isTerminal, StarField as StarFieldSchema, type Card, type ConfirmResult, type StarField } from '@/api/schemas';
import { Badge, Breadcrumb, Button, EvidenceList, Note, PageTitle, Skeleton, StickyFooter } from '@/components/ui';
import { STAR_FIELDS, cardKindLabel, candidateRefLabel, fieldKey, starFieldName, starFieldShort, cardContentLabel, interviewActionLabel, historyActionLabel } from '@/lib/labels';
import { ymd } from '@/lib/format';
import { toast } from '@/lib/toast';
import { errorView } from '@/api/errorView';
import { reconnectGithub } from '@/lib/githubReconnect';
import { track } from '@/lib/track';
import { releaseJobToast, suppressJobToast } from '@/lib/jobWatcher';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { CardCopyDialog } from './CardCopyDialog';
import { CardMetadataDialog } from './CardMetadataDialog';
import { cardMetadataSupported, cardRegenerationSupported } from '@/api/capabilities';
import { matchSupported } from '@/api/matching';
import { StarBlock, applyMask, railKind } from './StarBlock';
import { EditMode, MaskMode } from './CardModes';
import { ConfirmDialog, VersionsDialog } from './CardDialogs';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { StarRow } from '@/components/ui/StarRow';
import { canConfirmCard, emptyStarFields, starFieldsToReview } from '@/lib/cardRules';
import { isDraftMetadataPending } from '@/lib/cacheSavedDraft';
import { DraftSteps } from './DraftSteps';

/**
 * /cards/:id — 상태로 화면이 갈린다.
 *  generation != null    → D1 초안 생성 중 (Job 폴링) · generation.partial → D3 (E-7)
 *  DRAFT                 → D2 초안 STAR (+ ?mode=edit D6 · ?mode=mask D7 · ?confirm=1 D8 · ?versions=1 E3) · R 비면 D4 (E-6)
 *  CONFIRMED             → E2 읽기 (+ D9 확정 직후 배너 · 복사 · 인쇄)
 */
export function CardPage() {
  const { cardId = '' } = useParams();
  const [sp, updateSp] = useSearchParams();
  const location = useLocation();
  const setSp = (next: Parameters<typeof updateSp>[0]) => updateSp(next, { state: location.state });
  const nav = useNavigate();
  const { card: q, job } = useCard(cardId);
  const mode = sp.get('mode');
  const viewedCard = useRef(cardId);
  useEffect(() => {
    if (viewedCard.current !== cardId && q.data?.id === cardId) {
      viewedCard.current = cardId;
      document.querySelector('main')?.scrollIntoView?.({ block: 'start' });
    }
  }, [cardId, q.data?.id]);
  const previousView = useRef({ cardId, mode });
  useEffect(() => {
    const previous = previousView.current;
    previousView.current = { cardId, mode };
    // Same observer survives edit → detail. Reconcile ACK-only metadata once on exit,
    // without blocking navigation or replacing acknowledged writing when GET fails.
    if (previous.cardId === cardId && previous.mode === 'edit' && mode !== 'edit' && mode !== 'mask' && isDraftMetadataPending(q.data)) {
      void q.refetch({ cancelRefetch: false }); // Join the save-and-close refresh if already running.
    }
  }, [cardId, mode, q.data, q.refetch]);
  const regen = useRegenerateField(cardId);
  const retry = useRetryDraft(cardId);
  const reopen = useReopen(cardId);
  const [justConfirmed, setJustConfirmed] = useState<ConfirmResult | null>(null);
  const [copyOpen, setCopyOpen] = useState(false);
  const [metadataOpen, setMetadataOpen] = useState(false);
  useEffect(() => { setCopyOpen(false); setMetadataOpen(false); setJustConfirmed(null); }, [cardId]);
  const parsedField = StarFieldSchema.safeParse(sp.get('field'));
  const editField = parsedField.success ? parsedField.data : undefined;
  useDocumentTitle(q.data?.title);

  // 이 화면이 직접 보고 있는 Job 은 전역 알림에서 뺀다 · 끝나면 지표
  useEffect(() => {
    const jid = q.data?.generation?.jobId;
    if (jid) suppressJobToast(jid);
    if (jid && job.data && isTerminal(job.data.state)) { releaseJobToast(jid); track('draft_generated', { cardId, state: job.data.state }); }
    return () => { if (jid) releaseJobToast(jid); }; // 화면을 떠나면 억제도 같이 풀어야 전역 알림이 계속 죽어있지 않는다
  }, [q.data?.generation?.jobId, job.data, cardId]);

  if (q.isPending) return <main className="main"><Skeleton h={16} w={300} /><Skeleton h={40} w={360} /><Skeleton h={400} /></main>;
  if (q.isError && !q.data) return <main className="main"><QueryFailure error={q.error} retry={() => q.refetch()} pending={q.isFetching} /><Link to="/cards" className="btn btn--outline">경험 카드 목록</Link></main>;
  const card = q.data!;
  const repoName = card.repo ? `${card.repo.owner} / ${card.repo.name}` : null;
  const srcLabel = card.candidate ? candidateRefLabel(card.candidate.type, card.candidate.ref) : card.kind === 'QUALITATIVE' ? '직접 작성' : '카드';
  const crumbs = [{ label: '홈', to: '/' }, ...(card.repo ? [{ label: card.repo.name, to: `/repos/${card.repo.id}/candidates` }, { label: '후보 보드', to: `/repos/${card.repo.id}/candidates` }] : [{ label: '경험 카드', to: '/cards' }]), { label: srcLabel, href: candidateGithubUrl(card) }];
  const ask = (f: StarField) => nav(`/cards/${cardId}/interview?field=${f}`, { state: location.state });
  const close = () => setSp({});
  const masked = (t: string) => applyMask(t, card.maskRules);
  // 제목·기간 수정은 제목 바로 옆 연필 하나로 — 무엇을 고치는지 위치가 말해 준다
  const metadataAction = card.status === 'DRAFT' && cardMetadataSupported() ? <button type="button" className="title-edit" aria-label="기본 정보 수정" title="제목과 기간 수정" onClick={() => setMetadataOpen(true)}><Pencil size={18} aria-hidden /></button> : null;
  const metadataDialog = metadataOpen && card.status === 'DRAFT' ? <CardMetadataDialog key={card.id} card={card} onClose={() => setMetadataOpen(false)} /> : null;
  const exportMd = () => setCopyOpen(true);
  const print = () => { track('card_exported', { cardId, format: 'print' }); window.print(); };

  // ── D1 · D3 생성 중
  if (card.generation && !card.generation.partial) {
    const j = job.data;
    return (
      <main className="main">
        <Breadcrumb items={[...crumbs.slice(0, -1), { label: '초안 생성 중' }]} />
        <PageTitle>고른 후보의 카드 초안을 쓰고 있어요</PageTitle>
        {job.isError && <QueryFailure error={job.error} retry={() => job.refetch()} pending={job.isFetching} />}
        <DraftProgress job={j} checkedAt={job.dataUpdatedAt} />
        <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
          <span className="c-2 t-14">끝나면 알려드릴게요</span>
          <div className="right row" style={{ gap: 8 }}>
            {card.repo && <Link to={`/repos/${card.repo.id}/candidates`} className="btn btn--outline">후보 보드로</Link>}
            <Link to="/" className="btn btn--text">홈으로</Link>
          </div>
        </div>
      </main>
    );
  }

  // ── E2 읽기 (CONFIRMED) + D9 확정 직후
  if (card.status === 'CONFIRMED' && mode !== 'edit') {
    return (
      <main className="main main--tight">
        <Breadcrumb items={crumbs} />
        {justConfirmed && (
          <div className="card done-card">
            <span className="done-card__ok" aria-hidden>✓</span>
            <div className="stack grow" style={{ gap: 5 }}>
              <div className="row" style={{ gap: 8 }}><span className="w-600" style={{ fontSize: 16 }}>{card.title}</span><Badge kind="CONFIRMED">확정됨</Badge></div>
              <span className="t-12l c-2">근거 {card.evidence.length}건, {emptyStarFields(card).length ? `비어 있는 항목 ${emptyStarFields(card).length}개` : '모든 항목 작성 완료'}</span>
            </div>
            <Button variant="outline" size="sm" onClick={() => setSp({ versions: '1' })}>{historyActionLabel}</Button>
            {justConfirmed.repoId && justConfirmed.remainingCandidates > 0
              ? <Button onClick={() => nav(`/repos/${justConfirmed.repoId}/candidates`)}>남은 후보 {justConfirmed.remainingCandidates}개 보기</Button>
              : <Link to="/" className="btn btn--primary">홈으로</Link>}
            {matchSupported() && <Link to="/match" className="btn btn--outline">이 경험과 맞는 공고 보기</Link>}
          </div>
        )}
        <CardHeader card={card} right={<>
          <Button size="sm" onClick={exportMd} title="마스킹 적용된 표시값을 마크다운으로 복사">복사</Button>
          <Button variant="outline" size="sm" loading={reopen.isPending} onClick={() => reopen.mutate(undefined, { onSuccess: () => { track('card_reopened', { cardId }); toast('다시 편집할 수 있습니다. 확정 버전은 히스토리에 남습니다.'); } })}>수정하기</Button>
        </>} />
        <div className="two-col two-col--340">
          <div className="card star-read evidence-rail evidence-rail--rows">
            {STAR_FIELDS.map((f) => {
              const t = card.version[fieldKey[f]];
              const ev = card.evidence.filter((e) => e.field === f);
              return (
                <StarRow key={f} field={f} label={starFieldShort[f]} dropped={!t} className={t ? undefined : 'star-read__row--gap'} rail={{ commits: ev.filter((e) => e.type === 'COMMIT').length, kind: railKind(!t, ev) }}>
                  {t ? <p className="star__text">{masked(t)}</p> : <span className="c-3 w-500" style={{ fontSize: 13 }}>비운 채로 확정했습니다</span>}
                  <EvidenceList items={ev} label={starFieldName[f]} sentence={t ? masked(t) : null} />
                </StarRow>
              );
            })}
          </div>
          <aside className="stack card-review-aside" style={{ gap: 16 }}>
            {/* 오른쪽은 한눈에 읽히는 세 덩어리만: 받을 질문, 출처 두 줄, 카드 관리. 확정일은 제목 아래에 이미 있다 */}
            <div className="card stack" style={{ gap: 10, padding: '16px 18px' }}>
              <span className="w-600" style={{ fontSize: 14 }}>이 카드로 받을 수 있는 질문</span>
              <ul className="aside-list">{interviewQuestions(card).map((qq) => <li key={qq} className="t-12l c-2">{qq}</li>)}</ul>
            </div>
            <div className="card stack" style={{ gap: 12, padding: '16px 18px' }}>
              <div className="stack" style={{ gap: 4 }}>
                <span className="t-12 w-600 c-3">출처</span>
                <span className="t-12l">
                  {card.repo ? <a className="aside-link" href={`https://github.com/${card.repo.owner}/${card.repo.name}`} target="_blank" rel="noreferrer">{repoName}<ExternalLink size={12} aria-hidden /></a> : null}
                  {card.repo && srcLabel ? ', ' : ''}
                  {(() => { const href = candidateGithubUrl(card); return href ? <a className="aside-link" href={href} target="_blank" rel="noreferrer">{srcLabel}<ExternalLink size={12} aria-hidden /></a> : srcLabel; })()}
                  {!card.repo && !srcLabel ? '직접 작성' : null}
                </span>
                <span className="t-12 c-2">근거 커밋 {card.evidence.filter((e) => e.type === 'COMMIT').length}건, 내가 말한 것 {card.evidence.filter((e) => e.type !== 'COMMIT').length}건{card.maskRules.length ? `, 가린 정보 ${card.maskRules.length}개` : ''}</span>
              </div>
              <div className="card-tools">
                <Button variant="ghost" size="sm" onClick={() => setSp({ versions: '1' })}><History size={15} aria-hidden />{historyActionLabel}</Button>
                <Button variant="ghost" size="sm" onClick={print} title="면접 직전 복습용 인쇄"><Printer size={15} aria-hidden />인쇄</Button>
              </div>
            </div>
            <CardNavigation cardId={cardId} />
          </aside>
        </div>
        <DraftSteps steps={card.draftSteps} />
        {sp.get('versions') === '1' && <VersionsDialog key={card.id} card={card} onClose={close} />}
        {copyOpen && <CardCopyDialog key={card.id} card={card} onClose={() => setCopyOpen(false)} />}
        <CardDeleteAction card={card} onDeleted={() => nav('/cards', { replace: true })} />
      </main>
    );
  }

  // ── D6 · D7 모드
  if (mode === 'edit') return <main className="main main--footer main--tight"><Breadcrumb items={[...crumbs, { label: '직접 수정' }]} /><CardHeader card={card} titleAction={metadataAction} statusOverride={<Badge kind="CONFIRMED">편집 중</Badge>} note="입력한 내용은 자동으로 저장됩니다" /><EditMode key={card.id} card={card} onDone={close} initialField={editField} />{metadataDialog}</main>;
  if (mode === 'mask') return <main className="main main--footer main--tight"><Breadcrumb items={[...crumbs, { label: '정보 가리기' }]} /><PageTitle>공유할 정보 가리기</PageTitle><MaskMode key={card.id} card={card} onDone={close} /></main>;

  // ── D2 초안 · D3 (E-7 부분) · D4 (E-6 소재 부족)
  const partial = card.generation?.partial;
  const emptyR = !card.version.result && !!card.droppedFields.find((d) => d.field === 'R' && d.reason === 'NO_EVIDENCE');
  const e6 = emptyR && !partial && card.candidate;
  const canConfirm = canConfirmCard(card);
  const reviewFields = starFieldsToReview(card);
  const regenerate = (f: StarField) => regen.mutate(f, { onSuccess: () => { track('field_regenerated', { cardId, field: f }); toast(`${starFieldShort[f]} 칸을 다시 생성했습니다 — 근거가 없으면 그대로 비어 있습니다`); } });
  const canRegenerate = cardRegenerationSupported();
  const retryDraft = () => retry.mutate(undefined, { onSuccess: () => toast('초안을 다시 만들고 있어요') });

  return (
    <main className="main main--footer main--tight">
      <Breadcrumb items={crumbs} />
      <CardHeader card={card} titleAction={metadataAction} right={<>{card.repo && <Link to={`/repos/${card.repo.id}/candidates`} className="btn btn--ghost"><ListChecks size={15} aria-hidden />후보 목록</Link>}</>} />
      {partial && (
        <div className="card row" style={{ gap: 16, padding: '16px 18px', flexWrap: 'wrap' }}>
          <Badge kind="NEUTRAL">E-7</Badge>
          <div className="stack grow" style={{ gap: 4 }}>
            <span className="w-600" style={{ fontSize: 14 }}>{STAR_FIELDS.filter((f) => card.version[fieldKey[f]]).join(', ')} 는 채워졌고, {card.droppedFields.filter((d) => d.reason === 'TIMEOUT').map((d) => d.field).join(', ')} 은 아직입니다</span>
            <span className="t-12l c-2">{canRegenerate ? '칸 단위로 다시 만들 수 있어요' : '빈칸은 답변하거나 직접 작성해 주세요.'}</span>
          </div>
          {canRegenerate && card.droppedFields.filter((d) => d.reason === 'TIMEOUT').map((d) => <Button key={d.field} variant="outline" size="sm" loading={regen.isPending} onClick={() => regenerate(d.field)}>{d.field} 칸만 다시 생성</Button>)}
        </div>
      )}
      {card.draftFailure && card.status === 'DRAFT' && (
        <div className="card row" role="status" style={{ gap: 16, padding: '16px 18px', flexWrap: 'wrap' }}>
          <div className="stack grow" style={{ gap: 4 }}>
            <span className="w-600" style={{ fontSize: 14 }}>AI 초안을 만들지 못했어요</span>
            <span className="t-12l c-2">{card.draftFailure === 'GITHUB_UNAUTHORIZED'
              ? 'GitHub 연결이 끊겼어요. 다시 연결한 뒤 초안을 다시 만들어 주세요.'
              : card.draftFailure === 'AI_UNAVAILABLE'
              ? 'AI 가 잠시 응답하지 않았어요. 다시 시도하면 채워질 수 있어요.'
              : '초안을 만드는 중에 문제가 났어요. 다시 시도하거나 칸을 직접 채워 주세요.'} 직접 쓴 칸은 그대로 둡니다.</span>
          </div>
          {card.draftFailure === 'GITHUB_UNAUTHORIZED' && <Button size="sm" onClick={reconnectGithub}>GitHub 다시 연결</Button>}
          <Button variant="outline" size="sm" loading={retry.isPending} onClick={retryDraft}>초안 다시 만들기</Button>
          {retry.isError && <Note strong="다시 시도하지 못했어요" tone="danger">{errorView(retry.error).message}</Note>}
        </div>
      )}
      {e6 && (
        <div className="card row" style={{ gap: 16, padding: '18px 20px', background: 'var(--bg-inset)', border: 0, flexWrap: 'wrap' }}>
          <div className="stack grow" style={{ gap: 5 }}>
            <span className="w-600" style={{ fontSize: 16 }}>이 후보만으론 결과(R)가 채워지지 않습니다</span>
            <span className="c-2" style={{ fontSize: 13, lineHeight: '21px' }}>{candidateRefLabel(card.candidate!.type, card.candidate!.ref)}에는 결과 수치가 없어요 — 지어내지 않고 비워 둡니다</span>
          </div>
          <Link to={`/repos/${card.repo!.id}/candidates`} className="btn btn--outline btn--sm">다른 후보도 보기</Link>
        </div>
      )}

      <div className="two-col card-detail-layout">
        {/* 커밋 레일 — 문장마다 몇 개의 커밋이 받치는지, 근거 없는 칸은 선이 끊긴다. Gitory 의 한 가지 얼굴 */}
        <div className="stack evidence-rail" style={{ gap: 12 }}>
          {STAR_FIELDS.map((f) => <StarBlock key={f} card={card} field={f} onAsk={() => ask(f)} onEdit={() => setSp({ mode: 'edit', field: f })} onRegenerate={canRegenerate ? () => regenerate(f) : undefined} />)}
        </div>
        <aside className="card-review-aside"><div className="card stack" style={{ gap: 14, padding: '16px 18px' }}>
          <div className="stack" style={{ gap: 6 }}>
            <div className="card-tools">
              <Button variant="ghost" size="sm" onClick={() => setSp({ versions: '1' })}><History size={15} aria-hidden />{historyActionLabel}</Button>
              <Button variant="ghost" size="sm" onClick={exportMd}><Copy size={15} aria-hidden />복사</Button>
            </div>
          </div>
        </div>
        <CardNavigation cardId={cardId} />
        </aside>
      </div>

      <DraftSteps steps={card.draftSteps} />
      <CardDeleteAction card={card} onDeleted={() => nav('/cards', { replace: true })} />
      <StickyFooter strong={reviewFields.length ? `${reviewFields.map((f) => starFieldShort[f]).join(', ')} 보완하기` : canConfirm ? '내용을 확인한 뒤 확정하세요' : '상황과 행동을 작성해 주세요'} sub={!reviewFields.length && canConfirm ? '확정한 카드는 공고 매칭에 쓰여요. 나중에 다시 열어 고칠 수 있어요.' : undefined}>
        {reviewFields.length === 0 && (card.repo ? <Link to={`/repos/${card.repo.id}/candidates`} className="btn btn--text">나중에</Link> : <Link to="/" className="btn btn--text">나중에</Link>)}
        <Button variant={reviewFields.length ? 'outline' : 'primary'} size="lg" disabled={!canConfirm} onClick={() => setSp({ confirm: '1' })} title={canConfirm ? undefined : '상황과 행동을 작성해 주세요'}>확정</Button>
        {reviewFields.length > 0 && <Button size="lg" title={interviewActionLabel} onClick={() => ask(reviewFields[0])}>답변하기</Button>}
      </StickyFooter>

      {sp.get('confirm') === '1' && <ConfirmDialog card={card} onClose={close} onConfirmed={(r) => { setJustConfirmed(r); close(); }} />}
      {sp.get('versions') === '1' && <VersionsDialog key={card.id} card={card} onClose={close} />}
      {copyOpen && <CardCopyDialog key={card.id} card={card} onClose={() => setCopyOpen(false)} />}
      {metadataDialog}
    </main>
  );
}

function CardHeader({ card, right, titleAction, statusOverride, note }: { card: Card; right?: React.ReactNode; titleAction?: React.ReactNode; statusOverride?: React.ReactNode; note?: string }) {
  return (
    <div className="card-page-header">
      <div className="card-page-header__identity"><PageTitle sub={<>
        <Badge kind="NEUTRAL">{cardKindLabel[card.kind]}</Badge>
        {statusOverride ?? <CardStatusMark status={card.status} star={cardStarStates(card)} reviewWithStatus={false} />}
        <span className="t-12 c-3">{note ?? (card.status === 'CONFIRMED' && card.confirmedAt ? `${ymd(card.confirmedAt)} 확정` : isDraftMetadataPending(card) ? '본문 저장됨' : cardContentLabel[card.version.source])}</span>
      </>} after={titleAction}>{card.title}</PageTitle>
      <CardTags areas={card.areas} techs={card.techs} /></div>
      {right && <div className="card-page-header__actions">{right}</div>}
    </div>
  );
}

function interviewQuestions(card: Card): string[] {
  const qs: string[] = [];
  if (card.version.action) qs.push(`왜 그 방식을 택했나요? — ${starFieldName.A}`);
  if (card.version.result) qs.push(`그 결과는 어떻게 측정했나요? — ${starFieldName.R}`);
  if (card.evidence.some((e) => e.type !== 'COMMIT')) qs.push('코드에 안 남은 부분은 어떻게 증명하나요?');
  if (card.droppedFields.length) qs.push(`${card.droppedFields.map((d) => d.field).join(', ')} 칸이 빈 이유를 설명할 수 있나요?`);
  return qs.length ? qs : ['이 경험에서 가장 어려웠던 점은?'];
}

/** 후보가 PR·이슈면 그 GitHub 주소. 커밋 묶음은 한 주소로 가리킬 수 없어 링크를 만들지 않는다 */
function candidateGithubUrl(card: Card): string | undefined {
  const num = card.candidate?.ref.match(/^#(\d+)$/)?.[1];
  if (!card.repo || !card.candidate || !num) return undefined;
  const kind = card.candidate.type === 'PR' ? 'pull' : card.candidate.type === 'ISSUE' ? 'issues' : null;
  return kind ? `https://github.com/${card.repo.owner}/${card.repo.name}/${kind}/${num}` : undefined;
}

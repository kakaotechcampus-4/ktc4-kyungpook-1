import { Copy, ExternalLink, History, ListChecks, Pencil, Printer } from 'lucide-react';
import { DraftProgress } from './DraftProgress';
import { LiveCardDraft } from './LiveCardDraft';
import { CardNavigation } from './CardNavigation';
import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { CardTags } from '@/components/cards/CardTags';
import { CardThemes } from '@/components/cards/CardThemes';
import { CardDeleteAction } from '@/components/cards/CardDeleteAction';
import { CardStatusMark, cardStarStates } from '@/components/cards/CardStatusMark';
import { useCard, useRegenerateField, useReopen, useRetryDraft } from '@/api/queries';
import { isTerminal, StarField as StarFieldSchema, type Card, type StarField } from '@/api/schemas';
import { Badge, Breadcrumb, Button, ButtonLink, EvidenceList, Note, PageTitle, Skeleton, StickyFooter } from '@/components/ui';
import { STAR_FIELDS, cardKindLabel, candidateRefLabel, fieldKey, starFieldName, starFieldShort, cardContentLabel, interviewActionLabel, historyActionLabel } from '@/lib/labels';
import { ymd } from '@/lib/format';
import { evidenceCountLabel } from '@/lib/evidenceCount';
import { toast } from '@/lib/toast';
import { errorView } from '@/api/errorView';
import { reconnectGithub } from '@/lib/githubReconnect';
import { track } from '@/lib/track';
import { releaseJobToast, suppressJobToast } from '@/lib/jobWatcher';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { useLiveDraft } from '@/lib/useLiveDraft';
import { CardCopyDialog } from './CardCopyDialog';
import { CardMetadataDialog } from './CardMetadataDialog';
import { cardMetadataSupported, cardRegenerationSupported } from '@/api/capabilities';
import { matchSupported } from '@/api/matching';
import { StarBlock, applyMask } from './StarBlock';
import { EditMode, MaskMode } from './CardModes';
import { ConfirmDialog, VersionsDialog } from './CardDialogs';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { StarRow } from '@/components/ui/StarRow';
import { canConfirmCard, emptyStarFields, starFieldsToReview } from '@/lib/cardRules';
import { isDraftMetadataPending } from '@/lib/cacheSavedDraft';
import { DraftProcess } from './DraftSteps';
import { cardFolderPath, folderPath } from '@/lib/cardFolders';

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
  const [justConfirmed, setJustConfirmed] = useState(false);
  const [copyOpen, setCopyOpen] = useState(false);
  const [metadataOpen, setMetadataOpen] = useState(false);
  useEffect(() => { setCopyOpen(false); setMetadataOpen(false); setJustConfirmed(false); }, [cardId]);
  // 이 화면이 보는 동안 같은 카드가 작성 중에서 확정으로 바뀐 때만 안내한다 — 수정을 저장하다 상황과 행동이 차면 서버가 확정한다
  // 확정 창으로 확정한 때는 곧 폴더로 가므로 제외한다
  const lastSeen = useRef<{ id: string; status: Card['status'] } | null>(null);
  useEffect(() => {
    if (!q.data) return;
    const { id, status } = q.data;
    const seen = lastSeen.current;
    if (seen?.id === id && seen.status === 'DRAFT' && status === 'CONFIRMED' && sp.get('confirm') !== '1') setJustConfirmed(true);
    lastSeen.current = { id, status };
  }, [q.data?.id, q.data?.status]);
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

  // 초안을 쓰는 동안 AI 가 칸을 쓰는 모습(#617). Job 이 끝나도 마지막 카드를 다 펼칠 때까지 이 화면을 붙잡는다(live.holding)
  const generating = !!q.data?.generation && !q.data.generation.partial;
  const live = useLiveDraft({ scope: cardId, jobId: q.data?.generation?.jobId, generating });
  // 쓰는 화면을 보고 있다가 여러 장이 다 써졌으면 그 레포로 옮긴다 — 다음 일이 여러 장을 훑어 확정하는 것이다.
  // 매칭이 켜져 있으면 그 카드로 맞는 기업을 고르는 화면, 꺼져 있으면 카드 폴더다
  const showingLive = generating || live.holding;
  const wasShowingLive = useRef(false);
  const draftRepoId = q.data?.repo?.id;
  useEffect(() => {
    const done = live.job;
    if (wasShowingLive.current && !showingLive && done?.state === 'SUCCEEDED' && (done.result?.cardIds?.length ?? 0) > 1 && draftRepoId) {
      nav(matchSupported() ? folderPath.repoMatch(draftRepoId) : folderPath.repo(draftRepoId), { replace: true });
    }
    wasShowingLive.current = showingLive;
  }, [showingLive, live.job, draftRepoId, nav]);

  if (q.isPending) return <main className="main"><Skeleton h={16} w={300} /><Skeleton h={40} w={360} /><Skeleton h={400} /></main>;
  if (q.isError && !q.data) return <main className="main"><QueryFailure error={q.error} retry={() => q.refetch()} pending={q.isFetching} /><ButtonLink to="/cards" variant="secondary">경험 카드 목록</ButtonLink></main>;
  const card = q.data!;
  const repoName = card.repo ? `${card.repo.owner} / ${card.repo.name}` : null;
  const srcLabel = card.candidate ? candidateRefLabel(card.candidate.type, card.candidate.ref) : card.kind === 'QUALITATIVE' ? '직접 작성' : '카드';
  // 경험 카드 목록 다음에 카드가 든 폴더(레포 또는 활동)가 온다(#522)
  const crumbs = [{ label: '홈', to: '/' }, { label: '경험 카드', to: '/cards' }, ...(card.repo ? [{ label: card.repo.name, to: folderPath.repo(card.repo.id) }] : card.activity ? [{ label: card.activity.name, to: folderPath.activity(card.activity.id) }] : []), { label: srcLabel, href: candidateGithubUrl(card) }];
  const ask = (f: StarField) => nav(`/cards/${cardId}/interview?field=${f}`, { state: location.state });
  const close = () => setSp({});
  const masked = (t: string) => applyMask(t, card.maskRules);
  // 제목·기간 수정은 제목 바로 옆 연필 하나로 — 무엇을 고치는지 위치가 말해 준다. 서버가 확정 카드도 다시 열지 않고 받으므로 확정 카드에도 둔다
  const metadataAction = cardMetadataSupported() ? <button type="button" className="title-edit" aria-label="기본 정보 수정" title="제목과 기간 수정" onClick={() => setMetadataOpen(true)}><Pencil size={18} aria-hidden /></button> : null;
  const metadataDialog = metadataOpen ? <CardMetadataDialog key={card.id} card={card} onClose={() => setMetadataOpen(false)} /> : null;
  const exportMd = () => setCopyOpen(true);
  const print = () => { track('card_exported', { cardId, format: 'print' }); window.print(); };
  // 확정 카드를 편집 상태로 되돌린다. 칸 연필로 열었으면 되돌린 뒤 그 칸부터 고친다
  const reopenCard = (field?: StarField) => reopen.mutate(undefined, { onSuccess: () => {
    track('card_reopened', { cardId });
    toast(`다시 편집할 수 있어요. 확정했던 버전은 ${historyActionLabel}에 남아요.`);
    if (field) setSp({ mode: 'edit', field });
  } });

  // ── D1 · D3 생성 중
  if (generating || live.holding) {
    const j = job.data ?? live.job; // 카드가 완성으로 바뀌어 카드의 Job 조회가 꺼져도 마지막 진행을 그린다
    return (
      <main className="main">
        <Breadcrumb items={[...crumbs.slice(0, -1), { label: '초안 생성 중' }]} />
        <PageTitle>카드 초안을 쓰고 있어요</PageTitle>
        {job.isError && <QueryFailure error={job.error} retry={() => job.refetch()} pending={job.isFetching} />}
        <DraftProgress job={j} checkedAt={job.dataUpdatedAt} />
        <LiveCardDraft view={live.view} instant={{ on: live.manualInstant, toggle: live.reducedMotion ? null : () => live.setManualInstant((on) => !on) }} />
        <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
          <div className="right row" style={{ gap: 8 }}>
            {card.repo && <ButtonLink to={`/repos/${card.repo.id}/candidates`} variant="secondary">후보 보드로</ButtonLink>}
            <ButtonLink to="/" variant="text">홈으로</ButtonLink>
          </div>
        </div>
      </main>
    );
  }

  // ── E2 읽기 (CONFIRMED) + D9 확정 직후
  if (card.status === 'CONFIRMED' && mode !== 'edit') {
    const gapFields = emptyStarFields(card);
    return (
      <main className="main main--tight">
        <Breadcrumb items={crumbs} />
        {justConfirmed && (
          // 수정을 저장하다 서버가 확정한 때의 안내이고, 확정 창으로 확정하면 폴더로 가서 다음 걸음은 폴더 맨 위가 말한다
          <div className="card done-card" role="status">
            <span className="done-card__ok" aria-hidden>✓</span>
            <div className="stack grow" style={{ gap: 4 }}>
              <span className="w-bold" style={{ fontSize: 16 }}>카드를 확정했어요</span>
              <span className="t-13 c-2">상황과 행동이 있어서 바로 쓸 수 있어요.</span>
            </div>
            <ButtonLink to={cardFolderPath(card)} state={{ confirmed: card.id }} variant="primary">폴더로 돌아가기</ButtonLink>
          </div>
        )}
        <CardHeader card={card} titleAction={metadataAction} right={<Button variant="secondary" size="sm" onClick={exportMd} title="가린 정보를 적용한 내용을 복사해요">복사</Button>} />
        {/* 과제·결과가 비어도 확정은 그대로이고, 채우고 싶을 때 바로 질문을 받게만 한다 */}
        {gapFields.length > 0 && (
          <div className="card row" style={{ gap: 16, padding: '16px 18px', background: 'var(--bg-inset)', border: 0, flexWrap: 'wrap' }}>
            <span className="grow" style={{ fontSize: 14, lineHeight: '22px' }}>{gapFields.map((f) => starFieldShort[f]).join(', ')} 칸이 비어 있어요. 한두 가지만 알려 주면 채울 수 있어요</span>
            <Button variant="secondary" onClick={() => ask(gapFields[0])}>질문 받기</Button>
          </div>
        )}
        <div className="two-col two-col--340">
          <div className="card star-read star-read--rows">
            {STAR_FIELDS.map((f) => {
              const t = card.version[fieldKey[f]];
              const ev = card.evidence.filter((e) => e.field === f);
              const rowClass = [!t && 'star-read__row--gap', f === 'R' && 'star-read__row--result'].filter(Boolean).join(' ');
              return (
                <StarRow key={f} field={f} label={starFieldShort[f]} action={<button type="button" className="star__pencil" aria-label={`${starFieldShort[f]} ${t ? '수정' : '채우기'}`} title={`${starFieldShort[f]} ${t ? '수정' : '채우기'}`} disabled={reopen.isPending} onClick={() => reopenCard(f)}><Pencil size={16} aria-hidden /></button>} dropped={!t} className={rowClass}>
                  {t ? <p className="star__text">{masked(t)}</p> : <span className="c-3 w-regular" style={{ fontSize: 13 }}>비운 채로 확정했어요</span>}
                  <EvidenceList items={ev} label={starFieldName[f]} sentence={t ? masked(t) : null} />
                </StarRow>
              );
            })}
          </div>
          <aside className="stack card-review-aside" style={{ gap: 16 }}>
            {/* 오른쪽은 한눈에 읽히는 세 덩어리만: 만든 과정, 출처 두 줄, 카드 관리. 확정일은 제목 아래에 이미 있다 */}
            <DraftProcess card={card} />
            <div className="card stack" style={{ gap: 12, padding: '16px 18px' }}>
              <div className="stack" style={{ gap: 4 }}>
                <span className="t-12 w-bold c-3">출처</span>
                <span className="t-12l">
                  {card.activity ? <>활동 <Link className="aside-link" to={folderPath.activity(card.activity.id)}>{card.activity.name}</Link>, </> : null}
                  {card.repo ? <a className="aside-link" href={`https://github.com/${card.repo.owner}/${card.repo.name}`} target="_blank" rel="noreferrer">{repoName}<ExternalLink size={12} aria-hidden /></a> : null}
                  {card.repo && srcLabel ? ', ' : ''}
                  {(() => { const href = candidateGithubUrl(card); return href ? <a className="aside-link" href={href} target="_blank" rel="noreferrer">{srcLabel}<ExternalLink size={12} aria-hidden /></a> : srcLabel; })()}
                  {!card.repo && !srcLabel ? '직접 작성' : null}
                </span>
                <span className="t-12 c-2">{evidenceCountLabel(card.evidence, { showZero: true })}{card.maskRules.length ? `, 가린 정보 ${card.maskRules.length}개` : ''}</span>
              </div>
              <div className="card-tools">
                <Button variant="secondary" size="sm" onClick={() => setSp({ versions: '1' })}><History size={15} aria-hidden />{historyActionLabel}</Button>
                <Button variant="secondary" size="sm" onClick={print} title="면접 직전 복습용 인쇄"><Printer size={15} aria-hidden />인쇄</Button>
              </div>
            </div>
            <CardNavigation cardId={cardId} />
          </aside>
        </div>
        {sp.get('versions') === '1' && <VersionsDialog key={card.id} card={card} onClose={close} />}
        {copyOpen && <CardCopyDialog key={card.id} card={card} onClose={() => setCopyOpen(false)} />}
        {metadataDialog}
        <CardDeleteAction card={card} onDeleted={() => nav('/cards', { replace: true })} />
      </main>
    );
  }

  // ── D6 · D7 모드
  if (mode === 'edit') return <main className="main main--footer main--tight"><Breadcrumb items={[...crumbs, { label: '수정' }]} /><CardHeader card={card} titleAction={metadataAction} statusOverride={<Badge kind="CONFIRMED">편집 중</Badge>} /><EditMode key={card.id} card={card} onDone={close} initialField={editField} />{metadataDialog}</main>;
  if (mode === 'mask') return <main className="main main--footer main--tight"><Breadcrumb items={[...crumbs, { label: '정보 가리기' }]} /><PageTitle>공유할 정보 가리기</PageTitle><MaskMode key={card.id} card={card} onDone={close} /></main>;

  // ── D2 초안 · D3 (E-7 부분) · D4 (E-6 소재 부족)
  const partial = card.generation?.partial;
  const emptyR = !card.version.result && !!card.droppedFields.find((d) => d.field === 'R' && d.reason === 'NO_EVIDENCE');
  const e6 = emptyR && !partial && card.candidate;
  const canConfirm = canConfirmCard(card);
  const reviewFields = starFieldsToReview(card);
  const regenerate = (f: StarField) => regen.mutate(f, { onSuccess: () => { track('field_regenerated', { cardId, field: f }); toast(`${starFieldShort[f]} 칸을 다시 생성했어요. 근거가 없으면 그대로 비어 있어요.`); } });
  const canRegenerate = cardRegenerationSupported();
  const retryDraft = () => retry.mutate(undefined, { onSuccess: () => toast('초안을 다시 만들고 있어요') });

  return (
    <main className="main main--footer main--tight">
      <Breadcrumb items={crumbs} />
      <CardHeader card={card} titleAction={metadataAction} right={<>{card.repo && <ButtonLink to={`/repos/${card.repo.id}/candidates`} variant="secondary"><ListChecks size={15} aria-hidden />후보 보드</ButtonLink>}</>} />
      {partial && (
        <div className="card row" style={{ gap: 16, padding: '16px 18px', flexWrap: 'wrap' }}>
          <Badge kind="NEUTRAL">일부만 채움</Badge>
          <div className="stack grow" style={{ gap: 4 }}>
            <span className="w-bold" style={{ fontSize: 14 }}>{STAR_FIELDS.filter((f) => card.version[fieldKey[f]]).map((f) => starFieldShort[f]).join(', ')} 칸은 채웠고, {card.droppedFields.filter((d) => d.reason === 'TIMEOUT').map((d) => starFieldShort[d.field]).join(', ')} 칸은 아직이에요</span>
            <span className="t-12l c-2">{canRegenerate ? '칸 단위로 다시 만들 수 있어요' : '빈 칸은 답변하거나 직접 작성해 주세요.'}</span>
          </div>
          {canRegenerate && card.droppedFields.filter((d) => d.reason === 'TIMEOUT').map((d) => <Button key={d.field} variant="secondary" size="sm" loading={regen.isPending} onClick={() => regenerate(d.field)}>{starFieldShort[d.field]} 칸만 다시 생성</Button>)}
        </div>
      )}
      {card.draftFailure && card.status === 'DRAFT' && (
        <div className="card row" role="status" style={{ gap: 16, padding: '16px 18px', flexWrap: 'wrap' }}>
          <div className="stack grow" style={{ gap: 4 }}>
            <span className="w-bold" style={{ fontSize: 14 }}>AI 초안을 만들지 못했어요</span>
            <span className="t-12l c-2">{card.draftFailure === 'GITHUB_UNAUTHORIZED'
              ? 'GitHub 연결이 끊겼어요. 다시 연결한 뒤 초안을 다시 만들어 주세요.'
              : card.draftFailure === 'AI_UNAVAILABLE'
              ? 'AI 가 잠시 응답하지 않았어요. 다시 시도하면 채워질 수 있어요.'
              : '초안을 만드는 중에 문제가 났어요. 다시 시도하거나 칸을 직접 채워 주세요.'} 직접 쓴 칸은 그대로 둬요.</span>
          </div>
          {card.draftFailure === 'GITHUB_UNAUTHORIZED' && <Button size="sm" onClick={reconnectGithub}>GitHub 다시 연결</Button>}
          <Button variant="secondary" size="sm" loading={retry.isPending} onClick={retryDraft}>초안 다시 만들기</Button>
          {retry.isError && <Note strong="다시 시도하지 못했어요" tone="danger">{errorView(retry.error).message}</Note>}
        </div>
      )}
      {e6 && (
        <div className="card row" style={{ gap: 16, padding: '18px 20px', background: 'var(--bg-inset)', border: 0, flexWrap: 'wrap' }}>
          <div className="stack grow" style={{ gap: 5 }}>
            <span className="w-bold" style={{ fontSize: 16 }}>이 후보만으로는 결과 칸을 채울 수 없어요</span>
            <span className="c-2" style={{ fontSize: 13, lineHeight: '21px' }}>{candidateRefLabel(card.candidate!.type, card.candidate!.ref)}에는 결과 수치가 없어요. 지어내지 않고 비워 둬요</span>
          </div>
          <ButtonLink to={`/repos/${card.repo!.id}/candidates`} variant="secondary" size="sm">다른 후보도 보기</ButtonLink>
        </div>
      )}

      <div className="two-col card-detail-layout">
        {/* 커밋 레일 — 문장마다 몇 개의 커밋이 받치는지, 근거 없는 칸은 선이 끊긴다. Gitory 의 한 가지 얼굴 */}
        <div className="stack evidence-rail" style={{ gap: 12 }}>
          {STAR_FIELDS.map((f) => <StarBlock key={f} card={card} field={f} onAsk={() => ask(f)} onEdit={() => setSp({ mode: 'edit', field: f })} onRegenerate={canRegenerate ? () => regenerate(f) : undefined} />)}
        </div>
        <aside className="card-review-aside"><DraftProcess card={card} /><div className="card stack" style={{ gap: 14, padding: '16px 18px' }}>
          <div className="stack" style={{ gap: 6 }}>
            <div className="card-tools">
              <Button variant="secondary" size="sm" onClick={() => setSp({ versions: '1' })}><History size={15} aria-hidden />{historyActionLabel}</Button>
              <Button variant="secondary" size="sm" onClick={exportMd}><Copy size={15} aria-hidden />복사</Button>
            </div>
          </div>
        </div>
        <CardNavigation cardId={cardId} />
        </aside>
      </div>

      <CardDeleteAction card={card} onDeleted={() => nav('/cards', { replace: true })} />
      <StickyFooter compact strong={reviewFields.length ? `${reviewFields.map((f) => starFieldShort[f]).join(', ')} 칸에 답변해 주세요` : canConfirm ? '내용을 확인한 뒤 확정해 주세요' : '상황과 행동을 작성해 주세요'} sub={!reviewFields.length && canConfirm ? '확정한 카드는 공고 매칭에 쓰여요. 나중에 다시 열어 고칠 수 있어요.' : undefined}>
        {reviewFields.length === 0 && <ButtonLink to={cardFolderPath(card)} variant="text">나중에</ButtonLink>}
        <Button variant={reviewFields.length ? 'secondary' : 'primary'} size="lg" disabled={!canConfirm} onClick={() => setSp({ confirm: '1' })} title={canConfirm ? undefined : '상황과 행동을 작성해 주세요'}>확정</Button>
        {reviewFields.length > 0 && <Button size="lg" title={interviewActionLabel} onClick={() => ask(reviewFields[0])}>답변하기</Button>}
      </StickyFooter>

      {sp.get('confirm') === '1' && <ConfirmDialog card={card} onClose={close} onConfirmed={() => nav(cardFolderPath(card), { replace: true, state: { confirmed: card.id } })} />}
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
      <CardTags areas={card.areas} techs={card.techs} />
      <CardThemes key={card.id} card={card} editable={!statusOverride} /></div>
      {right && <div className="card-page-header__actions">{right}</div>}
    </div>
  );
}

/** 후보가 PR·이슈면 그 GitHub 주소. 커밋 묶음은 한 주소로 가리킬 수 없어 링크를 만들지 않는다 */
function candidateGithubUrl(card: Card): string | undefined {
  const num = card.candidate?.ref.match(/^#(\d+)$/)?.[1];
  if (!card.repo || !card.candidate || !num) return undefined;
  const kind = card.candidate.type === 'PR' ? 'pull' : card.candidate.type === 'ISSUE' ? 'issues' : null;
  return kind ? `https://github.com/${card.repo.owner}/${card.repo.name}/${kind}/${num}` : undefined;
}

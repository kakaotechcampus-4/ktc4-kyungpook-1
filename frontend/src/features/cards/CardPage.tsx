import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useCard, useRegenerateField, useReopen } from '@/api/queries';
import { isTerminal, type Card, type ConfirmResult, type StarField } from '@/api/schemas';
import { Badge, Breadcrumb, Button, EvidenceStrip, PageTitle, Skeleton, StarKey, StickyFooter, Track } from '@/components/ui';
import { STAR_FIELDS, cardKindLabel, cardStatusLabel, candidateRefLabel, fieldKey, jobStepLabel, starFieldName, starFieldShort, versionSourceLabel } from '@/lib/labels';
import { ymd } from '@/lib/format';
import { toast } from '@/lib/toast';
import { track } from '@/lib/track';
import { releaseJobToast, suppressJobToast } from '@/lib/jobWatcher';
import { doneSteps, stepBadge, stepProgress, stepView } from '@/lib/jobView';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { cardToMarkdown, copyText } from '@/lib/exportCard';
import { StarBlock, applyMask } from './StarBlock';
import { EditMode, MaskMode } from './CardModes';
import { ConfirmDialog, VersionsDialog } from './CardDialogs';
import { QueryFailure } from '@/components/ui/QueryFailure';

/**
 * /cards/:id — 상태로 화면이 갈린다.
 *  generation != null    → D1 초안 생성 중 (Job 폴링) · generation.partial → D3 (E-7)
 *  DRAFT                 → D2 초안 STAR (+ ?mode=edit D6 · ?mode=mask D7 · ?confirm=1 D8 · ?versions=1 E3) · R 비면 D4 (E-6)
 *  CONFIRMED             → E2 읽기 (+ D9 확정 직후 배너 · 복사 · 인쇄)
 */
export function CardPage() {
  const { cardId = '' } = useParams();
  const [sp, setSp] = useSearchParams();
  const nav = useNavigate();
  const { card: q, job } = useCard(cardId);
  const regen = useRegenerateField(cardId);
  const reopen = useReopen(cardId);
  const [justConfirmed, setJustConfirmed] = useState<ConfirmResult | null>(null);
  useDocumentTitle(q.data?.title);

  // 이 화면이 직접 보고 있는 Job 은 전역 알림에서 뺀다 · 끝나면 지표
  useEffect(() => {
    const jid = q.data?.generation?.jobId;
    if (jid) suppressJobToast(jid);
    if (jid && job.data && isTerminal(job.data.state)) { releaseJobToast(jid); track('draft_generated', { cardId, state: job.data.state }); }
  }, [q.data?.generation?.jobId, job.data, cardId]);

  if (q.isPending) return <main className="main"><Skeleton h={16} w={300} /><Skeleton h={40} w={360} /><Skeleton h={400} /></main>;
  if (q.isError && !q.data) return <main className="main"><QueryFailure error={q.error} retry={() => q.refetch()} pending={q.isFetching} /><Link to="/cards" className="btn btn--outline">경험 카드 목록</Link></main>;
  const card = q.data!;
  const mode = sp.get('mode');
  const repoName = card.repo ? `${card.repo.owner} / ${card.repo.name}` : null;
  const srcLabel = card.candidate ? candidateRefLabel(card.candidate.type, card.candidate.ref) : card.kind === 'QUALITATIVE' ? '직접 작성' : '카드';
  const crumbs = [{ label: '경험정리/홈', to: '/' }, ...(card.repo ? [{ label: card.repo.name, to: `/repos/${card.repo.id}/candidates` }, { label: '후보 보드', to: `/repos/${card.repo.id}/candidates` }] : [{ label: '경험 카드', to: '/cards' }]), { label: srcLabel }];
  const ask = (f: StarField) => nav(`/cards/${cardId}/interview?field=${f}`);
  const close = () => setSp({});
  const masked = (t: string) => applyMask(t, card.maskRules);
  const exportMd = async () => {
    const ok = await copyText(cardToMarkdown(card, masked));
    track('card_exported', { cardId, format: 'markdown' });
    toast(ok ? '마크다운으로 복사했습니다 — 마스킹이 적용된 표시값입니다' : '복사하지 못했습니다', { tone: ok ? 'success' : 'danger' });
  };
  const print = () => { track('card_exported', { cardId, format: 'print' }); window.print(); };

  // ── D1 · D3 생성 중
  if (card.generation && !card.generation.partial) {
    const j = job.data;
    return (
      <main className="main">
        <Breadcrumb items={[...crumbs.slice(0, -1), { label: '초안 생성 중' }]} />
        <PageTitle right="2단 읽기의 두 번째 단계">고른 후보의 코드를 읽고 있습니다</PageTitle>
        <div className="card stack" style={{ gap: 16, padding: '22px 24px' }}>
          <div className="row" style={{ gap: 12 }}>
            <span className="w-700" style={{ fontSize: 15 }}>{j ? `${doneSteps(j)} / ${j.steps.length} 단계` : '준비 중'}</span>
            <span className="right t-12 c-2">{j ? '오래 걸리면 채워진 칸까지 먼저 보여 드려요' : ''}</span>
          </div>
          <Track value={j ? stepProgress(j) : 0.02} label="초안 생성 진행률" />
          <div className="stack" style={{ gap: 10 }}>
            {(j?.steps ?? []).map((s) => {
              const status = stepView(s);
              return (
                <div key={s.key} className={`stage card ${status === 'NOW' ? 'card--paper' : ''}`} style={{ padding: '14px 16px', borderRadius: 10, borderTop: '1px solid var(--border-default)' }}>
                  <span className="stage__icon" style={status !== 'WAIT' ? { background: 'var(--bg-ink-solid)', borderColor: 'var(--bg-ink-solid)' } : undefined}>{status === 'DONE' ? '✓' : ''}</span>
                  <div className="stack grow" style={{ gap: 3 }}><span className="stage__label">{jobStepLabel[s.key]}</span><span className="stage__detail">{s.state === 'SKIPPED' ? '해당 없음' : `${s.done} / ${s.total ?? '?'}`}</span></div>
                  {status === 'NOW' && <div className="row" style={{ gap: 5 }}><StarKey field="S" small /><StarKey field="A" small /></div>}
                  <Badge kind={status === 'NOW' ? 'CONFIRMED' : 'NEUTRAL'}>{stepBadge(s)}</Badge>
                </div>
              );
            })}
          </div>
        </div>
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
              <span className="t-12l c-2">v{justConfirmed.versionNo} · 근거 {card.evidence.length}건 · {card.droppedFields.length ? `빈 칸 ${card.droppedFields.length}` : '빈 칸 없음'} · AI 초안 원본(v1)은 삭제되지 않습니다</span>
            </div>
            <Button variant="outline" size="sm" onClick={() => setSp({ versions: '1' })}>버전 히스토리</Button>
            {justConfirmed.repoId && justConfirmed.remainingCandidates > 0
              ? <Button onClick={() => nav(`/repos/${justConfirmed.repoId}/candidates`)}>남은 후보 {justConfirmed.remainingCandidates}개로 다음 카드</Button>
              : <Link to="/" className="btn btn--primary">홈으로</Link>}
          </div>
        )}
        <CardHeader card={card} right={<>
          <Button variant="outline" size="sm" onClick={exportMd} title="마스킹 적용된 표시값을 마크다운으로 복사">복사</Button>
          <Button variant="outline" size="sm" onClick={print} title="면접 직전 복습용 인쇄">인쇄</Button>
          <Button variant="outline" size="sm" onClick={() => setSp({ versions: '1' })}>버전 히스토리</Button>
          <Button variant="outline" size="sm" loading={reopen.isPending} onClick={() => reopen.mutate(undefined, { onSuccess: () => { track('card_reopened', { cardId }); toast('다시 편집할 수 있습니다. 확정 버전은 히스토리에 남습니다.'); } })}>수정하기</Button>
        </>} />
        <div className="two-col two-col--340">
          <div className="card star-read">
            {STAR_FIELDS.map((f) => {
              const t = card.version[fieldKey[f]];
              const ev = card.evidence.filter((e) => e.field === f);
              return (
                <div key={f} className={`star-read__row ${t ? '' : 'star-read__row--gap'}`}>
                  <StarKey field={f} dropped={!t} />
                  <div className="stack grow" style={{ gap: 9 }}>
                    <span className="star__name">{starFieldShort[f]}</span>
                    {t ? <p className="star__text">{masked(t)}</p> : <span className="c-3 w-500" style={{ fontSize: 13 }}>비운 채로 확정했습니다</span>}
                    {ev.map((e, i) => <EvidenceStrip key={i} e={e} />)}
                  </div>
                </div>
              );
            })}
          </div>
          <aside className="stack" style={{ gap: 16 }}>
            <div className="card stack" style={{ gap: 10, padding: '16px 18px' }}>
              <span className="w-700" style={{ fontSize: 13.5 }}>이 카드로 받을 수 있는 질문</span>
              {interviewQuestions(card).map((qq) => <div key={qq} className="row" style={{ gap: 8, alignItems: 'flex-start' }}><span className="c-3">·</span><span className="t-12l c-2">{qq}</span></div>)}
            </div>
            <div className="card stack" style={{ gap: 10, padding: '16px 18px' }}>
              <span className="w-700" style={{ fontSize: 13.5 }}>출처</span>
              {[['후보', srcLabel], ['레포', repoName ?? '—'], ['커밋 근거', `${card.evidence.filter((e) => e.type === 'COMMIT').length}건`], ['내가 말한 것', `${card.evidence.filter((e) => e.type !== 'COMMIT').length}건`], ['마스킹', card.maskRules.length ? `규칙 ${card.maskRules.length}개` : '없음'], ['확정', card.confirmedAt ? ymd(card.confirmedAt) : '—']].map(([k, v]) => (
                <div key={k} className="row t-12"><span className="c-2">{k}</span><span className="right w-600">{v}</span></div>
              ))}
            </div>
          </aside>
        </div>
        {sp.get('versions') === '1' && <VersionsDialog card={card} onClose={close} />}
      </main>
    );
  }

  // ── D6 · D7 모드
  if (mode === 'edit') return <main className="main main--footer main--tight"><Breadcrumb items={[...crumbs, { label: '직접 수정' }]} /><CardHeader card={card} statusOverride={<Badge kind="CONFIRMED">편집 중</Badge>} note={`v${card.version.versionNo} → 저장하면 v${card.version.versionNo + 1} (내가 수정)`} /><EditMode card={card} onDone={close} /></main>;
  if (mode === 'mask') return <main className="main main--footer main--tight"><Breadcrumb items={[...crumbs, { label: '마스킹' }]} /><PageTitle right="maskedFields">타인과 사내 정보를 가립니다</PageTitle><MaskMode card={card} onDone={close} /></main>;

  // ── D2 초안 · D3 (E-7 부분) · D4 (E-6 소재 부족)
  const partial = card.generation?.partial;
  const emptyR = !card.version.result && !!card.droppedFields.find((d) => d.field === 'R' && d.reason === 'NO_EVIDENCE');
  const emptyA = !card.version.action;
  const e6 = emptyR && !partial && card.candidate;
  const canConfirm = !!card.version.situation && !!card.version.action;
  const questions = card.droppedFields.map((d) => ({ f: d.field, why: '근거를 못 찾은 칸', q: askText(d.field), caution: false }))
    .concat(card.lowConfidenceFields.map((l) => ({ f: l.field, why: '⚑ 확인 필요', q: askText(l.field), caution: true })));
  const regenerate = (f: StarField) => regen.mutate(f, { onSuccess: () => { track('field_regenerated', { cardId, field: f }); toast(`${starFieldShort[f]} 칸을 다시 생성했습니다 — 근거가 없으면 그대로 비어 있습니다`); } });

  return (
    <main className="main main--footer main--tight">
      <Breadcrumb items={crumbs} />
      <CardHeader card={card} right={card.repo && <Link to={`/repos/${card.repo.id}/candidates`} className="t-14 w-600 c-2">다음 카드 ›</Link>} />
      {partial && (
        <div className="card row" style={{ gap: 16, padding: '16px 18px', flexWrap: 'wrap' }}>
          <Badge kind="NEUTRAL">E-7</Badge>
          <div className="stack grow" style={{ gap: 4 }}>
            <span className="w-600" style={{ fontSize: 14 }}>{STAR_FIELDS.filter((f) => card.version[fieldKey[f]]).join(' · ')} 는 채워졌고, {card.droppedFields.filter((d) => d.reason === 'TIMEOUT').map((d) => d.field).join(' · ')} 은 아직입니다</span>
            <span className="t-12l c-2">칸 단위로 다시 만들 수 있어요</span>
          </div>
          {card.droppedFields.filter((d) => d.reason === 'TIMEOUT').map((d) => <Button key={d.field} variant="outline" size="sm" loading={regen.isPending} onClick={() => regenerate(d.field)}>{d.field} 칸만 다시 생성</Button>)}
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

      <div className="two-col">
        <div className="stack" style={{ gap: 12 }}>
          {STAR_FIELDS.map((f) => <StarBlock key={f} card={card} field={f} onAsk={() => ask(f)} onEdit={() => setSp({ mode: 'edit' })} onRegenerate={() => regenerate(f)} />)}
        </div>
        <aside className="card stack" style={{ gap: 14, padding: '16px 18px' }}>
          <span className="w-700" style={{ fontSize: 13.5 }}>AI 보완 질문</span>
          {questions.length === 0 && <span className="t-12l c-3">되물을 칸 없음</span>}
          {questions.map((qq) => (
            <div key={qq.f + qq.why} className={`qpanel ${qq.caution ? 'qpanel--caution' : ''}`}>
              <div className="row" style={{ gap: 8 }}><Badge kind={qq.caution ? 'CAUTION' : 'NEUTRAL'}>{qq.f} 칸</Badge><span className="t-12" style={{ color: qq.caution ? 'var(--field-low-text)' : 'var(--text-tertiary)' }}>{qq.why}</span></div>
              <span className="qpanel__q">{qq.q}</span>
              <div><Button variant="outline" size="sm" onClick={() => ask(qq.f)}>되묻기 시작</Button></div>
            </div>
          ))}
          <div className="divider" />
          <div className="stack" style={{ gap: 6 }}>
            <span className="t-12 w-700 c-3">버전</span>
            <span className="t-12l c-2">v{card.version.versionNo} · {versionSourceLabel[card.version.source]} · 원본 보존</span>
            <div className="row" style={{ gap: 8, marginTop: 4, flexWrap: 'wrap' }}>
              <Button variant="text" size="sm" onClick={() => setSp({ versions: '1' })}>버전 히스토리</Button>
              <Button variant="text" size="sm" onClick={() => setSp({ mode: 'mask' })}>마스킹{card.maskRules.length ? ` (${card.maskRules.length})` : ''}</Button>
              <Button variant="text" size="sm" onClick={exportMd}>복사</Button>
            </div>
          </div>
        </aside>
      </div>

      <StickyFooter strong={canConfirm ? '확정만 되돌릴 수 없습니다' : emptyA ? 'S · A 를 채우면 확정할 수 있어요' : '확정만 되돌릴 수 없습니다'}>
        {card.repo ? <Link to={`/repos/${card.repo.id}/candidates`} className="btn btn--text">나중에</Link> : <Link to="/" className="btn btn--text">나중에</Link>}
        <Button variant="outline" onClick={() => setSp({ mode: 'edit' })}>직접 수정</Button>
        <Button size="lg" disabled={!canConfirm} onClick={() => setSp({ confirm: '1' })} style={{ paddingInline: 26 }} title={canConfirm ? undefined : 'S 와 A 가 채워져야 확정할 수 있습니다'}>확정</Button>
      </StickyFooter>

      {sp.get('confirm') === '1' && <ConfirmDialog card={card} onClose={close} onConfirmed={(r) => { setJustConfirmed(r); close(); }} />}
      {sp.get('versions') === '1' && <VersionsDialog card={card} onClose={close} />}
    </main>
  );
}

function CardHeader({ card, right, statusOverride, note }: { card: Card; right?: React.ReactNode; statusOverride?: React.ReactNode; note?: string }) {
  return (
    <div className="row" style={{ gap: 12, alignItems: 'flex-start', flexWrap: 'wrap' }}>
      <PageTitle sub={<>
        <Badge kind="NEUTRAL">{cardKindLabel[card.kind]}</Badge>
        {statusOverride ?? <Badge kind={card.status}>{cardStatusLabel[card.status]}</Badge>}
        <span className="t-12 c-3">{note ?? (card.status === 'CONFIRMED' && card.confirmedAt ? `v${card.version.versionNo} · ${ymd(card.confirmedAt)} 확정 · 근거 ${card.evidence.length}건` : `${versionSourceLabel[card.version.source]} v${card.version.versionNo} · 자동 저장됨`)}</span>
      </>}>{card.title}</PageTitle>
      {right && <div className="right row" style={{ gap: 8, flexWrap: 'wrap' }}>{right}</div>}
    </div>
  );
}

function askText(f: StarField) {
  return { S: '이 작업을 시작하게 된 계기가 무엇이었나요?', T: '그때 무엇을 해내야 하는 상황이었나요?', A: '가장 오래 붙잡았던 부분은 무엇이었나요?', R: '되돌린 뒤 어떤 방법으로 다시 붙였나요?' }[f];
}
function interviewQuestions(card: Card): string[] {
  const qs: string[] = [];
  if (card.version.action) qs.push(`왜 그 방식을 택했나요? — ${starFieldName.A}`);
  if (card.version.result) qs.push(`그 결과는 어떻게 측정했나요? — ${starFieldName.R}`);
  if (card.evidence.some((e) => e.type !== 'COMMIT')) qs.push('코드에 안 남은 부분은 어떻게 증명하나요?');
  if (card.droppedFields.length) qs.push(`${card.droppedFields.map((d) => d.field).join('·')} 칸이 빈 이유를 설명할 수 있나요?`);
  return qs.length ? qs : ['이 경험에서 가장 어려웠던 점은?'];
}

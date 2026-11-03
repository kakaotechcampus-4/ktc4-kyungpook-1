import { useCallback, useEffect, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useCreateManualDraft, useRepos } from '@/api/queries';
import { useActivities } from '@/api/activities';
import { keys } from '@/api/keys';
import { useQueryClient } from '@tanstack/react-query';
import { endpoints } from '@/api/endpoints';
import { AuthError } from '@/api/client';
import { redirectToLogin } from '@/app/queryClient';
import { isBlankCardText, type Card, type StarField } from '@/api/schemas';
import { Breadcrumb, Button, Field, Input, PageTitle, StickyFooter } from '@/components/ui';
import { STAR_FIELDS, fieldKey, starFieldExample, starFieldName, starFieldShort } from '@/lib/labels';
import { CONFIG } from '@/lib/config';
import { toDraftFields, useDraftAutosave } from '@/lib/useDraftAutosave';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { useUnsavedChanges } from '@/lib/useUnsavedChanges';
import { SaveStatus, UnsavedChangesDialog } from '@/components/SaveStatus';
import { cacheSavedDraft } from '@/lib/cacheSavedDraft';
import { cacheCardMetadata } from '@/lib/cacheCardMetadata';
import { cardMetadataSupported } from '@/api/capabilities';
import { ApiError } from '@/api/client';
import { Select } from '@/components/ui/Select';
import { StarEditorField } from '@/components/ui/StarEditorField';

const MAX = CONFIG.MANUAL_FIELD_MAX;

/**
 * E4 정성 카드 직접 작성 — 코드에 없는 경험. 근거는 "내가 쓴 문장"이라 커밋이 없어도 저장된다.
 *
 * 첫 화면은 제목·기간·레포·활동만 받고, 기본은 카드를 만든 뒤 되묻기로 넘어가 질문에 답하며 쓰는 길이다.
 * '네 칸 직접 쓰기' 를 고르면 첫 저장 버튼으로 DRAFT 와 cardId 를 만들고, 그 뒤 본문은 같은 카드로 자동 저장한다.
 * 브라우저에 남기지 않는 이유는 간단하다 — 여기 쓰는 글이 제일 날리기 아까운 글이기 때문이다.
 */
export function NewCardPage() {
  const [sp] = useSearchParams(); // 폴더 안에서 오면 ?repo=·?activity= 로 어디에 쓸지 미리 정해져 있다
  // 네 칸 양식으로 바꾸는 것은 주소가 아니라 화면 상태다 — 같은 화면에서 주소만 바꿔도 나가기 막기가 걸린다
  const [writing, setWriting] = useState(() => sp.get('write') === '1');
  useDocumentTitle(writing ? '직접 작성' : '경험 카드 만들기');
  const nav = useNavigate();
  const repos = useRepos();
  const activities = useActivities();
  const createDraft = useCreateManualDraft();
  const [idempotencyKey] = useState(() => crypto.randomUUID());
  const [cardId, setCardId] = useState<string | null>(null);
  const cardIdRef = useRef<string | null>(null);
  const savedMetadata = useRef<{ title: string; period: string } | undefined>(undefined);
  const savedFields = useRef<string | undefined>(undefined);
  const confirmedReplay = useRef(false);
  const [blockedCard, setBlockedCard] = useState<Card | null>(null);
  const [acknowledgedRepo, setAcknowledgedRepo] = useState<Card['repo']>(null);
  const [acknowledgedActivity, setAcknowledgedActivity] = useState<Card['activity']>(null);
  const canEditMetadata = cardMetadataSupported();
  const qc = useQueryClient();
  const mountedRef = useRef(true);
  useEffect(() => { mountedRef.current = true; return () => { mountedRef.current = false; }; }, []);
  const [title, setTitle] = useState('');
  const [period, setPeriod] = useState('');
  const [repoId, setRepoId] = useState<string>(() => sp.get('repo') ?? '');
  const [activityId, setActivityId] = useState<string>(() => sp.get('repo') ? '' : sp.get('activity') ?? ''); // 레포와 활동은 함께 보낼 수 없다(서버 400)
  const [f, setF] = useState<Record<StarField, string>>({ S: '', T: '', A: '', R: '' });
  const latestFields = useRef(f);
  latestFields.current = f;
  const editedFields = useRef(new Set<StarField>());

  const canConfirm = !blockedCard && !isBlankCardText(f.S) && !isBlankCardText(f.A) && !isBlankCardText(title);
  const missing = [isBlankCardText(title) && '제목', isBlankCardText(f.S) && '상황', isBlankCardText(f.A) && '행동'].filter(Boolean).join(', ');

  const snapshot = { title, period, repoId, activityId, fields: toDraftFields(f) };
  const acknowledgedInput = useRef<typeof snapshot | null>(null);
  const save = useCallback(async (body: typeof snapshot) => {
    if (confirmedReplay.current) throw new ApiError('CARD_CONFIRMED', '이미 확정된 카드예요.', 409);
    if (isBlankCardText(body.title)) throw new Error('TITLE_REQUIRED');
    let effective = body;
    let result: unknown;
    if (!cardIdRef.current) {
      const card = await createDraft.mutateAsync({ input: { title: body.title, period: body.period, repoId: body.repoId || null, ...(body.activityId ? { activityId: body.activityId } : {}) }, idempotencyKey });
      if (!mountedRef.current) return card;
      cardIdRef.current = card.id;
      const canonical = { title: card.title, period: card.period ?? '', repoId: card.repo?.id ?? '', activityId: card.activity?.id ?? '' };
      savedMetadata.current = { title: canonical.title, period: canonical.period };
      const returnedFields = Object.fromEntries(STAR_FIELDS.map(key => [key, card.version[fieldKey[key]] ?? ''])) as Record<StarField, string>;
      const merged = Object.fromEntries(STAR_FIELDS.map(key => [key, editedFields.current.has(key) ? latestFields.current[key] : returnedFields[key]])) as Record<StarField, string>;
      savedFields.current = JSON.stringify(toDraftFields(returnedFields));
      flushSync(() => {
        setCardId(card.id); setTitle(canonical.title); setPeriod(canonical.period); setRepoId(canonical.repoId); setActivityId(canonical.activityId); setAcknowledgedRepo(card.repo); setAcknowledgedActivity(card.activity ?? null);
        if (card.status === 'CONFIRMED') { confirmedReplay.current = true; setBlockedCard(card); }
        else { latestFields.current = merged; setF(merged); }
      });
      if (card.status === 'CONFIRMED') throw new ApiError('CARD_CONFIRMED', '이미 확정된 카드예요.', 409);
      effective = { ...canonical, fields: toDraftFields(merged) };
      result = card;
      track('manual_card_created', { cardId: card.id });
    }
    let acknowledgedFields = effective.fields;
    try {
      const metadata = { ...(savedMetadata.current?.title !== effective.title ? { title: effective.title } : {}), ...(savedMetadata.current?.period !== effective.period ? { period: effective.period } : {}) };
      if (Object.keys(metadata).length) {
        if (!canEditMetadata) throw new ApiError('FEATURE_UNAVAILABLE', '', 400);
        const acknowledged = await endpoints.saveMetadata(cardIdRef.current, metadata);
        if (!mountedRef.current) return acknowledged;
        await cacheCardMetadata(qc, acknowledged);
        savedMetadata.current = { title: acknowledged.title, period: acknowledged.period ?? '' };
        result = acknowledged;
      }
      const fieldSnapshot = JSON.stringify(effective.fields);
      if (savedFields.current !== fieldSnapshot) {
        const acknowledged = await endpoints.saveDraft(cardIdRef.current, effective.fields);
        if (!mountedRef.current) return acknowledged;
        await cacheSavedDraft(qc, acknowledged);
        acknowledgedFields = { situation: acknowledged.situation, task: acknowledged.task, action: acknowledged.action, result: acknowledged.result };
        savedFields.current = JSON.stringify(acknowledgedFields);
        result = acknowledged;
      }
    } catch (err) {
      // endpoints.saveDraft is called directly (not through useMutation) so it never reaches
      // the global mutationCache.onError -> redirectToLogin handler. Restore it here.
      if (err instanceof AuthError) redirectToLogin();
      throw err;
    }
    if (!mountedRef.current) return result; // page left before this resolved — don't write a stale card into the cache
    acknowledgedInput.current = { ...effective, fields: acknowledgedFields };
    void qc.invalidateQueries({ queryKey: keys.cards, refetchType: 'none' });
    void qc.invalidateQueries({ queryKey: keys.card(cardIdRef.current), refetchType: 'none' });
    return result;
  }, [createDraft, qc, canEditMetadata, idempotencyKey]);
  const autosave = useDraftAutosave(snapshot, save, { enabled: !!cardId && !blockedCard, acknowledgedSnapshot: submitted => acknowledgedInput.current ?? submitted });
  const { flush, saving } = autosave;
  const guard = useUnsavedChanges(autosave.dirty || saving);

  const leave = async (thenConfirm: boolean) => {
    if (!await flush()) return;
    const id = cardIdRef.current;
    if (!id) return;
    guard.allowNavigation();
    nav(`/cards/${id}${thenConfirm ? '?confirm=1' : ''}`);
  };
  // 카드를 만들고 되묻기로 넘어간다. 뒤로 가기로 이 화면에 돌아와 같은 카드를 또 만들지 않게 기록을 바꿔 둔다
  const startInterview = async () => {
    if (isBlankCardText(title) || createDraft.isPending) return;
    try {
      const card = await createDraft.mutateAsync({ input: { title, period, repoId: repoId || null, ...(activityId ? { activityId } : {}) }, idempotencyKey });
      track('manual_card_created', { cardId: card.id, interview: true });
      guard.allowNavigation();
      nav(card.status === 'CONFIRMED' ? `/cards/${card.id}` : `/cards/${card.id}/interview`, { replace: true });
    } catch { /* 실패 안내는 전역 알림이 보이고, 입력은 이 화면에 남는다 */ }
  };
  const repoOptions = [{ value: '', label: '없음' }, ...(repos.data ?? []).map(r => ({ value: r.id, label: `${r.owner}/${r.name}` }))];
  if (acknowledgedRepo && !repoOptions.some(option => option.value === acknowledgedRepo.id)) repoOptions.push({ value: acknowledgedRepo.id, label: `${acknowledgedRepo.owner}/${acknowledgedRepo.name}` });
  const activityOptions = [{ value: '', label: '없음' }, ...(activities.data ?? []).map(a => ({ value: a.id, label: a.name }))];
  if (acknowledgedActivity && !activityOptions.some(option => option.value === acknowledgedActivity.id)) activityOptions.push({ value: acknowledgedActivity.id, label: acknowledgedActivity.name });
  // 주소로 받은 레포·활동이 없는 것으로 확인되면 비운다(목록을 읽기 전에는 그대로 둔다). 첫 저장 뒤에는 서버가 정한 값이다
  useEffect(() => {
    if (cardId) return;
    if (repoId && repos.data && !repos.data.some(r => r.id === repoId)) setRepoId('');
    if (activityId && activities.data && !activities.data.some(a => a.id === activityId)) setActivityId('');
  }, [cardId, repoId, activityId, repos.data, activities.data]);

  const starting = !writing && !cardId;
  const locked = saving || createDraft.isPending;

  return (
    <main className="main main--footer main--tight manual-card-page">
      <Breadcrumb items={[{ label: '홈', to: '/' }, { label: '경험 카드', to: '/cards' }, { label: starting ? '경험 카드 만들기' : '직접 작성' }]} />
      {starting
        ? <PageTitle lead="제목만 정하면 AI 가 상황부터 하나씩 묻고, 답한 내용으로 상황·과제·행동·결과 네 칸을 채워요.">경험 카드 만들기</PageTitle>
        : <PageTitle>직접 작성</PageTitle>}

      <div className="card manual-metadata">
        <div className="manual-metadata__title">
          <Field label="카드 제목 (필수)">
            <Input autoFocus required maxLength={CONFIG.CARD_TITLE_MAX} aria-label="카드 제목" aria-describedby={starting ? undefined : 'manual-requirements'} value={title} disabled={locked || !!blockedCard || (!!cardId && !canEditMetadata)} onChange={(e) => setTitle(e.target.value)} placeholder="예) 로그인 세션 개선" />
          </Field>
        </div>
        <div><Field label="기간"><Input maxLength={CONFIG.CARD_PERIOD_MAX} value={period} disabled={locked || !!blockedCard || (!!cardId && !canEditMetadata)} onChange={(e) => setPeriod(e.target.value)} placeholder="2024.04" /></Field></div>
        <div>
          <Field label="관련 레포 (선택)">
            <Select className="select-trigger--field" value={repoId} onChange={(next) => { setRepoId(next); if (next) setActivityId(''); }} label="관련 레포" disabled={!!cardId || locked}
              options={repoOptions} />
          </Field>
        </div>
        <div style={{ gridColumn: '1 / -1' }}>
          <Field label="활동 (선택)" hint="동아리, 인턴처럼 레포 밖에서 한 경험이라면 활동 안에 둘 수 있어요. 레포와 활동은 하나만 고를 수 있어요.">
            <Select className="select-trigger--field" value={activityId} onChange={(next) => { setActivityId(next); if (next) setRepoId(''); }} label="활동" disabled={!!cardId || locked}
              options={activityOptions} />
          </Field>
        </div>
        {!cardId && <p className="manual-metadata__hint t-12 c-2">{canEditMetadata ? `관련 레포와 활동은 ${starting ? '카드를 만든' : '첫 저장'} 후 바꿀 수 없어요.` : `제목, 기간, 레포, 활동은 ${starting ? '카드를 만든' : '첫 저장'} 후 바꿀 수 없어요.`}</p>}
      </div>

      {!starting && <>
      <div className="manual-save-row">
        <p id="manual-requirements" className="t-12 c-2" aria-live="polite">{missing ? `확정까지: ${missing}` : '확정할 수 있어요'}</p>
        {!blockedCard && (cardId || autosave.status === 'error') && <SaveStatus status={autosave.status} retry={flush} />}
      </div>
      {blockedCard && <p role="alert">이미 확정된 카드예요. 현재 입력은 이 화면에 남아 있어요. <Link to={`/cards/${blockedCard.id}`}>기존 카드 보기</Link></p>}

      <div className="card star-read">
        {STAR_FIELDS.map((k, i) => (
          <StarEditorField key={k} field={k} dropped={i > 0 && !f[k]}
            label={<>{starFieldShort[k]}{(k === 'S' || k === 'A') && <span className="t-12 c-2"> (필수)</span>}</>}
            maxLength={MAX} aria-required={k === 'S' || k === 'A'} aria-describedby="manual-requirements"
            value={f[k]} onChange={(e) => { editedFields.current.add(k); const next = { ...latestFields.current, [k]: e.target.value }; latestFields.current = next; setF(next); }} placeholder={starFieldExample[k]} aria-label={starFieldName[k]} />
        ))}
      </div>
      </>}

      {starting ? (
        <StickyFooter strong={createDraft.isPending ? '카드를 만드는 중' : isBlankCardText(title) ? '제목을 적으면 시작할 수 있어요' : '질문은 상황부터 차례로 드려요'}>
          <Link to="/" className="btn btn--text">나가기</Link>
          <Button variant="outline" disabled={createDraft.isPending} onClick={() => setWriting(true)}>네 칸 직접 쓰기</Button>
          <Button size="lg" disabled={isBlankCardText(title)} loading={createDraft.isPending} onClick={() => void startInterview()}>질문 받으며 쓰기</Button>
        </StickyFooter>
      ) : (
      <StickyFooter
        strong={saving ? '저장 중' : autosave.failed ? '저장 상태를 확인해 주세요' : cardId ? '본문은 자동으로 저장돼요' : '제목을 적고 작성을 시작해 주세요'}>
        <Link to="/" className="btn btn--text">나가기</Link>
        {!cardId ? <Button size="lg" disabled={isBlankCardText(title)} loading={saving} onClick={() => void flush()}>작성 시작</Button> : <>
          <Button variant="outline" disabled={!!blockedCard || isBlankCardText(title)} loading={saving} onClick={() => void leave(false)}>저장 후 종료</Button>
          <Button size="lg" disabled={!canConfirm} loading={saving} onClick={() => void leave(true)}>확정</Button>
        </>}
      </StickyFooter>
      )}
      <UnsavedChangesDialog blocker={guard.blocker} saving={saving} flush={flush} />
    </main>
  );
}

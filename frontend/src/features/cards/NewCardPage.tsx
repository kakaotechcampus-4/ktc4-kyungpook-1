import { useCallback, useEffect, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import { Link, useNavigate } from 'react-router-dom';
import { useCreateManualDraft, useRepos } from '@/api/queries';
import { keys } from '@/api/keys';
import { useQueryClient } from '@tanstack/react-query';
import { endpoints } from '@/api/endpoints';
import { AuthError } from '@/api/client';
import { redirectToLogin } from '@/app/queryClient';
import { isBlankCardText, type Card, type StarField } from '@/api/schemas';
import { Breadcrumb, Button, Field, Input, PageTitle, StickyFooter } from '@/components/ui';
import { STAR_FIELDS, fieldKey, starFieldName, starFieldShort } from '@/lib/labels';
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

const HINT: Record<StarField, string> = {
  S: '어떤 상황이었나요?',
  T: '무엇을 해내야 했나요?',
  A: '내가 한 일을 적어 주세요.',
  R: '무엇이 달라졌나요?',
};
const MAX = CONFIG.MANUAL_FIELD_MAX;

/**
 * E4 정성 카드 직접 작성 — 코드에 없는 경험. 근거는 "내가 쓴 문장"이라 커밋이 없어도 저장된다.
 *
 * 첫 저장 버튼으로 DRAFT 와 cardId 를 만들고, 그 뒤 본문은 같은 카드로 자동 저장한다.
 * 브라우저에 남기지 않는 이유는 간단하다 — 여기 쓰는 글이 제일 날리기 아까운 글이기 때문이다.
 */
export function NewCardPage() {
  useDocumentTitle('직접 작성');
  const nav = useNavigate();
  const repos = useRepos();
  const createDraft = useCreateManualDraft();
  const [idempotencyKey] = useState(() => crypto.randomUUID());
  const [cardId, setCardId] = useState<string | null>(null);
  const cardIdRef = useRef<string | null>(null);
  const savedMetadata = useRef<{ title: string; period: string } | undefined>(undefined);
  const savedFields = useRef<string | undefined>(undefined);
  const confirmedReplay = useRef(false);
  const [blockedCard, setBlockedCard] = useState<Card | null>(null);
  const [acknowledgedRepo, setAcknowledgedRepo] = useState<Card['repo']>(null);
  const canEditMetadata = cardMetadataSupported();
  const qc = useQueryClient();
  const mountedRef = useRef(true);
  useEffect(() => { mountedRef.current = true; return () => { mountedRef.current = false; }; }, []);
  const [title, setTitle] = useState('');
  const [period, setPeriod] = useState('');
  const [repoId, setRepoId] = useState<string>('');
  const [f, setF] = useState<Record<StarField, string>>({ S: '', T: '', A: '', R: '' });
  const latestFields = useRef(f);
  latestFields.current = f;
  const editedFields = useRef(new Set<StarField>());

  const canConfirm = !blockedCard && !isBlankCardText(f.S) && !isBlankCardText(f.A) && !isBlankCardText(title);
  const missing = [isBlankCardText(title) && '제목', isBlankCardText(f.S) && '상황', isBlankCardText(f.A) && '행동'].filter(Boolean).join(' · ');

  const snapshot = { title, period, repoId, fields: toDraftFields(f) };
  const acknowledgedInput = useRef<typeof snapshot | null>(null);
  const save = useCallback(async (body: typeof snapshot) => {
    if (confirmedReplay.current) throw new ApiError('CARD_CONFIRMED', '이미 확정된 카드입니다.', 409);
    if (isBlankCardText(body.title)) throw new Error('TITLE_REQUIRED');
    let effective = body;
    let result: unknown;
    if (!cardIdRef.current) {
      const card = await createDraft.mutateAsync({ input: { title: body.title, period: body.period, repoId: body.repoId || null }, idempotencyKey });
      if (!mountedRef.current) return card;
      cardIdRef.current = card.id;
      const canonical = { title: card.title, period: card.period ?? '', repoId: card.repo?.id ?? '' };
      savedMetadata.current = { title: canonical.title, period: canonical.period };
      const returnedFields = Object.fromEntries(STAR_FIELDS.map(key => [key, card.version[fieldKey[key]] ?? ''])) as Record<StarField, string>;
      const merged = Object.fromEntries(STAR_FIELDS.map(key => [key, editedFields.current.has(key) ? latestFields.current[key] : returnedFields[key]])) as Record<StarField, string>;
      savedFields.current = JSON.stringify(toDraftFields(returnedFields));
      flushSync(() => {
        setCardId(card.id); setTitle(canonical.title); setPeriod(canonical.period); setRepoId(canonical.repoId); setAcknowledgedRepo(card.repo);
        if (card.status === 'CONFIRMED') { confirmedReplay.current = true; setBlockedCard(card); }
        else { latestFields.current = merged; setF(merged); }
      });
      if (card.status === 'CONFIRMED') throw new ApiError('CARD_CONFIRMED', '이미 확정된 카드입니다.', 409);
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
  const repoOptions = [{ value: '', label: '없음' }, ...(repos.data ?? []).map(r => ({ value: r.id, label: `${r.owner}/${r.name}` }))];
  if (acknowledgedRepo && !repoOptions.some(option => option.value === acknowledgedRepo.id)) repoOptions.push({ value: acknowledgedRepo.id, label: `${acknowledgedRepo.owner}/${acknowledgedRepo.name}` });

  return (
    <main className="main main--footer main--tight manual-card-page">
      <Breadcrumb items={[{ label: '홈', to: '/' }, { label: '경험 카드', to: '/cards' }, { label: '직접 작성' }]} />
      <PageTitle>직접 작성</PageTitle>

      <div className="card manual-metadata">
        <div className="manual-metadata__title">
          <Field label="카드 제목 *">
            <Input autoFocus required maxLength={CONFIG.CARD_TITLE_MAX} aria-label="카드 제목" aria-describedby="manual-requirements" value={title} disabled={saving || !!blockedCard || (!!cardId && !canEditMetadata)} onChange={(e) => setTitle(e.target.value)} placeholder="예) 로그인 세션 개선" />
          </Field>
        </div>
        <div><Field label="기간"><Input maxLength={CONFIG.CARD_PERIOD_MAX} value={period} disabled={saving || !!blockedCard || (!!cardId && !canEditMetadata)} onChange={(e) => setPeriod(e.target.value)} placeholder="2024.04" /></Field></div>
        <div>
          <Field label="관련 레포 (선택)">
            <Select className="select-trigger--field" value={repoId} onChange={setRepoId} label="관련 레포" disabled={!!cardId || saving}
              options={repoOptions} />
          </Field>
        </div>
        {!cardId && <p className="manual-metadata__hint t-12 c-2">{canEditMetadata ? '관련 레포는 첫 저장 후 바꿀 수 없어요.' : '제목·기간·레포는 첫 저장 후 바꿀 수 없어요.'}</p>}
      </div>

      <div className="manual-save-row">
        <p id="manual-requirements" className="t-12 c-2" aria-live="polite">{missing ? `확정까지: ${missing}` : '확정할 수 있어요'}</p>
        {!blockedCard && (cardId || autosave.status === 'error') && <SaveStatus status={autosave.status} retry={flush} />}
      </div>
      {blockedCard && <p role="alert">이미 확정된 카드입니다. 현재 입력은 이 화면에 남아 있습니다. <Link to={`/cards/${blockedCard.id}`}>기존 카드 보기</Link></p>}

      <div className="card star-read">
        {STAR_FIELDS.map((k, i) => (
          <StarEditorField key={k} field={k} dropped={i > 0 && !f[k]}
            label={<>{starFieldShort[k]}{(k === 'S' || k === 'A') && <span className="t-12 c-2"> · 필수</span>}</>}
            maxLength={MAX} aria-required={k === 'S' || k === 'A'} aria-describedby="manual-requirements"
            value={f[k]} onChange={(e) => { editedFields.current.add(k); const next = { ...latestFields.current, [k]: e.target.value }; latestFields.current = next; setF(next); }} placeholder={HINT[k]} aria-label={starFieldName[k]} />
        ))}
      </div>

      <StickyFooter
        strong={saving ? '저장 중' : autosave.failed ? '저장 상태를 확인해 주세요' : cardId ? '본문은 자동으로 저장됩니다' : '제목부터 저장해 주세요'}>
        <Link to="/" className="btn btn--text">나가기</Link>
        {!cardId ? <Button size="lg" disabled={isBlankCardText(title)} loading={saving} onClick={() => void flush()}>임시 저장 시작</Button> : <>
          <Button variant="outline" disabled={!!blockedCard || isBlankCardText(title)} loading={saving} onClick={() => void leave(false)}>저장 후 종료</Button>
          <Button size="lg" disabled={!canConfirm} loading={saving} onClick={() => void leave(true)}>확정</Button>
        </>}
      </StickyFooter>
      <UnsavedChangesDialog blocker={guard.blocker} saving={saving} flush={flush} />
    </main>
  );
}

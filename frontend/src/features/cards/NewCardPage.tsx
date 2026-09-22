import { useCallback, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useCreateManualDraft, useRepos } from '@/api/queries';
import { keys } from '@/api/keys';
import { useQueryClient } from '@tanstack/react-query';
import { endpoints } from '@/api/endpoints';
import type { StarField } from '@/api/schemas';
import { Breadcrumb, Button, EvidenceStrip, Field, Input, PageTitle, StarKey, StickyFooter, Textarea } from '@/components/ui';
import { STAR_FIELDS, starFieldName } from '@/lib/labels';
import { CONFIG } from '@/lib/config';
import { toDraftFields, useDraftAutosave } from '@/lib/useDraftAutosave';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { useUnsavedChanges } from '@/lib/useUnsavedChanges';
import { SaveStatus, UnsavedChangesDialog } from '@/components/SaveStatus';
import { cacheSavedDraft } from '@/lib/cacheSavedDraft';

const HINT: Record<StarField, string> = {
  S: '어떤 상황이었나요? 팀 규모와 맥락을 한 줄로.',
  T: '무엇을 해내야 했나요?',
  A: '본인이 무엇을 했나요? "우리" 대신 "나"로 쓰면 면접에서 흔들리지 않아요.',
  R: '무엇이 얼마나 달라졌나요? 숫자가 없으면 없다고 쓰면 돼요.',
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
  const [cardId, setCardId] = useState<string | null>(null);
  const cardIdRef = useRef<string | null>(null);
  const qc = useQueryClient();
  const [title, setTitle] = useState('');
  const [period, setPeriod] = useState('');
  const [repoId, setRepoId] = useState<string>('');
  const [f, setF] = useState<Record<StarField, string>>({ S: '', T: '', A: '', R: '' });

  const filled = STAR_FIELDS.filter((k) => f[k].trim());
  const canConfirm = !!f.S.trim() && !!f.A.trim() && !!title.trim();
  const missing = [!title.trim() && '제목', !f.S.trim() && '상황', !f.A.trim() && '행동'].filter(Boolean).join(' · ');

  const snapshot = { title, period, repoId, fields: toDraftFields(f) };
  const save = useCallback(async (body: typeof snapshot) => {
    if (!body.title.trim()) throw new Error('TITLE_REQUIRED');
    if (!cardIdRef.current) {
      const card = await createDraft.mutateAsync({ title: body.title, period: body.period, repoId: body.repoId || null });
      cardIdRef.current = card.id;
      setCardId(card.id);
      track('manual_card_created', { cardId: card.id });
    }
    const result = await endpoints.saveDraft(cardIdRef.current, body.fields);
    await cacheSavedDraft(qc, result);
    void qc.invalidateQueries({ queryKey: keys.cards, refetchType: 'none' });
    void qc.invalidateQueries({ queryKey: keys.card(cardIdRef.current), refetchType: 'none' });
    return result;
  }, [createDraft, qc]);
  const autosave = useDraftAutosave(snapshot, save, { enabled: !!cardId });
  const { flush, saving } = autosave;
  const guard = useUnsavedChanges(autosave.dirty || saving);

  const leave = async (thenConfirm: boolean) => {
    if (!await flush()) return;
    const id = cardIdRef.current;
    if (!id) return;
    guard.allowNavigation();
    nav(`/cards/${id}${thenConfirm ? '?confirm=1' : ''}`);
  };

  return (
    <main className="main main--footer main--tight">
      <Breadcrumb items={[{ label: '경험정리/홈', to: '/' }, { label: '경험 카드', to: '/cards' }, { label: '직접 작성' }]} />
      <PageTitle right={cardId ? '쓰는 동안 저장돼요' : '처음에는 저장 버튼을 눌러 주세요'}>코드에 없는 경험 쓰기</PageTitle>

      <div className="card row" style={{ gap: 16, padding: '16px 20px', alignItems: 'flex-end', flexWrap: 'wrap' }}>
        <div className="grow" style={{ minWidth: 240 }}>
          <Field label="카드 제목" hint="필수 · 제목을 입력하면 임시 저장을 시작할 수 있어요">
            <Input autoFocus required aria-label="카드 제목" aria-describedby="manual-requirements" value={title} disabled={!!cardId || saving} onChange={(e) => setTitle(e.target.value)} placeholder="예) 팀원과 토큰 저장 위치로 갈린 경험" />
          </Field>
        </div>
        <div style={{ width: 160 }}><Field label="기간"><Input value={period} disabled={!!cardId || saving} onChange={(e) => setPeriod(e.target.value)} placeholder="2024.04" /></Field></div>
        <div style={{ width: 220 }}>
          <Field label="관련 레포 (선택)">
            <select className="input" value={repoId} onChange={(e) => setRepoId(e.target.value)} aria-label="관련 레포" disabled={!!cardId || saving}>
              <option value="">없음</option>
              {(repos.data ?? []).map((r) => <option key={r.id} value={r.id}>{r.owner} / {r.name}</option>)}
            </select>
          </Field>
        </div>
      </div>

      <p className="t-12 c-2">제목·기간·관련 레포는 처음 저장한 뒤에는 수정할 수 없어요. 확인 후 저장해 주세요. 이후 본문은 자동 저장돼요.</p>
      <p id="manual-requirements" className="t-12 c-2" aria-live="polite">{missing ? `확정하려면 ${missing}을(를) 입력해 주세요. 제목만 있어도 임시 저장할 수 있어요.` : '제목·상황·행동이 준비됐어요. 확정으로 이동할 수 있어요.'}</p>
      <SaveStatus status={autosave.status} retry={flush} />
      {!cardId && <Button variant="outline" disabled={!title.trim()} loading={saving} onClick={() => void flush()}>임시 저장 시작</Button>}

      <div className="card star-read">
        {STAR_FIELDS.map((k, i) => (
          <div key={k} className="star-read__row">
            <StarKey field={k} dropped={i > 0 && !f[k]} />
            <div className="stack grow" style={{ gap: 9 }}>
              <div className="row"><span className="star__name">{starFieldName[k]}{(k === 'S' || k === 'A') && <span className="t-12 c-2"> · 확정 시 필수</span>}</span><span className="right t-12 c-3">{f[k].length} / {MAX}자</span></div>
              <Textarea className="input--lg" rows={3} maxLength={MAX} aria-required={k === 'S' || k === 'A'} aria-describedby="manual-requirements" value={f[k]} onChange={(e) => setF((x) => ({ ...x, [k]: e.target.value }))} placeholder={HINT[k]} aria-label={starFieldName[k]} />
              <EvidenceStrip e={{ field: k, type: 'USER_STATED', authoredBy: 'USER', sha: null, url: null, snippet: null, turnNo: 0 }} turnText="이 칸은 내가 쓴 문장으로 저장돼요" />
            </div>
          </div>
        ))}
      </div>

      <StickyFooter
        strong={filled.length ? `${filled.join(' · ')} 작성됨` : '상황과 행동만 채우면 확정할 수 있어요'}>
        <Link to="/" className="btn btn--text">나가기</Link>
        <Button variant="outline" disabled={!title.trim()} loading={saving} onClick={() => void leave(false)}>나중에 이어서</Button>
        <Button size="lg" disabled={!canConfirm} loading={saving} onClick={() => void leave(true)}>확정으로</Button>
      </StickyFooter>
      <UnsavedChangesDialog blocker={guard.blocker} saving={saving} flush={flush} />
    </main>
  );
}

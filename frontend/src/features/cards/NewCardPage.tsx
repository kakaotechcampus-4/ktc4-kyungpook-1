import { useCallback, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useCreateManualDraft, useRepos, useSaveDraft } from '@/api/queries';
import type { StarField } from '@/api/schemas';
import { Breadcrumb, Button, EvidenceStrip, Field, Input, PageTitle, StarKey, StickyFooter, Textarea } from '@/components/ui';
import { STAR_FIELDS, starFieldName } from '@/lib/labels';
import { CONFIG } from '@/lib/config';
import { toDraftFields, useDraftAutosave } from '@/lib/useDraftAutosave';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

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
 * 제목을 적는 순간 서버에 빈 DRAFT 를 먼저 만들고 cardId 를 받는다. 그 뒤 모든 입력은 그 카드로 임시 저장된다.
 * 브라우저에 남기지 않는 이유는 간단하다 — 여기 쓰는 글이 제일 날리기 아까운 글이기 때문이다.
 */
export function NewCardPage() {
  useDocumentTitle('직접 작성');
  const nav = useNavigate();
  const repos = useRepos();
  const createDraft = useCreateManualDraft();
  const [cardId, setCardId] = useState<string | null>(null);
  const saveDraft = useSaveDraft(cardId ?? '');
  const [title, setTitle] = useState('');
  const [period, setPeriod] = useState('');
  const [repoId, setRepoId] = useState<string>('');
  const [f, setF] = useState<Record<StarField, string>>({ S: '', T: '', A: '', R: '' });
  const [createFailed, setCreateFailed] = useState(false);
  const creating = useRef(false);

  const filled = STAR_FIELDS.filter((k) => f[k].trim());
  const canConfirm = !!f.S.trim() && !!f.A.trim() && !!title.trim();

  /** 제목이 생기면 카드 껍데기를 먼저 만든다. 두 번 만들지 않게 잠근다. */
  const ensureCard = useCallback(async (): Promise<string | null> => {
    if (cardId) return cardId;
    if (creating.current || !title.trim()) return null;
    creating.current = true;
    try {
      const card = await createDraft.mutateAsync({ title: title.trim(), period, repoId: repoId || null });
      setCardId(card.id);
      track('manual_card_created', { cardId: card.id });
      return card.id;
    } catch { setCreateFailed(true); return null; }
    finally { creating.current = false; }
  }, [cardId, title, period, repoId, createDraft]);

  // 카드가 아직 없으면 먼저 만들고 저장한다 — 화면은 제목만 쳐도 저장이 시작되는 것처럼 보인다
  const save = useCallback(async (body: Parameters<typeof saveDraft.mutateAsync>[0]) => {
    const id = await ensureCard();
    if (!id) throw new Error('NO_CARD');
    return saveDraft.mutateAsync(body);
  }, [ensureCard, saveDraft]);

  const { savedAt, failed, flush } = useDraftAutosave(toDraftFields(f), save, { enabled: !!title.trim() });

  const leave = async (thenConfirm: boolean) => {
    await flush();
    const id = cardId ?? (await ensureCard());
    if (!id) return;
    nav(`/cards/${id}${thenConfirm ? '?confirm=1' : ''}`);
  };

  return (
    <main className="main main--footer main--tight">
      <Breadcrumb items={[{ label: '경험정리/홈', to: '/' }, { label: '경험 카드', to: '/cards' }, { label: '직접 작성' }]} />
      <PageTitle right="쓰는 동안 저장돼요">코드에 없는 경험 쓰기</PageTitle>

      <div className="card row" style={{ gap: 16, padding: '16px 20px', alignItems: 'flex-end', flexWrap: 'wrap' }}>
        <div className="grow" style={{ minWidth: 240 }}>
          <Field label="카드 제목">
            <Input autoFocus value={title} onChange={(e) => setTitle(e.target.value)} onBlur={() => void ensureCard()} placeholder="예) 팀원과 토큰 저장 위치로 갈린 경험" />
          </Field>
        </div>
        <div style={{ width: 160 }}><Field label="기간"><Input value={period} onChange={(e) => setPeriod(e.target.value)} placeholder="2024.04" /></Field></div>
        <div style={{ width: 220 }}>
          <Field label="관련 레포 (선택)">
            <select className="input" value={repoId} onChange={(e) => setRepoId(e.target.value)} aria-label="관련 레포" disabled={!!cardId}>
              <option value="">없음</option>
              {(repos.data ?? []).map((r) => <option key={r.id} value={r.id}>{r.owner} / {r.name}</option>)}
            </select>
          </Field>
        </div>
      </div>

      <div className="card star-read">
        {STAR_FIELDS.map((k, i) => (
          <div key={k} className="star-read__row">
            <StarKey field={k} dropped={i > 0 && !f[k]} />
            <div className="stack grow" style={{ gap: 9 }}>
              <div className="row"><span className="star__name">{starFieldName[k]}</span><span className="right t-12 c-3">{f[k].length} / {MAX}자</span></div>
              <Textarea className="input--lg" rows={3} maxLength={MAX} value={f[k]} onChange={(e) => setF((x) => ({ ...x, [k]: e.target.value }))} placeholder={HINT[k]} aria-label={starFieldName[k]} />
              <EvidenceStrip e={{ field: k, type: 'USER_STATED', authoredBy: 'USER', sha: null, url: null, snippet: null, turnNo: 0 }} turnText="이 칸은 내가 쓴 문장으로 저장돼요" />
            </div>
          </div>
        ))}
      </div>

      <StickyFooter
        strong={failed || createFailed ? '저장이 안 됐어요' : savedAt ? '저장됨' : filled.length ? `${filled.join(' · ')} 작성됨` : '상황과 행동만 채우면 확정할 수 있어요'}
        sub={failed || createFailed ? '연결을 확인하고 다시 눌러 주세요' : undefined}>
        <Link to="/" className="btn btn--text">나가기</Link>
        <Button variant="outline" disabled={!title.trim()} loading={createDraft.isPending} onClick={() => void leave(false)}>나중에 이어서</Button>
        <Button size="lg" disabled={!canConfirm} loading={createDraft.isPending || saveDraft.isPending} onClick={() => void leave(true)}>확정으로</Button>
      </StickyFooter>
    </main>
  );
}

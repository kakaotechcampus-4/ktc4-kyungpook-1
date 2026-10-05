import { useCallback, useEffect, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import { useQueryClient } from '@tanstack/react-query';
import { keys } from '@/api/keys';
import type { Card, DraftFields, StarField } from '@/api/schemas';
import { Badge, Button, Chip, EvidenceStrip, Input, StarKey, StickyFooter, Textarea } from '@/components/ui';
import { STAR_FIELDS, fieldKey, starFieldName } from '@/lib/labels';
import { useMask, useSaveDraft } from '@/api/queries';
import { toast } from '@/lib/toast';
import { track } from '@/lib/track';
import { toDraftFields, useDraftAutosave } from '@/lib/useDraftAutosave';
import { applyMask } from './StarBlock';
import { useUnsavedChanges } from '@/lib/useUnsavedChanges';
import { SaveStatus, UnsavedChangesDialog } from '@/components/SaveStatus';
import { cacheSavedDraft } from '@/lib/cacheSavedDraft';

type Draft = Record<StarField, string>;
const fromCard = (c: Card): Draft => ({ S: c.version.situation ?? '', T: c.version.task ?? '', A: c.version.action ?? '', R: c.version.result ?? '' });

/**
 * D6 직접 수정 — 사용자 문장은 윤문·병합하지 않는다.
 *
 * 입력은 멈춘 뒤 잠깐 있다가 서버에 임시 저장된다(PATCH /cards/:id/draft). 브라우저에는 아무것도 남기지 않는다 —
 * 다른 기기에서 열어도 이어서 쓸 수 있어야 하고, 캐시를 지웠다고 쓰던 글이 날아가면 안 되기 때문이다.
 * 임시 저장은 같은 버전을 덮어쓴다. AI 초안(v1)은 잠겨 있어 첫 수정 때 새 버전이 한 번 갈라지고, 그 뒤로는 그 버전을 계속 덮는다.
 */
export function EditMode({ card, onDone, initialField }: { card: Card; onDone: () => void; initialField?: StarField }) {
  const qc = useQueryClient();
  const saveDraft = useSaveDraft(card.id);
  const baseRef = useRef<Draft>(fromCard(card));           // 비교 기준은 들어올 때 한 번만 잡는다
  const [draft, setDraft] = useState<Draft>(() => fromCard(card));
  const fields = useRef<Partial<Record<StarField, HTMLTextAreaElement | null>>>({});
  useEffect(() => {
    const target = initialField && fields.current[initialField];
    if (target) { target.focus({ preventScroll: true }); target.scrollIntoView?.({ block: 'center' }); }
  }, [card.id, initialField]);
  const mountedRef = useRef(true);
  useEffect(() => { mountedRef.current = true; return () => { mountedRef.current = false; }; }, []);
  // 카드가 바뀌면(A → B) CardPage 가 key={card.id} 로 이 컴포넌트를 새로 마운트한다 — 비교 기준·자동저장 상태·취소 세션이
  // 카드마다 따로여야 A 의 저장 기록이 B 의 취소에 새어 나가지 않는다.
  const save = useCallback(async (fields: DraftFields) => {
    const saved = await saveDraft.mutateAsync(fields);
    if (!mountedRef.current) return saved; // 화면을 떠난 뒤 도착한 저장 — 캐시에 반영하지 않는다
    await cacheSavedDraft(qc, saved);
    return saved;
  }, [saveDraft, qc]);
  const autosave = useDraftAutosave(toDraftFields(draft), save);
  const { failed, flush, saving } = autosave;
  const guard = useUnsavedChanges(autosave.dirty || saving);
  // 취소가 flush()를 기다리는 동안 칸이 계속 열려 있으면, 그사이 타이핑한 내용이 되돌리기로 조용히 사라진다.
  const [canceling, setCanceling] = useState(false);

  const changed = STAR_FIELDS.filter((f) => baseRef.current[f] !== draft[f]);
  const done = async () => {
    if (!await flush()) return;
    void qc.invalidateQueries({ queryKey: keys.card(card.id), exact: true });
    guard.allowNavigation();
    if (changed.length) { track('card_edited', { cardId: card.id, fields: changed.join('') }); toast('변경한 내용을 저장했어요', { tone: 'success' }); }
    onDone();
  };
  // 취소 = 입력을 원문으로 되돌린 상태를 "저장된 것"으로 맞추는 일이다. 되돌린 값을 자동저장 훅이 보게 한 뒤(flushSync)
  // 같은 저장 경로(flush)로 보낸다 — 서버에 고친 내용이 이미 나갔으면 원문으로 복원하고(응답은 카드 캐시에 반영, 실패하면
  // 훅이 에러와 '다시 저장'을 보여 줘 화면을 유지한다), 아무것도 안 나갔으면 보낼 게 없어 요청 자체가 없다
  // (잠긴 AI 초안에 가짜 버전도 안 생긴다). 복원이 끝난 걸 확인한 뒤에만 닫는다.
  const cancel = async () => {
    setCanceling(true);
    flushSync(() => setDraft(baseRef.current));
    const restored = await flush();
    if (!mountedRef.current) return;
    setCanceling(false);
    if (!restored) return;
    guard.allowNavigation();
    onDone();
  };

  return (
    <>
      <div className="card star-read">
        {STAR_FIELDS.map((f) => {
          const ev = card.evidence.filter((e) => e.field === f);
          const dirty = changed.includes(f);
          return (
            <div key={f} className="star-read__row">
              <StarKey field={f} dropped={!draft[f]} />
              <div className="stack grow" style={{ gap: 10 }}>
                <div className="row" style={{ gap: 8 }}>
                  <span className="star__name">{starFieldName[f]}</span>
                  {dirty && <Badge kind="CAUTION">고친 칸</Badge>}
                  <span className="right t-12 c-3">{draft[f].length}자</span>
                  {dirty && <button type="button" className="t-12 w-500 c-2" disabled={canceling} onClick={() => setDraft((d) => ({ ...d, [f]: baseRef.current[f] }))}>되돌리기</button>}
                </div>
                <Textarea ref={(element) => { fields.current[f] = element; }} className="input--lg" rows={3} value={draft[f]} disabled={canceling} onChange={(e) => setDraft((d) => ({ ...d, [f]: e.target.value }))}
                  placeholder={ev.length ? '' : '여기 쓰시면 내가 쓴 문장으로 저장돼요'} aria-label={starFieldName[f]} />
                {ev.map((e, i) => <EvidenceStrip key={i} e={e} />)}
                {dirty && ev.some((e) => e.type === 'COMMIT') && (
                  <div className="star__why"><strong>커밋에 없는 내용은 근거가 뒷받침하지 못해요</strong></div>
                )}
              </div>
            </div>
          );
        })}
      </div>
      <SaveStatus status={autosave.status} retry={flush} />
      <StickyFooter
        strong={changed.length ? `${changed.length}칸 고침` : '고친 곳 없음'}
        sub={failed ? '연결을 확인하고 다시 눌러 주세요' : '쓰는 동안 알아서 저장돼요'}>
        <Button variant="text" disabled={saving || canceling} loading={canceling} onClick={() => void cancel()}>편집 취소</Button>
        <Button size="lg" disabled={canceling} loading={saving} onClick={done}>{failed ? '다시 저장하고 닫기' : '저장하고 닫기'}</Button>
      </StickyFooter>
      <UnsavedChangesDialog blocker={guard.blocker} saving={saving} flush={flush} />
    </>
  );
}

/** D7 마스킹 — 원문은 DB 에 그대로. 표시·내보내기에만 적용. */
export function MaskMode({ card, onDone }: { card: Card; onDone: () => void }) {
  const mask = useMask(card.id);
  // EditMode의 baseRef와 같은 이유 — card.maskRules 를 매 렌더 그대로 기준으로 삼으면, 순서만 다르게 돌아온
  // 백그라운드 리페치에도 JSON.stringify 비교가 달라져 아무것도 안 고쳤는데 dirty 로 뜬다. 진입 시 한 번만 고정한다.
  const initialRulesRef = useRef(card.maskRules.length ? card.maskRules : [{ from: '', to: '' }]);
  const [rules, setRules] = useState<{ from: string; to: string }[]>(initialRulesRef.current);
  const initialRules = initialRulesRef.current;
  const valid = rules.filter((r) => r.from.trim());
  const set = (i: number, k: 'from' | 'to', v: string) => setRules((rs) => rs.map((r, j) => (j === i ? { ...r, [k]: v } : r)));
  const hits = STAR_FIELDS.filter((f) => { const t = card.version[fieldKey[f]]; return t && applyMask(t, valid) !== t; });
  // EditMode·InterviewPage 와 같은 이유 — 규칙을 쓰다가 그냥 나가면 아무 경고 없이 사라진다.
  const dirty = JSON.stringify(rules) !== JSON.stringify(initialRules);
  const guard = useUnsavedChanges(dirty || mask.isPending); // 저장 요청이 진행되는 동안에도 이탈·새로고침을 막는다
  const saveMask = async () => {
    try {
      await mask.mutateAsync(valid);
      initialRulesRef.current = rules; // 서버가 받은 걸 확인한 뒤에야 '저장된 상태'가 된다
      track('card_masked', { cardId: card.id, rules: valid.length });
      return true;
    } catch { return false; }
  };
  const apply = async () => {
    if (!await saveMask()) return;
    toast(valid.length ? `마스킹 규칙 ${valid.length}개를 저장했습니다` : '마스킹을 해제했습니다', { tone: 'success' });
    guard.allowNavigation();
    onDone();
  };
  return (
    <>
      <div className="card stack" style={{ gap: 10, padding: '16px 20px' }}>
        <span className="t-12 w-600 c-3">치환 규칙</span>
        {rules.map((r, i) => (
          <div key={i} className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            <Input style={{ flex: 1, minWidth: 140 }} value={r.from} onChange={(e) => set(i, 'from', e.target.value)} placeholder="원문 (예: 민수 선배)" aria-label="원문" />
            <span className="c-3">→</span>
            <Input style={{ flex: 1, minWidth: 140 }} value={r.to} onChange={(e) => set(i, 'to', e.target.value)} placeholder="치환 (예: 팀원A)" aria-label="치환" />
            <Button variant="text" size="sm" onClick={() => setRules((rs) => rs.filter((_, j) => j !== i))} aria-label="규칙 삭제">✕</Button>
          </div>
        ))}
        <div className="row" style={{ gap: 8 }}>
          <Chip onClick={() => setRules((rs) => [...rs, { from: '', to: '' }])}>+ 규칙 추가</Chip>
          <Chip onClick={() => setRules((rs) => [...rs, { from: '', to: '팀원A' }])}>+ 팀원 익명화</Chip>
          <Chip onClick={() => setRules((rs) => [...rs, { from: '', to: 'B사' }])}>+ 회사명 가리기</Chip>
        </div>
      </div>
      <div className="card star-read">
        {STAR_FIELDS.map((f) => {
          const text = card.version[fieldKey[f]];
          if (!text) return null;
          const after = applyMask(text, valid);
          const hit = after !== text;
          return (
            <div key={f} className="star-read__row">
              <StarKey field={f} />
              <div className="stack grow" style={{ gap: 12 }}>
                <div className="row" style={{ gap: 8 }}><span className="star__name">{starFieldName[f]}</span>{hit && <Badge kind="CAUTION">마스킹 적용</Badge>}</div>
                <div className="cmp">
                  <div className="cmp__box"><span className="cmp__label">원문 (저장된 값)</span><span className="c-2">{text}</span></div>
                  <div className="cmp__box cmp__box--after"><span className="cmp__label">내보낼 때 (마스킹 적용)</span><span>{after}</span></div>
                </div>
              </div>
            </div>
          );
        })}
      </div>
      <StickyFooter strong={hits.length ? `${hits.length}칸에 적용` : '적용되는 칸 없음'} sub="원문은 그대로 · 표시할 때만 가림">
        <Button variant="outline" onClick={onDone}>카드로 돌아가기</Button>
        <Button loading={mask.isPending} onClick={() => void apply()}>마스킹 적용</Button>
      </StickyFooter>
      <UnsavedChangesDialog blocker={guard.blocker} saving={mask.isPending} flush={saveMask} />
    </>
  );
}

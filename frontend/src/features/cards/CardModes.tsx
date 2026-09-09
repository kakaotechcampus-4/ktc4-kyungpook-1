import { useEffect, useRef, useState } from 'react';
import type { Card, StarField } from '@/api/schemas';
import { Badge, Button, Chip, EvidenceStrip, Input, Note, StarKey, StickyFooter, Textarea } from '@/components/ui';
import { STAR_FIELDS, fieldKey, starFieldName } from '@/lib/labels';
import { useMask, useSaveVersion } from '@/api/queries';
import { CONFIG } from '@/lib/config';
import { toast } from '@/lib/toast';
import { track } from '@/lib/track';
import { applyMask } from './StarBlock';

const draftKey = (c: Card) => `gitory.draft.${c.id}.v${c.version.versionNo}`;
type Draft = Record<StarField, string>;
const fromCard = (c: Card): Draft => ({ S: c.version.situation ?? '', T: c.version.task ?? '', A: c.version.action ?? '', R: c.version.result ?? '' });

/**
 * D6 직접 수정 — 사용자 문장은 윤문·병합하지 않는다. 저장하면 v+1 (USER_EDIT). AI 초안 v1 은 그대로 남는다.
 * 편집 중 내용은 이 브라우저에 자동 저장돼 새로고침·이탈 후에도 복원된다. 서버 버전은 [저장]을 눌렀을 때만 생긴다.
 */
export function EditMode({ card, onDone }: { card: Card; onDone: () => void }) {
  const save = useSaveVersion(card.id);
  const [draft, setDraft] = useState<Draft>(() => fromCard(card));
  const [restored, setRestored] = useState(false);
  const [savedAt, setSavedAt] = useState<number | null>(null);
  const timer = useRef<number | null>(null);

  // 복원
  useEffect(() => {
    try {
      const raw = localStorage.getItem(draftKey(card));
      if (raw) {
        const d = JSON.parse(raw) as Draft;
        if (JSON.stringify(d) !== JSON.stringify(fromCard(card))) { setDraft(d); setRestored(true); }
      }
    } catch { /* noop */ }
  }, [card]);
  // 자동 저장 (디바운스)
  useEffect(() => {
    if (timer.current) window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => {
      const dirty = JSON.stringify(draft) !== JSON.stringify(fromCard(card));
      try { if (dirty) { localStorage.setItem(draftKey(card), JSON.stringify(draft)); setSavedAt(Date.now()); } else localStorage.removeItem(draftKey(card)); } catch { /* noop */ }
    }, CONFIG.AUTOSAVE_MS);
    return () => { if (timer.current) window.clearTimeout(timer.current); };
  }, [draft, card]);

  const changed = STAR_FIELDS.filter((f) => (card.version[fieldKey[f]] ?? '') !== draft[f]);
  const discard = () => { setDraft(fromCard(card)); setRestored(false); try { localStorage.removeItem(draftKey(card)); } catch { /* noop */ } };
  const submit = async () => {
    const body: Record<string, string | null> = {};
    changed.forEach((f) => (body[fieldKey[f]] = draft[f].trim() || null));
    await save.mutateAsync(body);
    try { localStorage.removeItem(draftKey(card)); } catch { /* noop */ }
    track('card_edited', { cardId: card.id, fields: changed.join('') });
    toast(`v${card.version.versionNo + 1} 로 저장했습니다 — AI 초안 v1 은 그대로 남습니다`, { tone: 'success' });
    onDone();
  };
  return (
    <>
      {restored && <Note strong="편집 중이던 내용을 복원했어요" tone="caution"><button type="button" className="w-600" style={{ textDecoration: 'underline', marginLeft: 6 }} onClick={discard}>버리고 원본으로</button></Note>}
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
                  {dirty && <Badge kind="CAUTION">편집 중</Badge>}
                  <span className="right t-12 c-3">{draft[f].length}자</span>
                  {dirty && <button type="button" className="t-12 w-500 c-2" onClick={() => setDraft((d) => ({ ...d, [f]: card.version[fieldKey[f]] ?? '' }))}>되돌리기</button>}
                </div>
                <Textarea className="input--lg" rows={3} value={draft[f]} onChange={(e) => setDraft((d) => ({ ...d, [f]: e.target.value }))}
                  placeholder={ev.length ? '' : '이 칸은 근거가 없습니다. 쓰면 내가 말한 것(USER_STATED)으로 저장됩니다.'} aria-label={starFieldName[f]} />
                {ev.map((e, i) => <EvidenceStrip key={i} e={e} />)}
                {dirty && ev.some((e) => e.type === 'COMMIT') && (
                  <div className="star__why"><strong>커밋에 없는 내용은 근거가 뒷받침하지 못해요</strong></div>
                )}
              </div>
            </div>
          );
        })}
      </div>
      <StickyFooter strong={changed.length ? `${changed.length}칸 수정${savedAt ? ' · 임시 저장됨' : ''}` : '변경 없음'} sub="AI 초안 v1 은 그대로 남습니다">
        <Button variant="text" onClick={() => { discard(); onDone(); }}>편집 취소</Button>
        <Button size="lg" disabled={!changed.length} loading={save.isPending} onClick={submit}>v{card.version.versionNo + 1} 로 저장</Button>
      </StickyFooter>
    </>
  );
}

/** D7 마스킹 — 원문은 DB 에 그대로. 표시·내보내기에만 적용. */
export function MaskMode({ card, onDone }: { card: Card; onDone: () => void }) {
  const mask = useMask(card.id);
  const [rules, setRules] = useState<{ from: string; to: string }[]>(card.maskRules.length ? card.maskRules : [{ from: '', to: '' }]);
  const valid = rules.filter((r) => r.from.trim());
  const set = (i: number, k: 'from' | 'to', v: string) => setRules((rs) => rs.map((r, j) => (j === i ? { ...r, [k]: v } : r)));
  const hits = STAR_FIELDS.filter((f) => { const t = card.version[fieldKey[f]]; return t && applyMask(t, valid) !== t; });
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
        <Button loading={mask.isPending} onClick={async () => { await mask.mutateAsync(valid); track('card_masked', { cardId: card.id, rules: valid.length }); toast(valid.length ? `마스킹 규칙 ${valid.length}개를 저장했습니다` : '마스킹을 해제했습니다', { tone: 'success' }); onDone(); }}>마스킹 적용</Button>
      </StickyFooter>
    </>
  );
}

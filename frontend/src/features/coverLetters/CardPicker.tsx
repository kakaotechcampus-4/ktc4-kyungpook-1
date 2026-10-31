import { useState } from 'react';
import { useCards } from '@/api/queries';
import { useChooseCards, type CoverLetterQuestion } from '@/api/coverLetters';
import { Button, Radio, Skeleton } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { track } from '@/lib/track';

/**
 * ③ 문항의 중심·보조 카드 바꾸기. 내 확정 카드만 고를 수 있고, 다른 문항과 겹쳐도 막지 않는다(계약).
 * 중심과 보조가 같으면 서버가 400 이라 화면에서 먼저 막는다.
 */
export function CardPicker({ letterId, question, onClose }: { letterId: string; question: CoverLetterQuestion; onClose: () => void }) {
  const cards = useCards();
  const choose = useChooseCards(letterId);
  const [primary, setPrimary] = useState<string | null>(question.primaryCard?.id ?? null);
  const [supporting, setSupporting] = useState<string | null>(question.supportingCard?.id ?? null);
  const confirmed = (cards.data ?? []).filter((c) => c.status === 'CONFIRMED');
  const changed = primary !== (question.primaryCard?.id ?? null) || supporting !== (question.supportingCard?.id ?? null);

  const pickPrimary = (id: string) => { setPrimary(id); if (supporting === id) setSupporting(null); };
  const save = async () => {
    try {
      await choose.mutateAsync({ questionId: question.id, primaryCardId: primary, supportingCardId: supporting });
      track('cover_letter_cards_changed', { coverLetterId: letterId, questionSeq: question.seq });
      onClose();
    } catch { /* 실패는 전역 안내로 보이고 고른 값은 남는다 */ }
  };

  const options = (selected: string | null, onPick: (id: string | null) => void, disabledId: string | null, allowNone: boolean, name: string) => (
    <ul className="cl-pick" role="radiogroup" aria-label={name}>
      {allowNone && (
        <li><button type="button" role="radio" aria-checked={selected == null} className="cl-pick__row" onClick={() => onPick(null)}>
          <Radio checked={selected == null} /><span className="grow">쓰지 않음</span>
        </button></li>
      )}
      {confirmed.map((c) => (
        <li key={c.id}><button type="button" role="radio" aria-checked={selected === c.id} className="cl-pick__row" disabled={c.id === disabledId} onClick={() => onPick(c.id)}>
          <Radio checked={selected === c.id} />
          <span className="grow cl-pick__title">{c.title}</span>
          <span className="c-3 t-12">{c.kind === 'TECH' ? '기술' : '경험'}</span>
        </button></li>
      ))}
    </ul>
  );

  return (
    <Modal title={`${question.seq}번 문항 카드 바꾸기`} sub={question.text} width={560} onClose={onClose}
      footer={{ strong: '확정한 카드만 고를 수 있어요', sub: '다른 문항과 같은 카드를 골라도 돼요', actions: (
        <>
          <Button variant="outline" onClick={onClose}>닫기</Button>
          <Button onClick={save} loading={choose.isPending} disabled={!changed}>바꾸기</Button>
        </>
      ) }}>
      {cards.isPending ? <Skeleton h={120} /> : confirmed.length === 0 ? <p className="c-2">확정한 카드가 없어요.</p> : (
        <div className="stack" style={{ gap: 18 }}>
          <div className="stack" style={{ gap: 6 }}>
            <span className="field__label">중심 카드</span>
            <span className="t-12 c-2">이 문항 글의 뼈대가 되는 경험이에요.</span>
            {options(primary, (id) => (id ? pickPrimary(id) : setPrimary(null)), null, false, '중심 카드')}
          </div>
          <div className="stack" style={{ gap: 6 }}>
            <span className="field__label">보조 카드</span>
            <span className="t-12 c-2">중심 경험을 받쳐 줄 다른 경험이에요. 없어도 돼요.</span>
            {options(supporting, setSupporting, primary, true, '보조 카드')}
          </div>
        </div>
      )}
    </Modal>
  );
}

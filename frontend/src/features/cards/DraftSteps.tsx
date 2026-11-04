import { useId, useState } from 'react';
import { CircleCheck, FilePenLine, FileSearch, Flag, Hand, PencilLine, RefreshCw, Undo2, type LucideIcon } from 'lucide-react';
import type { Card, DraftStep, DraftStepKind } from '@/api/schemas';
import { Button } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { draftStepGroups, draftStepSummary } from '@/lib/draftSteps';
import './DraftSteps.css';

/** 아이콘은 장식이다. 무슨 단계인지는 문장이 말한다 */
export const kindIcon: Record<DraftStepKind, LucideIcon> = {
  DRAFT: FilePenLine, FLAG: Flag, OPEN: FileSearch, RETRY: RefreshCw, KEEP: Hand, REWRITE: PencilLine, PASS: CircleCheck, REVERT: Undo2,
};

/**
 * 카드 상세 오른쪽 '만든 과정' — 초안 → 고쳐 쓰기 → 검사 결과 세 줄과, 단계 전부를 여는 '자세히'.
 * 기록이 없으면 세 줄 대신 한 문장만 둔다. 후보 없이 만든 카드는 직접 쓴 카드이고, 후보가 있는데 기록이 없으면 기록 전에 만든 카드다.
 */
export function DraftProcess({ card }: { card: Card }) {
  const titleId = useId();
  const [open, setOpen] = useState(false);
  const lines = draftStepSummary(card.draftSteps);
  return (
    <section className="card stack draft-process" aria-labelledby={titleId}>
      <h2 id={titleId} className="draft-process__title">만든 과정</h2>
      {lines.length
        ? <ol className="aside-list">{lines.map((line) => <li key={line} className="t-12l c-2">{line}</li>)}</ol>
        : <p className="t-12l c-2">{card.candidate ? '만든 과정 기록이 없는 카드예요' : '직접 쓴 카드예요'}</p>}
      {lines.length > 0 && <Button variant="text" size="sm" className="draft-process__more" onClick={() => setOpen(true)}>자세히</Button>}
      {open && <DraftStepsDialog steps={card.draftSteps} onClose={() => setOpen(false)} />}
    </section>
  );
}

/**
 * '만든 과정' 전체 — 초안이 거친 단계를 서버 순서대로 보여 준다(#521).
 * reason 은 AI 가 쓴 글이라 '판단 이유' 이름표를 달아 카드 문장과 섞이지 않게 한다.
 */
export function DraftStepsDialog({ steps, onClose }: { steps: readonly DraftStep[] | null | undefined; onClose: () => void }) {
  const groups = draftStepGroups(steps);
  return (
    <Modal title="만든 과정" width={620} onClose={onClose}>
      <div className="draft-steps">
        <p className="draft-steps__lead">초안을 만들며 거친 단계예요. 판단은 글에 들어가지 않아요.</p>
        {groups.map((group) => (
          <div key={group.lines[0].index} className="draft-steps__group">
            {group.label && <h3 className="draft-steps__round">{group.label}</h3>}
            <ol className="draft-steps__list">
              {group.lines.map((line) => {
                const Icon = kindIcon[line.kind];
                return (
                  <li key={line.index} className={line.reason ? 'draft-step draft-step--judged' : 'draft-step'}>
                    <span className="draft-step__icon" aria-hidden><Icon size={14} /></span>
                    <div className="draft-step__body">
                      <p className="draft-step__text">{line.text}</p>
                      {line.detail && <p className="draft-step__detail">{line.detail}</p>}
                      {line.reason && <p className="draft-step__reason"><span className="draft-step__reason-label">판단 이유</span><q>{line.reason}</q></p>}
                    </div>
                  </li>
                );
              })}
            </ol>
          </div>
        ))}
      </div>
    </Modal>
  );
}

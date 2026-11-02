import { useId } from 'react';
import { Bot, CircleCheck, FilePenLine, FileSearch, Flag, Hand, PencilLine, RefreshCw, Undo2, type LucideIcon } from 'lucide-react';
import type { DraftStep, DraftStepKind } from '@/api/schemas';
import { IconBox } from '@/components/ui';
import { draftStepGroups } from '@/lib/draftSteps';
import './DraftSteps.css';

/** 아이콘은 장식이다. 무슨 단계인지는 문장이 말한다 */
const kindIcon: Record<DraftStepKind, LucideIcon> = {
  DRAFT: FilePenLine, FLAG: Flag, OPEN: FileSearch, RETRY: RefreshCw, KEEP: Hand, REWRITE: PencilLine, PASS: CircleCheck, REVERT: Undo2,
};

/**
 * 카드 상세의 'AI 가 한 일' (#521). AI 초안이 거친 단계를 서버 순서대로 보여 준다.
 * 그릴 단계가 없으면(직접 쓴 카드, 기록 전에 만든 카드, 이전 서버) 구역을 통째로 그리지 않는다.
 * 초안을 만드는 동안의 실시간 표시는 여기서 하지 않는다 — 끝난 카드의 기록만 보여 준다.
 * reason 은 AI 가 쓴 글이라 'AI 의 판단' 이름표를 달아 카드 문장과 섞이지 않게 한다.
 */
export function DraftSteps({ steps }: { steps: readonly DraftStep[] | null | undefined }) {
  const titleId = useId();
  const groups = draftStepGroups(steps);
  if (!groups.length) return null;
  return (
    <section className="card draft-steps" aria-labelledby={titleId}>
      <div className="draft-steps__head">
        <IconBox icon={Bot} size={32} />
        <div className="stack" style={{ gap: 2 }}>
          <h2 id={titleId} className="draft-steps__title">AI 가 한 일</h2>
          <p className="draft-steps__lead">초안을 만들며 AI 가 거친 단계예요. 'AI 의 판단'은 AI 가 쓴 글이고, 카드에 들어가는 문장이 아니에요.</p>
        </div>
      </div>
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
                    {line.reason && <p className="draft-step__reason"><span className="draft-step__reason-label">AI 의 판단</span><q>{line.reason}</q></p>}
                  </div>
                </li>
              );
            })}
          </ol>
        </div>
      ))}
    </section>
  );
}

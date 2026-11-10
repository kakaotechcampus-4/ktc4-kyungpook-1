import { isTerminal, type Job } from '@/api/schemas';
import { Note, Track } from '@/components/ui';
import { elapsed } from '@/lib/format';
import { jobActivity } from '@/lib/jobView';
import type { LiveView, SlotPhase } from '@/lib/liveDraft';
import { useNow } from '@/lib/useNow';
import './LiveCardDraft.css';

/** 서버가 이만큼 답이 없으면 멈춘 것처럼 보이므로 그때만 한 줄로 알린다 */
export const STALL_SEC = 60;
const FIELDS_PER_CARD = 4;
const FINISHED: readonly SlotPhase[] = ['DONE', 'BLANK', 'EMPTY', 'MISSED'];

/** 막대는 카드 수가 아니라 칸 수로 센다. 첫 카드가 끝나기 전에도 움직여 멈춘 것처럼 보이지 않는다 */
export function draftFieldProgress(view: LiveView): number {
  if (view.total <= 0) return 0;
  const written = view.finished.length * FIELDS_PER_CARD + (view.current?.slots.filter((slot) => FINISHED.includes(slot.phase)).length ?? 0);
  return Math.min(1, written / (view.total * FIELDS_PER_CARD));
}

/**
 * 카드 초안을 쓰는 화면 위쪽: 진행을 한 줄과 얇은 막대로만 말한다.
 * 매초 바뀌는 글이라 낭독 영역(aria-live)으로 두지 않는다.
 */
export function DraftHeader({ job, checkedAt, view }: { job?: Job; checkedAt: number; view: LiveView }) {
  const running = !job || !isTerminal(job.state);
  const now = useNow(!!job && running);
  // drafts 를 주지 않는 서버는 장 수만 안다. 칸 단위는 모르므로 끝난 장 수로 센다
  const reason = job?.steps.find((step) => step.key === 'REASON');
  const byCards = view.total === 0 && reason?.total != null && reason.total > 0 && reason.done >= 0 && reason.done <= reason.total ? { done: reason.done, total: reason.total } : null;
  const total = byCards?.total ?? view.total;
  const known = total > 0;
  const index = byCards ? Math.min(byCards.done, byCards.total - 1) : view.current?.index ?? 0;
  const activity = job ? jobActivity(job, checkedAt, now) : null;
  const stalled = !!job && running && checkedAt > 0 && !!activity && activity.sinceCheckSec > STALL_SEC;
  return (
    <section className="draft-line" aria-label="초안 작성 진행">
      <p className="draft-line__text">
        {running && <span className="draft-line__pulse" aria-hidden />}
        <span className="w-bold">{known ? `${total}장 중 ${index + 1}번째` : '카드 수를 세는 중이에요'}</span>
        {activity && running && <span className="c-2">{elapsed(activity.elapsedSec)}째</span>}
      </p>
      {known && <Track thin value={byCards ? byCards.done / byCards.total : draftFieldProgress(view)} label="초안 생성 진행률" />}
      {stalled && <p className="draft-line__stall">서버 응답을 기다리고 있어요</p>}
      {job?.state === 'FAILED' && <Note strong="초안 생성이 중단됐어요. 후보 보드에서 생성된 카드를 확인해 주세요." />}
      {job?.state === 'CANCELED' && <Note strong="초안 생성이 취소됐어요." />}
    </section>
  );
}

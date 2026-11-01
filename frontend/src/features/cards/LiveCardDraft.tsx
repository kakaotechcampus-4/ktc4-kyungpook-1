import { useEffect, useId, useRef, useState } from 'react';
import { Check, ChevronRight, CircleDashed, Minus, PencilLine, RefreshCw, TriangleAlert, type LucideIcon } from 'lucide-react';
import { Button } from '@/components/ui';
import { draftStepGroups } from '@/lib/draftSteps';
import { starFieldShort } from '@/lib/labels';
import type { LiveCard, LiveSlot, LiveView, SlotPhase } from '@/lib/liveDraft';
import './LiveCardDraft.css';

/** 색만으로 상태를 가르지 않는다. 칸 상태는 늘 글자로도 쓴다 */
const phaseLabel: Record<SlotPhase, string> = {
  WAITING: '대기', NEXT: '쓸 차례', WRITING: '쓰는 중', REWRITING: '다시 쓰는 중', DONE: '완료', BLANK: '비움', EMPTY: '비어 있음', MISSED: '쓰지 못함',
};
/** 아이콘은 장식이다 */
const phaseIcon: Record<SlotPhase, LucideIcon> = {
  WAITING: CircleDashed, NEXT: PencilLine, WRITING: PencilLine, REWRITING: RefreshCw, DONE: Check, BLANK: Minus, EMPTY: Minus, MISSED: TriangleAlert,
};
/** 칸 안에 글 대신 보이는 한 줄 */
const phaseNote: Partial<Record<SlotPhase, string>> = {
  BLANK: '근거가 부족해 비워 둠', EMPTY: '이 칸은 쓰지 않았어요', MISSED: '끝내지 못했어요',
};
const cardLabel: Record<LiveCard['state'], string> = {
  WAITING: '차례를 기다리는 중', READING: '코드를 읽는 중', WRITING: '쓰는 중', DONE: '다 썼어요', FAILED: '완성하지 못했어요',
};
/** 글이 아직 펼쳐지는 중인 칸은 낭독하지 않는다. 다 쓴 뒤에 읽는다 */
const unsettled = (phase: SlotPhase) => phase === 'WAITING' || phase === 'NEXT' || phase === 'WRITING' || phase === 'REWRITING';

const titleOf = (card: LiveCard) => card.title.trim() || `${card.index + 1}번째 카드`;

/**
 * 칸에 한 AI 의 과정. 카드 상세의 'AI 가 한 일'과 같은 문장·순서·구분 규칙(draftStepGroups)을 쓰되 칸 이름은 칸 제목이 말해서 뗀다.
 * 글은 모두 텍스트로만 그린다.
 */
function SlotSteps({ slot }: { slot: LiveSlot }) {
  const groups = draftStepGroups(slot.steps, slot.field);
  const lines = groups.flatMap((group) => group.lines.map((line, at) => ({ line, round: at === 0 ? group.label : null })));
  if (!lines.length) return null;
  return (
    <ol className="live-steps" aria-label={`${starFieldShort[slot.field]} 칸에서 AI 가 한 일`}>
      {lines.map(({ line, round }, at) => (
        <li key={line.index} className="live-step">
          {at > 0 && <ChevronRight className="live-step__sep" size={14} aria-hidden />}
          <span className="live-step__body">
            {round && <span className="live-step__round">{round}</span>}
            <span className="live-step__text">{line.text}</span>
            {line.detail && <span className="live-step__detail">{line.detail}</span>}
          </span>
        </li>
      ))}
    </ol>
  );
}
function Slot({ slot }: { slot: LiveSlot }) {
  const { field, phase } = slot;
  const Icon = phaseIcon[phase];
  const note = phaseNote[phase];
  const hidden = unsettled(phase);
  return (
    <li className="live-slot" data-field={field} data-phase={phase}>
      <div className="live-slot__head">
        <span className="live-slot__name">{starFieldShort[field]}</span>
        <span className="live-slot__status"><Icon size={14} aria-hidden />{phaseLabel[phase]}</span>
      </div>
      <p className="live-slot__body">
        {/* 서버가 준 전체 글로 칸 높이를 미리 잡는다(보이지 않는다). 글이 늘어도 아래 내용이 밀리지 않는다 */}
        <span className="live-slot__ghost" aria-hidden>{slot.full}</span>
        {note && !slot.text ? <span className="live-slot__note">{note}</span> : (
          <span className="live-slot__typed" data-live-text aria-hidden={hidden || undefined}>
            {slot.text}
            {slot.cursor && <span className="live-cursor" aria-hidden />}
          </span>
        )}
      </p>
      <SlotSteps slot={slot} />
    </li>
  );
}

function Slots({ card }: { card: LiveCard }) {
  return <ol className="live-slots">{card.slots.map((slot) => <Slot key={slot.field} slot={slot} />)}</ol>;
}

/** 다 쓴 카드는 접어 둔다. 눌러서 펼치면 네 칸의 글이 그대로 보인다 */
function FinishedCard({ card }: { card: LiveCard }) {
  const blanks = card.slots.filter((slot) => slot.phase === 'BLANK').length;
  const failed = card.state === 'FAILED';
  return (
    <details className="live-finished__item" data-state={card.state}>
      <summary className="live-finished__summary">
        <span className="live-finished__title">{titleOf(card)}</span>
        <span className="live-finished__state">{failed ? '완성하지 못했어요' : blanks ? `다 썼어요, 비운 칸 ${blanks}개` : '다 썼어요'}</span>
      </summary>
      <div className="live-finished__body"><Slots card={card} /></div>
    </details>
  );
}

/** 카드가 시작되고 끝날 때만 낭독한다. 글이 늘어나는 영역은 낭독 영역(aria-live)이 아니다 */
function useAnnouncement(current: LiveCard | null) {
  const [message, setMessage] = useState('');
  const announced = useRef('');
  const cardId = current?.cardId;
  const state = current?.state;
  const index = current?.index ?? 0;
  const title = current ? titleOf(current) : '';
  useEffect(() => {
    if (!cardId || !state) return;
    const phase = state === 'DONE' ? 'done' : state === 'FAILED' ? 'failed' : 'start';
    const key = `${cardId}:${phase}`;
    if (announced.current === key) return;
    announced.current = key;
    setMessage(phase === 'done' ? `${index + 1}번째 카드를 다 썼어요` : phase === 'failed' ? `${index + 1}번째 카드를 완성하지 못했어요` : `${index + 1}번째 카드를 쓰기 시작했어요. ${title}`);
  }, [cardId, state, index, title]);
  return message;
}

/**
 * 카드 초안을 쓰는 동안 AI 가 STAR 칸을 쓰는 모습(#617). 지금 쓰는 카드 한 장을 네 칸 틀로 보이고, 다 쓰면 다음 카드로 넘어간다.
 * 다 쓴 칸은 색을 채우고 아직 안 쓴 칸은 비워 둔다. 상태는 색뿐 아니라 칸 이름 옆 글자로도 쓴다.
 * 서버가 준 카드가 없으면(카드 초안 Job 이 아니거나 서버가 진행을 잃음) 아무것도 그리지 않는다. 진행 막대는 따로 있다.
 */
export function LiveCardDraft({ view, instant }: {
  view: LiveView;
  /** 글을 펼치지 않고 한 번에 보이는 중인지, 사용자가 누르는 버튼(동작 줄이기를 켠 사람에게는 필요 없어 null) */
  instant: { on: boolean; toggle: (() => void) | null };
}) {
  const titleId = useId();
  const cardTitleId = useId();
  const finishedId = useId();
  const { current, finished } = view;
  const announcement = useAnnouncement(current);
  if (!current) return null;
  return (
    <section className="card live-draft" aria-labelledby={titleId}>
      <div className="live-draft__head">
        <h2 id={titleId} className="live-draft__title">지금 쓰는 카드</h2>
        <span className="live-draft__count">{view.total}장 중 {current.index + 1}번째</span>
        {instant.toggle && <Button variant="outline" size="sm" aria-pressed={instant.on} onClick={instant.toggle}>한 번에 보기</Button>}
      </div>
      <p className="sr-only" role="status" aria-live="polite" aria-atomic="true">{announcement}</p>
      <article className="live-card" data-state={current.state} aria-labelledby={cardTitleId}>
        <header className="live-card__head">
          <h3 id={cardTitleId} className="live-card__title">{titleOf(current)}</h3>
          <span className="live-card__state">{cardLabel[current.state]}</span>
        </header>
        <Slots card={current} />
      </article>
      {finished.length > 0 && (
        <section className="live-finished" aria-labelledby={finishedId}>
          <h3 id={finishedId} className="live-finished__heading">앞서 쓴 카드 {finished.length}장</h3>
          {finished.map((card) => <FinishedCard key={card.cardId} card={card} />)}
        </section>
      )}
    </section>
  );
}


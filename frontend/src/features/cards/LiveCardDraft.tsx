import { useEffect, useId, useRef, useState } from 'react';
import { Check, CircleDashed, Minus, PencilLine, RefreshCw, TriangleAlert, type LucideIcon } from 'lucide-react';
import { ButtonLink } from '@/components/ui';
import { draftStepGroups } from '@/lib/draftSteps';
import { kindIcon } from './DraftSteps';
import { starFieldShort } from '@/lib/labels';
import type { LiveCard, LiveSlot, LiveView, SlotPhase } from '@/lib/liveDraft';
import './LiveCardDraft.css';

/** 색만으로 상태를 가르지 않는다. 칸 상태는 늘 글자로도 쓴다 */
const phaseLabel: Record<SlotPhase, string> = {
  WAITING: '대기', NEXT: '쓸 차례', WRITING: '쓰는 중', REWRITING: '고치는 중', DONE: '완료', BLANK: '비움', EMPTY: '비어 있음', MISSED: '쓰지 못함',
};
/** 아이콘은 장식이다 */
const phaseIcon: Record<SlotPhase, LucideIcon> = {
  WAITING: CircleDashed, NEXT: PencilLine, WRITING: PencilLine, REWRITING: RefreshCw, DONE: Check, BLANK: Minus, EMPTY: Minus, MISSED: TriangleAlert,
};
/** 칸 안에 글 대신 보이는 한 줄 */
const phaseNote: Partial<Record<SlotPhase, string>> = {
  BLANK: '근거가 부족해 비워 둠', EMPTY: '이 칸은 쓰지 않았어요', MISSED: '끝내지 못했어요',
};
/** 카드 제목 밑에 한 줄로 보이는 것은 글이 아직 시작되지 않았거나 끝내 못 쓴 카드뿐이다 */
const cardNote: Partial<Record<LiveCard['state'], string>> = { WAITING: '차례를 기다리고 있어요', READING: '코드를 읽고 있어요', FAILED: '완성하지 못했어요' };
/** 글이 아직 펼쳐지는 중인 칸은 낭독하지 않는다. 다 쓴 뒤에 읽는다 */
const unsettled = (phase: SlotPhase) => phase === 'WAITING' || phase === 'NEXT' || phase === 'WRITING' || phase === 'REWRITING';

const titleOf = (card: LiveCard) => card.title.trim() || `${card.index + 1}번째 카드`;
/**
 * 서버가 AI 에 보내지 않고 건너뛴 카드(커밋이 없는 후보로 만든 카드, CardDraftResult.SKIPPED). 칸 글 없이 DONE 으로 끝나 네 칸이 모두 EMPTY 다.
 * AI 가 근거가 없어 일부러 비운 칸(BLANK)과 다르다
 */
const skippedByAi = (card: LiveCard) => card.state === 'DONE' && card.slots.every((slot) => slot.phase === 'EMPTY');
const SKIPPED_LABEL = '커밋이 없어 AI 초안 없이 만들었어요';

/**
 * 칸에 한 AI 의 과정. 카드 상세 '만든 과정'과 같은 문장·순서·구분 규칙(draftStepGroups)과 같은 세로 모양을 쓰되 칸 이름은 칸 제목이 말해서 뗀다.
 * AI 가 스스로 고르고 이유를 남긴 단계는 카드 상세처럼 '고른 이유' 이름표를 달아 카드 문장과 섞이지 않게 한다. 글은 모두 텍스트로만 그린다.
 */
function SlotSteps({ slot }: { slot: LiveSlot }) {
  const groups = draftStepGroups(slot.steps, slot.field);
  const lines = groups.flatMap((group) => group.lines.map((line, at) => ({ line, round: at === 0 ? group.label : null })));
  if (!lines.length) return null;
  return (
    <ol className="live-steps" aria-label={`${starFieldShort[slot.field]} 칸에서 AI 가 한 일`}>
      {lines.map(({ line, round }) => {
        const Icon = kindIcon[line.kind];
        return (
          <li key={line.index} className="live-step" data-judged={line.reason ? '' : undefined} data-round={round ? '' : undefined}>
            <span className="live-step__icon" aria-hidden><Icon size={12} aria-hidden /></span>
            <span className="live-step__body">
              {round && <span className="live-step__round">{round}</span>}
              <span className="live-step__text">{line.text}</span>
              {line.detail && <span className="live-step__detail">{line.detail}</span>}
              {line.reason && <span className="live-step__reason"><span className="live-step__reason-label">고른 이유</span><q>{line.reason}</q></span>}
            </span>
          </li>
        );
      })}
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
      {slot.rewrote && !hidden && <span className="live-slot__rewrote">고쳐 썼어요</span>}
      <SlotSteps slot={slot} />
    </li>
  );
}

function Slots({ card }: { card: LiveCard }) {
  return <ol className="live-slots">{card.slots.map((slot) => <Slot key={slot.field} slot={slot} />)}</ol>;
}

/** 다 쓴 카드는 한 줄로 접어 둔다. 눌러서 펼치면 네 칸의 글이 그대로 보인다 */
function FinishedCard({ card }: { card: LiveCard }) {
  const blanks = card.slots.filter((slot) => slot.phase === 'BLANK').length;
  const failed = card.state === 'FAILED';
  const skipped = skippedByAi(card);
  return (
    <details className="live-finished__item" data-state={card.state}>
      <summary className="live-finished__summary">
        <span className="live-finished__title">{titleOf(card)}</span>
        <span className="live-finished__state">
          {!failed && !skipped && <Check size={14} aria-hidden />}
          {failed ? '완성하지 못했어요' : skipped ? SKIPPED_LABEL : blanks ? `다 썼어요, 비운 칸 ${blanks}개` : '다 썼어요'}
        </span>
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
 * 카드 초안을 쓰는 동안 AI 가 STAR 칸을 쓰는 모습(#617). 지금 쓰는 카드 한 장을 네 칸 틀로 보이고, 다 쓰면 한 줄 목록으로 내려간다.
 * 다 쓴 칸은 색을 채우고 아직 안 쓴 칸은 비워 둔다. 상태는 색뿐 아니라 칸 이름 옆 글자로도 쓴다. 진행 막대는 DraftHeader 가 맡는다.
 * 서버가 준 카드가 없으면(카드 초안 Job 이 아니거나 서버가 진행을 잃음) 아무것도 그리지 않는다.
 */
export function LiveCardDraft({ view }: { view: LiveView }) {
  const titleId = useId();
  const cardTitleId = useId();
  const finishedId = useId();
  const { current, finished } = view;
  const announcement = useAnnouncement(current);
  if (!current) return null;
  const note = skippedByAi(current) ? SKIPPED_LABEL : cardNote[current.state];
  return (
    <section className="card live-draft" aria-labelledby={titleId}>
      <h2 id={titleId} className="live-draft__title">지금 쓰는 카드</h2>
      <p className="sr-only" role="status" aria-live="polite" aria-atomic="true">{announcement}</p>
      <article className="live-card" data-state={current.state} aria-labelledby={cardTitleId}>
        <header className="live-card__head">
          <h3 id={cardTitleId} className="live-card__title">{titleOf(current)}</h3>
          {note && <p className="live-card__note">{note}</p>}
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

/** 화면 맨 아래 글자 링크. 이 화면에는 눌러야 할 큰 버튼이 없다 */
export function DraftFoot({ folderTo }: {
  /** 카드가 든 레포·활동 폴더. 없으면 링크를 두지 않는다 */
  folderTo?: string;
}) {
  return (
    <div className="live-foot">
      {folderTo && <ButtonLink to={folderTo} variant="text" size="sm">다 쓴 카드 먼저 보기</ButtonLink>}
      <ButtonLink to="/" variant="text" size="sm">홈으로</ButtonLink>
    </div>
  );
}

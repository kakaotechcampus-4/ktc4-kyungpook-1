import { Check } from 'lucide-react';
import { FLOW_STEPS, type FlowStep } from '@/lib/coverLetterCopy';

/**
 * 자소서 흐름 네 단계 중 지금 어디인지 — 이동 경로 바로 아래에 둔다(#745).
 * 좁은 화면에서는 '2/4 카드' 알약만 보이고, 단계 목록은 화면 읽기 프로그램에 그대로 남는다.
 */
export function Stepper({ current }: { current: FlowStep }) {
  return (
    <div className="cl-steps">
      {/* list-style 을 지우면 Safari 가 목록으로 읽지 않아 role 을 적어 둔다 */}
      <ol className="cl-steps__list" role="list" aria-label="진행 단계">
        {FLOW_STEPS.map((name, i) => {
          const n = i + 1;
          const state = n < current ? 'done' : n === current ? 'now' : 'next';
          return (
            <li key={name}>
              <span className={`cl-step cl-step--${state}`} aria-current={state === 'now' ? 'step' : undefined}>
                <span className="cl-step__n" aria-hidden>{state === 'done' ? <Check size={12} /> : n}</span>
                {name}
                {state === 'done' && <span className="sr-only"> 끝남</span>}
              </span>
            </li>
          );
        })}
      </ol>
      <span className="cl-steps__pill" aria-hidden>{current}/{FLOW_STEPS.length} {FLOW_STEPS[current - 1]}</span>
    </div>
  );
}

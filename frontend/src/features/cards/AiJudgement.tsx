import type { ReactNode } from 'react';
import './AiJudgement.css';

/**
 * 되묻기에서 AI 가 칸을 고르거나 그만 묻기로 한 이유 (#430).
 * 이유는 AI 가 쓴 글이라 카드 문장과 섞이지 않게 'AI 판단' 이름표와 따옴표, 왼쪽 선으로 따로 보인다.
 * 이유는 HTML 이 아니라 글로만 그린다. 긴 글은 줄바꿈하고, 색이 아니라 이름표 글자가 AI 의 글임을 말한다.
 */
export function AiJudgement({ head, children }: { head?: string; children: string }) {
  return (
    <p className="ai-judgement">
      <span className="ai-judgement__label">AI 판단</span>
      {head && <span className="ai-judgement__head">{head}</span>}
      <q className="ai-judgement__text">{children}</q>
    </p>
  );
}

/**
 * 새 질문의 이유나 끝난 알림이 오면 한 번만 읽어 주는 낭독 영역(aria-live polite) — 화면에 하나다.
 * 영역은 늘 DOM 에 있어야 안에 생긴 글이 읽히므로 이유가 없을 때도 비워 둔 채 그린다.
 * 매초 바뀌는 글이 아니고, 입력 글자 수 같은 자주 바뀌는 것은 안에 넣지 않는다.
 */
export function AiLive({ children }: { children?: ReactNode }) {
  return <div className="ai-live" aria-live="polite" aria-atomic="true">{children}</div>;
}

import { useEffect, useState } from 'react';

const LONG_MS = 10_000;

/**
 * AI 를 기다리는 동안 무엇을 하는 중인지 한 문장으로 말한다(#436). 회색 상자만 있으면 멈춘 건지 도는 건지 모른다.
 * 진행률은 모르면 만들지 않는다 — 대신 움직이는 점과 문장으로 살아 있음을 보이고, 10초가 넘으면 기다리는 이유를 덧붙인다.
 */
export function AiWait({ label, longLabel = '평소보다 오래 걸리고 있어요. 다른 화면으로 가도 이어서 처리돼요.' }: { label: string; longLabel?: string }) {
  const [long, setLong] = useState(false);
  useEffect(() => { const t = setTimeout(() => setLong(true), LONG_MS); return () => clearTimeout(t); }, []);
  return (
    <div className="ai-wait" role="status" aria-live="polite">
      <span className="ai-wait__dots" aria-hidden><i /><i /><i /></span>
      <div className="ai-wait__text">
        <span>{label}</span>
        {long && <span className="ai-wait__long">{longLabel}</span>}
      </div>
    </div>
  );
}

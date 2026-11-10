import { useEffect, useState } from 'react';

// 보여 주기용 예시 글이다. 사용자 활동이나 분석 결과로 보내지 않는다.
export const MORPH_PAIRS = [
  { sha: '9c02de1', commit: 'fix: 만료된 토큰 재발급 처리', sentence: '만료된 토큰을 자동으로 재발급해 사용자가 다시 로그인하지 않게 했습니다.' },
  { sha: 'a3f21c9', commit: 'feat: 결제 실패 재시도 큐 추가', sentence: '결제 실패를 큐에 쌓아 다시 시도하게 만들어 유실 주문을 0건으로 줄였습니다.' },
  { sha: '7f3e9a2', commit: 'test: 세션 만료 시나리오 추가', sentence: '세션 만료 시나리오를 테스트로 고정해 같은 장애가 다시 나지 않게 했습니다.' },
];

type Phase = 'commit' | 'sentence' | 'chip' | 'blank';
const reducedMotion = () => typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

/** 커밋 한 줄이 같은 자리에서 자소서 문장으로 바뀐다. 인트로가 끝난 뒤(active)에만 돈다 */
export function LandingMorph({ active }: { active: boolean }) {
  const [still] = useState(reducedMotion);
  const [index, setIndex] = useState(0);
  const [phase, setPhase] = useState<Phase>(still ? 'chip' : 'commit');

  useEffect(() => {
    if (still || !active) return;
    setPhase('commit');
    // 바뀌는 쪽이 사라지고 0.6초 뒤에 다음 글이 떠오른다 — 커밋에서 문장으로 갈 때와 다음 커밋으로 돌아갈 때 같은 빠르기다
    const steps: [number, () => void][] = [
      [2600, () => setPhase('sentence')],
      [4300, () => setPhase('chip')],
      [9800, () => setPhase('blank')],
      [10400, () => setIndex((value) => (value + 1) % MORPH_PAIRS.length)],
    ];
    const timers = steps.map(([ms, run]) => window.setTimeout(run, ms));
    return () => timers.forEach(window.clearTimeout);
  }, [active, index, still]);

  const pair = MORPH_PAIRS[index];
  return (
    <div className="landing-morph" data-phase={phase} aria-hidden="true">
      <p className="landing-morph__commit"><code>{pair.sha}</code> {pair.commit}</p>
      <p className="landing-morph__sentence">{pair.sentence}</p>
      <span className="landing-morph__chip">근거 <code>{pair.sha}</code></span>
    </div>
  );
}

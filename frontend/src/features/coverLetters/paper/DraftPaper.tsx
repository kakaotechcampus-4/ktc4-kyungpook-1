import { useEffect, useState } from 'react';
import type { CoverLetter } from '@/api/coverLetters';
import { ACTOR_LABEL, letterNotes } from '@/lib/coverLetterNotes';
import { PaperQuestion, type PaperLayout, type PaperMode } from './PaperQuestion';
import './paper.css';

const NARROW = '(max-width: 1100px)';
/** 여백을 둘 자리가 없는 폭이면 메모를 문항 아래로 — CSS 로 숨기기만 하면 폰에서 메모가 사라진다 */
export function useNarrow(): boolean {
  const [narrow, setNarrow] = useState(() => typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia(NARROW).matches);
  useEffect(() => {
    if (!window.matchMedia) return;
    const mq = window.matchMedia(NARROW);
    const on = () => setNarrow(mq.matches);
    mq.addEventListener?.('change', on);
    return () => mq.removeEventListener?.('change', on);
  }, []);
  return narrow;
}

/**
 * 자소서 종이 한 장(#618) — ② 카드 고르기 · ⑤ 쓰는 중 · ⑥ 고치기가 같은 종이를 쓴다. 문항 번호 → 문항 → 답 → 글자 수 순서의 실제 양식 모양.
 * 모든 문항이 한 장에 있고 쓰는 문항마다 커서가 움직인다. 메모는 오른쪽 여백, 좁은 화면에서는 문항 아래(규칙 8).
 */
export function DraftPaper({ letter, mode, texts, onText }: {
  letter: CoverLetter; mode: PaperMode; texts?: Record<string, string>; onText?: (id: string, text: string) => void;
}) {
  const layout: PaperLayout = useNarrow() ? 'inline' : 'margin';
  const assigning = letter.stage === 'ASSIGNING';
  const foot = letterNotes(letter.steps.draft, mode === 'assign' ? letter.steps.assign : []);
  const meta = [letter.posting ? `${letter.posting.company} · ${letter.posting.title}` : letter.jobRole, `문항 ${letter.questions.length}개`].join(' · ');

  return (
    <section className={`paper${layout === 'inline' ? ' paper--inline' : ''}`} aria-label="자기소개서">
      <header className="paper__head">
        <p className="paper__title">자기소개서</p>
        <span className="paper__meta">{meta}</span>
      </header>
      {letter.questions.map((q, i) => (
        <PaperQuestion key={q.id} letterId={letter.id} q={q} draftSteps={letter.steps.draft} assignSteps={letter.steps.assign}
          mode={mode} layout={layout} assigning={assigning} pollMs={letter.pollAfterMs ?? 1500} last={i === letter.questions.length - 1}
          text={texts?.[q.id]} onText={onText} />
      ))}
      <footer className={`paper__foot${foot.length ? '' : ' paper__foot--empty'}`}>
        {foot.map((i) => <span key={i.seq}><b>{ACTOR_LABEL[i.actor]}</b> {i.text}{i.reason ? ` — ${i.reason}` : ''}</span>)}
      </footer>
    </section>
  );
}

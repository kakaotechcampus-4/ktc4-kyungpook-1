import { useState } from 'react';
import type { CoverLetterQuestion } from '@/api/coverLetters';
import { repeatedSentences } from '@/lib/coverLetterNotes';

const excerpt = (s: string) => (s.length > 16 ? `${s.slice(0, 16).trim()}…` : s.replace(/[.]$/, ''));

/**
 * 패널 맨 위 알림 줄 — 살펴볼 게 있을 때만. 지금은 다른 문항과 겹친다는 표시(REPEATED)가 남은 문항마다 하나.
 * 공고 조건이 어느 문항에도 없다는 알림은 응답에 공고 조건이 없어 아직 만들지 않는다.
 */
export function Notices({ questions, texts, onGo }: {
  questions: Pick<CoverLetterQuestion, 'id' | 'seq' | 'draft'>[]; texts: Record<string, string>; onGo: (questionId: string) => void;
}) {
  const [closed, setClosed] = useState<Set<string>>(() => new Set());
  const byQuestion = new Map<string, { seq: number; sentence: string }>();
  for (const r of repeatedSentences(questions, texts)) if (!byQuestion.has(r.questionId)) byQuestion.set(r.questionId, r);
  const list = [...byQuestion].filter(([id]) => !closed.has(id));
  if (list.length === 0) return null;
  return (
    <div className="clp-notices" role="region" aria-label="살펴볼 것">
      {list.map(([id, { seq, sentence }]) => {
        const others = questions.filter((q) => q.id !== id);
        const title = others.length === 1
          ? `Q${Math.min(seq, others[0].seq)}·Q${Math.max(seq, others[0].seq)}에 같은 이야기가 있어요`
          : `Q${seq}에 다른 문항과 같은 이야기가 있어요`;
        return (
          <div key={id} className="clp-notice">
            <div className="clp-notice__body">
              <b className="clp-notice__t">{title}</b>
              <span className="clp-notice__w">
                Q{seq}의 "{excerpt(sentence)}" 문장을 바꾸는 쪽이 쉬워요{' '}
                <button type="button" className="clp-notice__go" onClick={() => onGo(id)}>→ Q{seq} 보기</button>
              </span>
            </div>
            <button type="button" className="clp-notice__x" aria-label="이 알림 닫기" onClick={() => setClosed((s) => new Set(s).add(id))}>닫기</button>
          </div>
        );
      })}
    </div>
  );
}

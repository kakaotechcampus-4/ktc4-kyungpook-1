import { useState } from 'react';
import type { CoverLetterQuestion } from '@/api/coverLetters';
import { ITEM_LABEL } from '@/lib/coverLetterCopy';
import { repeatedSentences } from '@/lib/coverLetterNotes';
import { blanksIn } from '@/lib/draftText';

const excerpt = (s: string) => (s.length > 16 ? `${s.slice(0, 16).trim()}…` : s.replace(/[.]$/, ''));

type Notice = { key: string; questionId: string; title: string; line: string; go: string; onClick: () => void };

/**
 * 패널 맨 위 알림 줄 — 살펴볼 게 있을 때만. 다른 문항과 겹친다는 표시(REPEATED)가 남은 문항마다 하나('→ Qn 보기'),
 * 필수 재료 빈칸이 남은 문항마다 하나('→ Qn에 넣기', 누르면 채우기에서 그 문항 이야기를 시작한다 · #733).
 * 질문 단계가 지원 이유를 글로 쓸 수 없다고 봤으면(memoUsable false · #738) 맨 위에 하나 — 지원 동기 문항(없으면 첫 문항)의 채우기를 연다.
 * 공고 조건이 어느 문항에도 없다는 알림은 응답에 공고 조건이 없어 아직 만들지 않는다.
 */
export function Notices({ questions, texts, onGo, onTalk, memoUsable }: {
  questions: Pick<CoverLetterQuestion, 'id' | 'seq' | 'draft' | 'theme'>[]; texts: Record<string, string>; onGo: (questionId: string) => void;
  onTalk?: (questionId: string) => void; memoUsable?: boolean | null;
}) {
  const [closed, setClosed] = useState<Set<string>>(() => new Set());
  const byQuestion = new Map<string, { seq: number; sentence: string }>();
  for (const r of repeatedSentences(questions, texts)) if (!byQuestion.has(r.questionId)) byQuestion.set(r.questionId, r);
  const list: Notice[] = [...byQuestion].map(([id, { seq, sentence }]) => {
    const others = questions.filter((q) => q.id !== id);
    return {
      key: id, questionId: id,
      title: others.length === 1
        ? `Q${Math.min(seq, others[0].seq)}, Q${Math.max(seq, others[0].seq)}에 같은 이야기가 있어요`
        : `Q${seq}에 다른 문항과 같은 이야기가 있어요`,
      line: `Q${seq}의 "${excerpt(sentence)}" 문장을 바꾸는 쪽이 쉬워요`, go: `Q${seq} 보기`, onClick: () => onGo(id),
    };
  });
  const reasonQ = questions.find((q) => q.theme === 'MOTIVATION') ?? questions[0];
  if (onTalk && memoUsable === false && reasonQ) {
    list.unshift({
      key: 'reason', questionId: reasonQ.id, title: '지원 이유가 글로 쓰기 어려워서 채팅에서 다시 물어요',
      line: '', go: '채우기', onClick: () => onTalk(reasonQ.id),
    });
  }
  if (onTalk) {
    for (const q of questions) {
      const item = blanksIn(texts[q.id] ?? q.draft?.text ?? '')[0];
      if (!item) continue;
      list.push({
        key: `blank:${q.id}`, questionId: q.id, title: `Q${q.seq}에 '${ITEM_LABEL[item]}' 빈칸이 남아 있어요`,
        line: '한 줄로 알려 주면 그 자리를 채워요', go: `Q${q.seq}에 넣기`, onClick: () => onTalk(q.id),
      });
    }
  }
  const shown = list.filter((n) => !closed.has(n.key));
  if (shown.length === 0) return null;
  return (
    <div className="clp-notices" role="region" aria-label="살펴볼 것">
      {shown.map((n) => (
        <div key={n.key} className="clp-notice">
          <div className="clp-notice__body">
            <b className="clp-notice__t">{n.title}</b>
            <span className="clp-notice__w">
              {n.line && `${n.line} `}
              <button type="button" className="clp-notice__go" onClick={n.onClick}>{n.go}</button>
            </span>
          </div>
          <button type="button" className="clp-notice__x" aria-label="이 알림 닫기" onClick={() => setClosed((s) => new Set(s).add(n.key))}>닫기</button>
        </div>
      ))}
    </div>
  );
}

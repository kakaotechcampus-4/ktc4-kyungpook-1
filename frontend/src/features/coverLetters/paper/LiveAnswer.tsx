import { useEffect, useMemo } from 'react';
import type { NoteThread } from '@/lib/coverLetterNotes';
import { locateSentence } from '@/lib/draftPaper';
import { useLiveText } from '@/lib/useLiveText';
import { TextRuns, type Highlight } from './TextRuns';

/**
 * ⑤ 쓰는 중의 답 칸 — 서버 글을 사람이 치듯 따라붙여 보이고, 걸린 문장은 지금 보이는 글에서 찾아 칠한다.
 * 첫 초안을 받는 중(composing)이거나 아직 따라잡는 중이면 끝에 커서가 깜빡인다.
 */
export function LiveAnswer({ target, active, composing, pollMs, threads, focusKey, onShown }: {
  target: string; active: boolean; composing: boolean; pollMs: number; threads: NoteThread[]; focusKey: string | null; onShown: (text: string) => void;
}) {
  const { segs, text, typing } = useLiveText(target, { active, pollMs });
  useEffect(() => { onShown(text); }, [text, onShown]);
  const highlights = useMemo<Highlight[]>(() => threads.flatMap((t) => {
    const r = locateSentence(text, t.sentence);
    return r ? [{ key: t.key, ...r, status: t.status, value: t.value, focus: focusKey === t.key }] : [];
  }), [threads, text, focusKey]);

  let offset = 0;
  const nodes = segs.map((s) => {
    if (s.kind === 'del') return <del key={s.id} className="pseg pseg--del">{s.text}</del>;
    const visible = s.text.slice(0, s.shown);
    const node = <TextRuns key={s.id} text={visible} offset={offset} highlights={highlights} />;
    offset += visible.length;
    return node;
  });
  const caret = composing || typing;
  return (
    <div className={`pq__answer${!text && !caret ? ' pq__answer--empty' : ''}`}>
      {nodes}
      {caret && <span className="pcaret" aria-hidden />}
    </div>
  );
}

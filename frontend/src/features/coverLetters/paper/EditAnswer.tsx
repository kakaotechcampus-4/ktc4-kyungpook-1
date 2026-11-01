import { useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Copy, RotateCcw } from 'lucide-react';
import { DRAFT_MAX, useSaveDraft, type CoverLetterQuestion } from '@/api/coverLetters';
import { errorView } from '@/api/errorView';
import { Button } from '@/components/ui';
import { copyText } from '@/lib/clipboard';
import { ITEM_LABEL } from '@/lib/coverLetterCopy';
import type { NoteThread } from '@/lib/coverLetterNotes';
import { locateSentence } from '@/lib/draftPaper';
import { blanksIn, moreToWrite } from '@/lib/draftText';
import { track } from '@/lib/track';
import { TextRuns, type Highlight } from './TextRuns';

const fitHeight = (el: HTMLTextAreaElement) => {
  el.style.height = '0px';
  el.style.height = `${el.scrollHeight}px`;
};

/**
 * ⑥ 고치는 답 칸 — 실제 textarea 를 종이 모양으로 두고, 칠하기는 뒤에 같은 글을 그린 층이 맡는다(한국어 조합 입력이 깨지지 않게).
 * 저장(칸을 벗어날 때·버튼)·복사·AI 원본·채울 곳은 예전 ⑥ 과 같은 규칙이고 자리만 옮겼다.
 */
export function EditAnswer({ letterId, q, text, onText, threads, focusKey, foot }: {
  letterId: string; q: CoverLetterQuestion; text: string; onText: (text: string) => void; threads: NoteThread[]; focusKey: string | null; foot: ReactNode;
}) {
  const d = q.draft!;
  const [saved, setSaved] = useState(d.text);
  const [showOriginal, setShowOriginal] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const area = useRef<HTMLTextAreaElement>(null);
  const save = useSaveDraft(letterId);
  const dirty = text !== saved;
  const blanks = blanksIn(text);
  const more = moreToWrite(text);

  // 글상자는 안에서 스크롤하지 않고 글 높이만큼 자란다 — 뒤의 칠하기 층과 줄이 어긋나지 않게
  useLayoutEffect(() => {
    if (area.current) fitHeight(area.current);
  }, [text]);
  // 폭이 바뀌면(창 크기·메모 자리) 줄바꿈이 달라지니 다시 잰다 — 높이만 바뀐 알림은 맞춘 결과라 건너뛴다
  useEffect(() => {
    const el = area.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    let width = -1;
    const ro = new ResizeObserver(([entry]) => {
      const w = entry?.contentRect.width ?? 0;
      if (w === width) return;
      width = w;
      fitHeight(el);
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const highlights = useMemo<Highlight[]>(() => threads.flatMap((t) => {
    const r = locateSentence(text, t.sentence);
    return r ? [{ key: t.key, ...r, status: t.status, value: t.value, focus: focusKey === t.key }] : [];
  }), [threads, text, focusKey]);

  const persist = async () => {
    if (!dirty || text.length > DRAFT_MAX) return;
    try {
      const letter = await save.mutateAsync({ questionId: q.id, text });
      setSaved(letter.questions.find((x) => x.id === q.id)?.draft?.text ?? text);
      setNote(null);
      track('cover_letter_edited', { coverLetterId: letterId, questionSeq: q.seq });
    } catch (e) { setNote(`${errorView(e).message} 고친 글은 화면에 그대로 있어요.`); }
  };
  const copy = async () => {
    const ok = await copyText(text);
    if (!ok) { area.current?.select(); setNote('복사하지 못했어요. 글이 선택돼 있으니 직접 복사해 주세요.'); return; }
    setNote(blanks.length ? `복사했어요. 아직 채우지 않은 빈칸 ${blanks.length}개가 함께 복사됐어요.` : '복사했어요.');
    track('cover_letter_copied', { coverLetterId: letterId, scope: 'question', blanks: blanks.length });
  };

  return (
    <>
      <div className="pedit">
        <div className="pedit__mirror" aria-hidden><TextRuns text={text} offset={0} highlights={highlights} />{'​'}</div>
        <textarea ref={area} className="pedit__area" value={text} rows={1} maxLength={DRAFT_MAX} spellCheck={false} aria-label={`${q.seq}번 문항 초안`}
          onChange={(e) => onText(e.target.value)} onBlur={() => void persist()} />
      </div>
      {foot}
      <div className="pq__tools">
        {text !== d.aiText && <span className="t-12 c-3">고친 글</span>}
        <span className="right row" style={{ gap: 6 }}>
          <Button variant="ghost" size="sm" onClick={() => setShowOriginal((v) => !v)}>{showOriginal ? 'AI 원본 닫기' : 'AI 원본 보기'}</Button>
          <Button variant="outline" size="sm" onClick={copy}><Copy size={14} aria-hidden /> 복사</Button>
          <Button size="sm" onClick={persist} loading={save.isPending} disabled={!dirty}>{dirty ? '저장' : '저장됨'}</Button>
        </span>
      </div>
      {note && <p className="pq__note" role="status">{note}</p>}
      {(blanks.length > 0 || more.length > 0) && (
        <div className="cl-todo">
          <span className="cl-todo__title">채울 곳</span>
          <ul>
            {blanks.map((b) => <li key={b}><b>{ITEM_LABEL[b]}</b> 빈칸이 남아 있어요. 대괄호 문구를 지우고 내용을 적어 주세요.</li>)}
            {more.map((m, i) => <li key={`m${i}`}><b>더 쓸 거리</b> {m}</li>)}
          </ul>
        </div>
      )}
      {showOriginal && (
        <div className="cl-original">
          <div className="row" style={{ gap: 8 }}>
            <span className="t-12 c-2">AI가 쓴 원본</span>
            {text !== d.aiText && <button type="button" className="cl-link-btn t-12 right" onClick={() => onText(d.aiText)}><RotateCcw size={12} aria-hidden /> 원본으로 되돌리기</button>}
          </div>
          <p>{d.aiText}</p>
        </div>
      )}
    </>
  );
}

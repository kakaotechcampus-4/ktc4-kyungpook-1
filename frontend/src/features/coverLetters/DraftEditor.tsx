import { useRef, useState } from 'react';
import { Copy, RotateCcw } from 'lucide-react';
import { DRAFT_MAX, useSaveDraft, type CoverLetter, type CoverLetterQuestion } from '@/api/coverLetters';
import { errorView } from '@/api/errorView';
import { Badge, Button, Note, PageTitle } from '@/components/ui';
import { ITEM_LABEL, checkMessage } from '@/lib/coverLetterCopy';
import { blanksIn, countableLength, moreToWrite } from '@/lib/draftText';
import { track } from '@/lib/track';

/** 클립보드에 쓴다. 막힌 환경이면 false — 화면이 직접 고르라고 안내한다 */
async function copyText(text: string): Promise<boolean> {
  try { await navigator.clipboard.writeText(text); return true; } catch { return false; }
}

/**
 * ⑥ 초안(#518). 고치고 저장, 복사. 글자 수와 빈칸은 서버와 같은 기준으로 화면이 바로 센다(draftText.ts).
 * '확인 필요' 표시와 문장 번호는 AI 원본 기준이라, 고친 뒤에는 원본 문장을 함께 보여 어디를 봐야 하는지 알린다.
 */
export function DraftEditor({ letter }: { letter: CoverLetter }) {
  const [copied, setCopied] = useState<string | null>(null);
  const all = letter.questions.map((q) => `${q.seq}. ${q.text}\n\n${q.draft?.text ?? ''}`).join('\n\n\n');
  const copyAll = async () => {
    const ok = await copyText(all);
    setCopied(ok ? '모든 문항을 복사했어요' : '복사하지 못했어요. 글을 직접 선택해 복사해 주세요.');
    if (ok) track('cover_letter_copied', { coverLetterId: letter.id, scope: 'all', blanks: letter.questions.reduce((n, q) => n + (q.draft?.blanks.length ?? 0), 0) });
  };
  return (
    <>
      <PageTitle sub={`${letter.jobRole}, 문항 ${letter.questions.length}개. 고친 글은 저장하면 남고, AI가 쓴 원본도 언제든 다시 볼 수 있어요.`}
        right={<Button variant="outline" size="sm" onClick={copyAll}><Copy size={14} aria-hidden /> 전체 복사</Button>}>초안이 나왔어요</PageTitle>
      {copied && <p className="t-13 c-2" role="status">{copied}</p>}
      <div className="cl-review">
        {letter.questions.map((q) => <QuestionDraft key={q.id} letterId={letter.id} q={q} />)}
      </div>
    </>
  );
}

function QuestionDraft({ letterId, q }: { letterId: string; q: CoverLetterQuestion }) {
  const d = q.draft;
  const [text, setText] = useState(d?.text ?? '');
  const [saved, setSaved] = useState(d?.text ?? '');
  const [showOriginal, setShowOriginal] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const area = useRef<HTMLTextAreaElement>(null);
  const save = useSaveDraft(letterId);
  if (!d) return null;

  const count = countableLength(text);
  const over = Math.max(0, count - q.charLimit);
  const blanks = blanksIn(text);
  const more = moreToWrite(text);
  const dirty = text !== saved;
  const sentence = (seq: number | null | undefined) => d.sentences.find((s) => s.seq === seq)?.text ?? null;

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
    <section className="cl-q" aria-labelledby={`q-${q.id}`}>
      <header className="cl-q__head">
        <span className="cl-q__no" aria-hidden>{q.seq}</span>
        <div className="stack grow" style={{ gap: 4 }}>
          <h2 id={`q-${q.id}`} className="cl-q__text">{q.text}</h2>
          <span className="t-12 c-3">{q.primaryCard ? `${q.primaryCard.title} 카드로 씀` : '답으로 씀'}</span>
        </div>
        {text !== d.aiText && <Badge kind="NEUTRAL">고친 글</Badge>}
      </header>

      {d.source === 'SKELETON' && <Note strong="AI가 초안을 쓰지 못해 뼈대만 만들었어요" tone="caution">카드 문장과 빈칸으로 만든 뼈대예요. 빈칸을 채워 다듬어 주세요.</Note>}

      <div className="cl-indent stack" style={{ gap: 8 }}>
        <textarea ref={area} className="input cl-draft" value={text} maxLength={DRAFT_MAX} aria-label={`${q.seq}번 문항 초안`}
          onChange={(e) => setText(e.target.value)} onBlur={() => void persist()} />
        <div className="row cl-draft__meta">
          <span className={over ? 'cl-over' : 'c-2'} aria-live="polite">
            {count.toLocaleString()} / {q.charLimit.toLocaleString()}자{over ? `, ${over.toLocaleString()}자 넘음` : ''}
          </span>
          <span className="t-12 c-3">빈칸 문구는 글자 수에 넣지 않아요</span>
          <span className="right row" style={{ gap: 6 }}>
            <Button variant="ghost" size="sm" onClick={() => setShowOriginal((v) => !v)}>{showOriginal ? 'AI 원본 닫기' : 'AI 원본 보기'}</Button>
            <Button variant="outline" size="sm" onClick={copy}><Copy size={14} aria-hidden /> 복사</Button>
            <Button size="sm" onClick={persist} loading={save.isPending} disabled={!dirty}>{dirty ? '저장' : '저장됨'}</Button>
          </span>
        </div>
        {note && <p className="t-12 c-2" role="status">{note}</p>}

        {(blanks.length > 0 || more.length > 0) && (
          <div className="cl-todo">
            <span className="cl-todo__title">채울 곳</span>
            <ul>
              {blanks.map((b) => <li key={b}><b>{ITEM_LABEL[b]}</b> 빈칸이 남아 있어요. 대괄호 문구를 지우고 내용을 적어 주세요.</li>)}
              {more.map((m, i) => <li key={`m${i}`}><b>더 쓸 거리</b> {m}</li>)}
            </ul>
          </div>
        )}

        {d.marks.length > 0 && (
          <div className="cl-todo cl-todo--marks">
            <span className="cl-todo__title">확인 필요</span>
            <ul>
              {d.marks.map((m, i) => (
                <li key={i}>
                  <span>{checkMessage(m.code, m.value)}</span>
                  {sentence(m.sentenceSeq) && <q className="cl-todo__quote">{sentence(m.sentenceSeq)}</q>}
                </li>
              ))}
            </ul>
            <span className="t-12 c-3">AI가 근거를 찾지 못한 부분이에요. 맞는 내용이면 그대로 두고, 아니면 고쳐 주세요.</span>
          </div>
        )}

        {showOriginal && (
          <div className="cl-original">
            <div className="row" style={{ gap: 8 }}>
              <span className="t-12 c-2">AI가 쓴 원본</span>
              {text !== d.aiText && <button type="button" className="cl-link-btn t-12 right" onClick={() => setText(d.aiText)}><RotateCcw size={12} aria-hidden /> 원본으로 되돌리기</button>}
            </div>
            <p>{d.aiText}</p>
          </div>
        )}
      </div>
    </section>
  );
}

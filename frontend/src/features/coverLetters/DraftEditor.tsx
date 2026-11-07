import { useCallback, useRef, useState } from 'react';
import { Copy } from 'lucide-react';
import type { CoverLetter } from '@/api/coverLetters';
import { Button, PageTitle } from '@/components/ui';
import { copyText } from '@/lib/clipboard';
import { copiedExtras } from '@/lib/coverLetterCopy';
import { unconfirmedInferences } from '@/lib/coverLetterNotes';
import { blanksIn } from '@/lib/draftText';
import { track } from '@/lib/track';
import { useReducedMotion } from '@/lib/useLiveText';
import { DraftPanel } from './panel/DraftPanel';
import { Notices } from './panel/Notices';
import { SHEET_QUERY, useActiveQuestion, useMatches } from './panel/useActiveQuestion';
import { DraftPaper } from './paper/DraftPaper';

/**
 * ⑥ 초안(#518 → #618 종이 → #727 옆 패널). 같은 종이에서 바로 고치고 저장·복사하고, 오른쪽 패널에서 첨삭·짐작 확인을 한다.
 * 글자 수와 빈칸은 서버와 같은 기준으로 화면이 바로 센다(draftText.ts).
 * 전체 복사는 서버 캐시가 아니라 화면에 있는 지금 글로 만든다 — 저장 전·저장 실패한 글도 빠지지 않게.
 */
export function DraftEditor({ letter }: { letter: CoverLetter }) {
  const [copied, setCopied] = useState<string | null>(null);
  const [texts, setTexts] = useState<Record<string, string>>(() => Object.fromEntries(letter.questions.map((q) => [q.id, q.draft?.text ?? ''])));
  const onText = useCallback((id: string, text: string) => setTexts((s) => ({ ...s, [id]: text })), []);
  const paperRef = useRef<HTMLDivElement>(null);
  const [lock, setLock] = useState<string | null>(null);
  const [focus, setFocus] = useState<{ questionId: string; key: string; n: number } | null>(null);
  const onFlag = useCallback((questionId: string, key: string) => setFocus((f) => ({ questionId, key, n: (f?.n ?? 0) + 1 })), []);
  const [talk, setTalk] = useState<{ questionId: string } | null>(null);
  const onTalk = useCallback((questionId: string) => setTalk({ questionId }), []);
  const onTalked = useCallback(() => setTalk(null), []);
  const [flash, setFlash] = useState<{ questionId: string; sentence: string | null; n: number } | null>(null);
  const [activeId, setActive] = useActiveQuestion(paperRef, letter.questions.map((q) => q.id), lock);
  const mobile = useMatches(SHEET_QUERY);
  const reduced = useReducedMotion();
  const goQuestion = useCallback((id: string) => {
    setActive(id);
    document.querySelector(`[data-question="${id}"]`)?.scrollIntoView?.({ block: 'start', behavior: reduced ? 'auto' : 'smooth' });
  }, [setActive, reduced]);
  // 채우기의 'Qn에 넣었어요 → 보기' — 종이의 그 문항으로 가서 다시 쓴 문장을 잠깐 칠한다(굴리기는 종이가 한다)
  const showFilled = useCallback((questionId: string, sentence: string | null) => {
    setActive(questionId);
    setFlash((f) => ({ questionId, sentence, n: (f?.n ?? 0) + 1 }));
  }, [setActive]);

  const copyAll = async () => {
    const all = letter.questions.map((q) => `${q.seq}. ${q.text}\n\n${texts[q.id] ?? ''}`).join('\n\n\n');
    const ok = await copyText(all);
    if (!ok) { setCopied('복사하지 못했어요. 글을 직접 선택해 복사해 주세요.'); return; }
    const blanks = letter.questions.reduce((n, q) => n + blanksIn(texts[q.id] ?? '').length, 0);
    const inferred = letter.questions.reduce((n, q) => n + unconfirmedInferences(q.draft, texts[q.id] ?? ''), 0);
    const extra = copiedExtras(inferred, blanks);
    setCopied(extra ? `모든 문항을 복사했어요. ${extra}` : '모든 문항을 복사했어요');
    track('cover_letter_copied', { coverLetterId: letter.id, scope: 'all', blanks, inferred });
  };
  return (
    <>
      <PageTitle sub={`${letter.jobRole}, 문항 ${letter.questions.length}개. 고친 글은 저장하면 남고, 처음 초안도 언제든 다시 볼 수 있어요.`}
        right={<Button variant="outline" size="sm" onClick={copyAll}><Copy size={14} aria-hidden /> 전체 복사</Button>}>초안이 나왔어요</PageTitle>
      {copied && <p className="t-13 c-2" role="status">{copied}</p>}
      <div className={`cl-draft${mobile ? ' cl-draft--sheet' : ''}`}>
        <div className="cl-draft__paper" ref={paperRef}>
          {mobile && <Notices questions={letter.questions} texts={texts} onGo={goQuestion} onTalk={onTalk} memoUsable={letter.memoUsable} />}
          <DraftPaper letter={letter} mode="edit" texts={texts} onText={onText} activeId={activeId} focus={focus} flash={flash} />
        </div>
        <DraftPanel letter={letter} texts={texts} onText={onText} activeId={activeId} mobile={mobile} onLock={setLock} onGo={goQuestion} onFlag={onFlag}
          talk={talk} onTalk={onTalk} onTalked={onTalked} onShowFilled={showFilled} />
      </div>
    </>
  );
}

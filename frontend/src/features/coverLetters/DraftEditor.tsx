import { useCallback, useState } from 'react';
import { Copy } from 'lucide-react';
import type { CoverLetter } from '@/api/coverLetters';
import { Button, PageTitle } from '@/components/ui';
import { copyText } from '@/lib/clipboard';
import { blanksIn } from '@/lib/draftText';
import { track } from '@/lib/track';
import { DraftPaper } from './paper/DraftPaper';

/**
 * ⑥ 초안(#518 → #618 종이). 같은 종이에서 바로 고치고 저장·복사한다. 글자 수와 빈칸은 서버와 같은 기준으로 화면이 바로 센다(draftText.ts).
 * 전체 복사는 서버 캐시가 아니라 화면에 있는 지금 글로 만든다 — 저장 전·저장 실패한 글도 빠지지 않게.
 */
export function DraftEditor({ letter }: { letter: CoverLetter }) {
  const [copied, setCopied] = useState<string | null>(null);
  const [texts, setTexts] = useState<Record<string, string>>(() => Object.fromEntries(letter.questions.map((q) => [q.id, q.draft?.text ?? ''])));
  const onText = useCallback((id: string, text: string) => setTexts((s) => ({ ...s, [id]: text })), []);

  const copyAll = async () => {
    const all = letter.questions.map((q) => `${q.seq}. ${q.text}\n\n${texts[q.id] ?? ''}`).join('\n\n\n');
    const ok = await copyText(all);
    setCopied(ok ? '모든 문항을 복사했어요' : '복사하지 못했어요. 글을 직접 선택해 복사해 주세요.');
    if (ok) track('cover_letter_copied', { coverLetterId: letter.id, scope: 'all', blanks: letter.questions.reduce((n, q) => n + blanksIn(texts[q.id] ?? '').length, 0) });
  };
  return (
    <>
      <PageTitle sub={`${letter.jobRole}, 문항 ${letter.questions.length}개. 고친 글은 저장하면 남고, AI가 쓴 원본도 언제든 다시 볼 수 있어요.`}
        right={<Button variant="outline" size="sm" onClick={copyAll}><Copy size={14} aria-hidden /> 전체 복사</Button>}>초안이 나왔어요</PageTitle>
      {copied && <p className="t-13 c-2" role="status">{copied}</p>}
      <DraftPaper letter={letter} mode="edit" texts={texts} onText={onText} />
    </>
  );
}

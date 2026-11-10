import { useSearchParams } from 'react-router-dom';
import { useCard } from '@/api/queries';
import type { CoverLetter } from '@/api/coverLetters';
import { Button } from '@/components/ui';
import { objectParticle } from '@/lib/notices';

const PREVIEW = 20;

/** 알림에서 온 길(?card=&question=)에서만 보이고, 눌러야 다시 쓴다 — useCard 는 바로 다시 쓰는 표시라 여기서는 card 만 읽는다 */
export function CardSuggestBanner({ letter }: { letter: CoverLetter }) {
  const [sp, setSp] = useSearchParams();
  const cardId = sp.get('card');
  const questionId = sp.get('question');
  const { card } = useCard(cardId ?? undefined);
  const question = letter.questions.find((q) => q.id === questionId);
  if (!cardId || !question) return null;
  const text = question.text.length > PREVIEW ? `${question.text.slice(0, PREVIEW)}…` : question.text;
  const title = card.data?.title ?? '새 카드';
  return (
    <div className="note cl-suggest">
      <strong>새 카드 '{title}'{objectParticle(title)} {text} 문항에 쓸 수 있어요</strong>
      <span className="cl-suggest__actions">
        <Button size="sm" onClick={() => setSp({ useCard: cardId, question: question.id }, { replace: true })}>이 카드로 다시 쓰기</Button>
        <Button size="sm" variant="text" onClick={() => setSp({}, { replace: true })}>닫기</Button>
      </span>
    </div>
  );
}

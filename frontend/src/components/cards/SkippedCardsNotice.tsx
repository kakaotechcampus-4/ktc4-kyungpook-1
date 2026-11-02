import { Note } from '@/components/ui';
import { useSkippedCount } from '@/lib/skippedItems';

/** 서버가 보낸 카드 중 이 화면이 읽지 못해 뺀 장이 있을 때만 보인다. 나머지 카드는 그대로 보인다 */
export function SkippedCardsNotice() {
  const skipped = useSkippedCount('cards');
  if (skipped === 0) return null;
  return (
    <div role="status">
      <Note strong={`카드 ${skipped}장을 표시하지 못했어요`} tone="caution">서버가 보낸 카드 정보를 이 화면에서 읽을 수 없었어요. 나머지 카드는 그대로 볼 수 있어요.</Note>
    </div>
  );
}

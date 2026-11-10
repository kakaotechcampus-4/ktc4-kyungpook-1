import { Button } from '@/components/ui';
import { startSampleTour } from '@/mock/sampleTour';

type Start = { label: string; pending: boolean; onStart: () => void; tour?: boolean };

/** 첫 화면 위와 맨 아래에서 같은 두 행동을 보여 준다 — 둘러보기는 로그인 없이 샘플 데이터로 앱을 써 보므로 이름에 그걸 적는다 */
export function StartActions({ label, pending, onStart, tour = true }: Start) {
  return (
    <div className="landing__actions">
      <Button size="xl" loading={pending} onClick={onStart}>{label}</Button>
      {tour && <button type="button" className="landing__tour" onClick={startSampleTour}>샘플 데이터로 둘러보기</button>}
    </div>
  );
}

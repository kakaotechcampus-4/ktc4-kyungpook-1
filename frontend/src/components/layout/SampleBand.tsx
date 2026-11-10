import { Button } from '@/components/ui';
import { endSampleTour, sampleTourActive } from '@/mock/sampleTour';

export function SampleBand() {
  if (!sampleTourActive()) return null;
  return (
    <div className="sample-band" role="status">
      <span className="sample-band__text">샘플 데이터로 둘러보는 중이에요. 실제 분석이 아니에요</span>
      <div className="sample-band__actions">
        <Button size="sm" onClick={endSampleTour}>내 GitHub 로 시작</Button>
        <Button size="sm" variant="text" onClick={endSampleTour}>나가기</Button>
      </div>
    </div>
  );
}

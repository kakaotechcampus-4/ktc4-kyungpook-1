import { startSampleTour } from '@/mock/sampleTour';

export function SampleTourLink() {
  return (
    <div className="sample-link">
      <button type="button" className="sample-link__btn" onClick={startSampleTour}>샘플 데이터로 둘러보기</button>
    </div>
  );
}

import { startSampleTour } from '@/mock/sampleTour';

export function SampleTourLink() {
  return (
    <div className="sample-link">
      <button type="button" className="sample-link__btn" onClick={startSampleTour}>둘러보기</button>
      <span className="sample-link__hint">샘플 데이터로 먼저 써 보기</span>
    </div>
  );
}

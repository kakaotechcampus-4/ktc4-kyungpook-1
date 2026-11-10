import { Reveal } from './Shot';
import { StartActions } from './StartActions';

export function Closing(props: { label: string; pending: boolean; onStart: () => void }) {
  return (
    <section className="lp-closing lp-wrap" aria-labelledby="lp-closing-title">
      <Reveal>
        <h2 id="lp-closing-title">지금 레포 하나로 시작해 보세요</h2>
        <p>정리는 몇 분이면 끝나고, 끝나면 카드가 먼저 와요.</p>
        <StartActions {...props} tour={false} />
      </Reveal>
    </section>
  );
}

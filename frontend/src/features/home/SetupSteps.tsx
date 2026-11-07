import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Check } from 'lucide-react';
import type { SetupView } from './homeApply';

type Step = { title: string; text: string; action: ReactNode };

const go = (to: string, label: string, primary = true) => (
  <Link to={to} className={primary ? 'btn btn--primary home3-btn home3-btn--sm' : 'btn btn--outline home3-obtn'}>{label}{primary && <ArrowRight size={18} aria-hidden />}</Link>
);

/** 맞는 공고가 아직 없을 때 지원할 곳 자리에 보이는 준비 단계 — 매칭이 꺼진 배포는 앞의 두 단계만 보인다 */
export function SetupSteps({ view, matching, onRetry, retrying = false }: { view: SetupView; matching: boolean; onRetry?: () => void; retrying?: boolean }) {
  const { current, mode } = view;
  const ready = mode === 'ready';
  const title = ready ? '카드가 준비됐어요'
    : mode === 'busy' ? '맞는 공고를 고르고 있어요'
    : mode === 'zero' ? '아직 맞는 공고가 없어요'
    : mode === 'failed' ? '맞는 공고를 고르지 못했어요'
    : matching ? '세 단계면 준비돼요' : '두 단계면 준비돼요';
  const text = ready ? '확정한 카드로 공고에 맞는 자소서를 쓸 수 있어요.'
    : mode === 'busy' ? '확정한 카드로 공고를 맞춰 보는 중이에요. 다 고르면 이 자리에 모아 드려요.'
    : mode === 'zero' ? '지금 올라온 공고 중에는 내 카드와 잘 맞는 곳이 없어요. 새 공고가 맞으면 바로 알려 드려요.'
    : mode === 'failed' ? '공고를 맞춰 보다가 멈췄어요. 다시 고르면 이어서 맞춰 봐요.'
    : matching ? '레포를 정리하고 카드를 확정하면, 내 카드와 맞는 공고가 이 자리에 모여요.' : '레포를 정리하고 카드를 확정하면 자소서를 쓸 수 있어요.';

  const cardStep = view.draftCount > 0
    ? go(`/cards/${encodeURIComponent(view.firstDraftId ?? '')}`, `확인할 카드 ${view.draftCount}장 보기`)
    : go('/cards', '카드 보기');
  const steps: Step[] = [
    { title: '레포 정리하기', text: '내 커밋과 PR에서 경험을 찾아 카드 초안을 써요. 레포 하나에 1~2분이에요.', action: go('/repos', '레포 고르기') },
    { title: '카드 확정하기', text: '상황과 행동이 찬 카드는 저절로 확정돼요. 확인이 필요한 카드만 봐 주세요.', action: cardStep },
  ];
  if (matching) {
    steps.push({
      title: mode === 'zero' ? '맞는 공고 기다리기' : '맞는 공고 받기',
      text: mode === 'zero' ? '매일 새벽 새 공고를 맞춰 봐요. 카드가 늘면 맞는 곳도 늘어요.' : '확정한 카드와 맞는 공고를 서버가 골라 이 자리에 모아요.',
      action: mode === 'busy' ? <span className="home3-busy" role="status"><span className="home3-spin" aria-hidden />고르는 중</span>
        : mode === 'zero' ? go('/repos', '레포 더 정리하기', false)
        : mode === 'failed' ? <button type="button" className="btn btn--outline home3-obtn" onClick={onRetry} disabled={retrying}>다시 고르기</button>
        : null,
    });
  }

  return (
    <section className="home3-setup" aria-label="지원 준비">
      <h2>{title}</h2>
      <p>{text}</p>
      {ready
        ? <div className="home3-setup__done">{go('/cover-letters/new', '자소서 쓰기')}</div>
        : null}
      <ol className="home3-steps">
        {steps.map((step, index) => {
          const state = ready || index < current ? 'done' : index === current ? 'now' : 'later';
          return (
            <li key={step.title} className={`home3-st is-${state}`} aria-current={state === 'now' ? 'step' : undefined}>
              <span className="home3-no home3-tnum" {...(state === 'done' ? { role: 'img', 'aria-label': '끝났어요' } : {})}>{state === 'done' ? <Check size={18} aria-hidden /> : index + 1}</span>
              <div>
                <b>{step.title}</b>
                <p>{step.text}</p>
                {state === 'now' && step.action && <div className="home3-do">{step.action}</div>}
              </div>
            </li>
          );
        })}
      </ol>
    </section>
  );
}

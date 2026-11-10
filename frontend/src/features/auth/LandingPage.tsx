import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react';
import { Link, Navigate, useSearchParams } from 'react-router-dom';
import { useMe } from '@/api/queries';
import { Button, Note } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { LANDING_HOLD_EXTENSION_MS, LandingBrand, shouldPlayLandingIntro } from './LandingBrand';
import { useLogin } from './useLogin';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { LandingMorph } from './LandingMorph';
import { StartActions } from './landing/StartActions';
import { ProductShot } from './landing/ProductShot';
import { Claims } from './landing/Claims';
import { Principles } from './landing/Principles';
import { Closing } from './landing/Closing';
import { LandingFooter } from './landing/LandingFooter';

const READS = ['내가 올린 커밋', '내가 만든 PR', '내가 남긴 리뷰 코멘트'];
const SKIPS = ['비공개 레포', '다른 사람만 한 일'];

/**
 * A1 랜딩 · A2 동의 안내(모달).
 * 실제 OAuth: [GitHub으로 이동] → {API}/auth/github/start → GitHub authorize → Spring callback(세션 쿠키) → / 로 302.
 * 동의 취소·실패는 Spring 이 /login?error=access_denied 로 돌려보낸다. 세션 만료는 /login?reason=expired.
 */
export function LandingPage() {
  useDocumentTitle('시작하기');
  const [sp, setSp] = useSearchParams();
  const consent = sp.get('consent') === '1';
  const error = sp.get('error');
  const reason = sp.get('reason');
  const login = useLogin();
  const [introRequested, setIntroRequested] = useState(() => shouldPlayLandingIntro(sp));
  const finishIntro = useCallback(() => setIntroRequested(false), []);
  const introActive = introRequested && !consent;
  // 위 바의 시작 버튼은 첫 화면의 시작 버튼이 화면 밖으로 나간 뒤에만 보인다 — 첫 화면에 같은 버튼이 둘 있지 않게
  const heroActions = useRef<HTMLDivElement>(null);
  const [heroActionsAway, setHeroActionsAway] = useState(() => typeof IntersectionObserver === 'undefined');
  useEffect(() => {
    const target = heroActions.current;
    if (!target || typeof IntersectionObserver === 'undefined') return;
    const watcher = new IntersectionObserver(([entry]) => setHeroActionsAway(!entry.isIntersecting));
    watcher.observe(target);
    return () => watcher.disconnect();
  }, []);
  // 이미 로그인한 사람이 /login 을 열면 로그인 화면에 머물지 않고 홈으로 간다. 원래 가려던 곳은 AuthGate 가 이어 준다.
  // (로그아웃은 캐시를 비운 뒤 여기로 오므로 다시 튕기지 않는다)
  const start = () => login.isDemo ? void login.start() : setSp({ consent: '1' });
  const me = useMe();
  if (me.isSuccess) return <Navigate to="/" replace />;

  return (
    <main className="landing" data-intro={introActive ? 'playing' : 'done'} data-top-action={heroActionsAway ? 'shown' : 'hidden'} style={{ '--intro-hold-extension': `${LANDING_HOLD_EXTENSION_MS}ms` } as CSSProperties}>
      <header className="landing__top">
        <LandingBrand active={introActive} onComplete={finishIntro} />
        <Button size="sm" className="landing__top-action" loading={login.pending} inert={introActive} onClick={start}>{login.label}</Button>
      </header>

      <section className="landing__hero" inert={introActive}>
        <div className="landing__stage">
          <h1 className="sr-only">커밋 한 줄이 자소서 한 문장이 되기까지</h1>
          <LandingMorph active={!introActive} />
          <p className="landing__lead">커밋이 자소서 문장이 되기까지, 근거는 따라갑니다.</p>

          <div ref={heroActions}><StartActions label={login.label} pending={login.pending} onStart={start} /></div>
          {error === 'access_denied' && <p className="landing-notice" role="status">GitHub 연결을 취소했어요. 준비되면 다시 시작해 주세요.</p>}
          {error && error !== 'access_denied' && <p className="landing-notice landing-notice--error" role="alert">GitHub 연결에 실패했어요. 다시 시작해 주세요.</p>}
          {reason === 'expired' && !error && !login.isDemo && <p className="landing-notice" role="status">로그인이 만료됐어요. 다시 시작해 주세요.</p>}
          {reason === 'left' && !error && <p className="landing-notice" role="status">탈퇴했어요. 정보를 모두 지웠어요.</p>}
          {login.failure && <p className="landing-notice landing-notice--error" role="alert">{login.failure}</p>}

          {!login.isDemo && <details className="landing__permissions" aria-label="GitHub 접근 범위 자세히 보기">
            <summary>어떤 정보를 읽는지 자세히 보기</summary>
            <PermissionGrid reads={READS} skips={SKIPS} compact />
          </details>}
        </div>
        <ProductShot />
      </section>

      <Claims />
      <Principles />
      <Closing label={login.label} pending={login.pending} onStart={start} />
      <LandingFooter />

      {consent && !login.isDemo && (
        <Modal title="GitHub 연결 권한 확인" width={520}
          onClose={() => setSp({})}
          footer={{ strong: '쓰기 권한은 요청하지 않아요', actions: <><Button variant="secondary" onClick={() => setSp({})}>취소</Button><Button loading={login.pending} onClick={() => void login.start()}>GitHub으로 이동</Button></> }}>
          {login.failure && <Note strong="시작하지 못했어요" tone="danger">{login.failure}</Note>}
          <p className="t-14">프로필 정보와 공개 레포만 읽어요.</p>
          <p className="legal-consent">시작하면 <Link to="/terms">이용약관</Link>과 <Link to="/privacy">개인정보처리방침</Link>에 동의하게 돼요.</p>
        </Modal>
      )}
    </main>
  );
}
export function PermissionGrid({ reads, skips, compact }: { reads: string[]; skips: string[]; compact?: boolean }) {
  return (
    <div className={`perm ${compact ? 'perm--compact' : ''}`}>
      <div className="perm__col">
        <h3>이건 읽어요</h3>
        {reads.map((r) => <div key={r} className="perm__row"><b aria-hidden>•</b><span>{r}</span></div>)}
      </div>
      <div className="perm__col perm__col--skip">
        <h3>이건 안 읽어요</h3>
        {skips.map((r) => <div key={r} className="perm__row"><b>—</b><span>{r}</span></div>)}
      </div>
    </div>
  );
}

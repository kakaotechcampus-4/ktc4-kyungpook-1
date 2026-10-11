import { useSearchParams } from 'react-router-dom';
import { ArrowRight } from 'lucide-react';
import { Button, Note } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { Wordmark } from '@/components/layout/AppShell';
import { useLogin } from './useLogin';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { LandingExample } from './LandingExample';

const READS = ['내가 올린 커밋', '내가 만든 PR', '내가 남긴 리뷰 코멘트'];
const SKIPS = ['비공개 저장소', '다른 사람만 작업한 내용'];

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

  return (
    <main className="landing">
      <header className="landing__top">
        <Wordmark height={34} />
      </header>

      <section className="landing__hero">
        <div className="landing__copy stack">
          <h1 className="landing__h1">커밋과 PR을<br />자소서 경험으로</h1>
          <p className="landing__lead">
            저장소를 고르면 내가 한 작업을 찾아 상황·과제·행동·결과로 정리해요.
            비어 있는 내용은 질문에 답하며 채우고, 자소서와 면접에 활용할 수 있어요.
          </p>

          <div className="landing__actions">
            <Button size="xl" loading={login.pending} onClick={() => login.isDemo ? void login.start() : setSp({ consent: '1' })}>{login.label} <ArrowRight size={16} /></Button>
          </div>
          {error === 'access_denied' && <p className="landing-notice" role="status">GitHub에서 동의를 취소하셨네요</p>}
          {error && error !== 'access_denied' && <p className="landing-notice landing-notice--error" role="alert">GitHub 연결에 실패했어요. 다시 시작해 주세요.</p>}
          {reason === 'expired' && !error && <p className="landing-notice" role="status">로그인이 만료됐어요. 다시 시작해 주세요.</p>}
          {login.failure && <p className="landing-notice landing-notice--error" role="alert">{login.failure}</p>}

          {!login.isDemo && <details className="landing__permissions" aria-label="GitHub 접근 범위 자세히 보기">
            <summary>어떤 정보를 읽는지 자세히 보기</summary>
            <PermissionGrid reads={READS} skips={SKIPS} compact />
          </details>}
        </div>

        <LandingExample />
      </section>

      {consent && !login.isDemo && (
        <Modal title="GitHub 연결 권한 확인" width={520}
          onClose={() => setSp({})}
          footer={{ strong: '쓰기 권한은 요청하지 않아요', actions: <><Button variant="outline" onClick={() => setSp({})}>취소</Button><Button loading={login.pending} onClick={() => void login.start()}>GitHub으로 이동</Button></> }}>
          {login.failure && <Note strong="시작하지 못했어요" tone="danger">{login.failure}</Note>}
          <p className="t-14">프로필 정보와 공개 저장소만 읽어요.</p>
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
        {reads.map((r) => <div key={r} className="perm__row"><b>·</b><span>{r}</span></div>)}
      </div>
      <div className="perm__col perm__col--skip">
        <h3>이건 안 읽어요</h3>
        {skips.map((r) => <div key={r} className="perm__row"><b>—</b><span>{r}</span></div>)}
      </div>
    </div>
  );
}

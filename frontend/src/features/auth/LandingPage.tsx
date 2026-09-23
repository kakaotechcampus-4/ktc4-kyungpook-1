import { useSearchParams } from 'react-router-dom';
import { ArrowRight } from 'lucide-react';
import { Badge, Button, Note, StarKey } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { Wordmark } from '@/components/layout/AppShell';
import { useLogin } from './useLogin';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

const READS = ['내가 올린 커밋', '내가 만든 PR', '내가 남긴 리뷰 코멘트'];
const SKIPS = ['비공개 저장소는 권한부터 요청하지 않아요', '다른 사람만 작업한 내용', '전화번호·학번·실명'];

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
            내가 한 일을 개발 기록에서 찾아 경험 카드로 정리해요.
          </p>

          {error === 'access_denied' && <Note strong="GitHub에서 동의를 취소하셨네요" tone="inset">아무것도 저장되지 않았어요. 준비되면 다시 시작하시면 됩니다.</Note>}
          {error && error !== 'access_denied' && <Note strong="GitHub 연결이 안 됐어요" tone="danger">다시 시도해 보세요. 계속 안 되면 GitHub 쪽 앱 권한을 확인해야 합니다.</Note>}
          {reason === 'expired' && <Note strong="로그인이 만료됐어요" tone="inset">다시 로그인하면 보던 화면으로 돌아갑니다.</Note>}
          {login.failure && <Note strong="시작하지 못했어요" tone="danger">{login.failure}</Note>}

          <div className="landing__actions">
            <Button size="xl" loading={login.pending} onClick={() => login.isDemo ? void login.start() : setSp({ consent: '1' })}>{login.label} <ArrowRight size={16} /></Button>
          </div>

          {!login.isDemo && <details className="landing__permissions" aria-label="GitHub 접근 범위 자세히 보기">
            <summary>어떤 정보를 읽는지 자세히 보기</summary>
            <PermissionGrid reads={READS} skips={SKIPS} compact />
          </details>}
        </div>

        {/* S-5 — 심사위원 머리에 남길 한 장면: T 칸이 비어 있는 카드 */}
        <div className="demo-card" aria-label="예시 카드">
          <div className="row" style={{ gap: 8 }}><span className="w-700" style={{ fontSize: 15 }}>로그인 세션 처리</span><Badge kind="DRAFT">작성 중</Badge><span className="right t-12 c-3">PR #42</span></div>
          {[
            { f: 'S' as const, t: '로그인이 자꾸 풀렸어요', ev: 'a3f21c9' },
            { f: 'T' as const, t: null, ev: null },
            { f: 'A' as const, t: '세션 처리 코드를 고쳤어요', ev: '9c02de1' },
          ].map((r) => (
            <div key={r.f} className={`demo-card__row ${r.t ? '' : 'demo-card__row--gap'}`}>
              <StarKey field={r.f} dropped={!r.t} small />
              <div className="stack grow" style={{ gap: 6 }}>
                {r.t ? <span style={{ fontSize: 13, lineHeight: '20px' }}>{r.t}</span> : <span className="c-3" style={{ fontSize: 12.5 }}>근거가 없어 비워 뒀어요</span>}
                {r.ev && <span className="evidence" style={{ padding: '5px 8px', fontSize: 11 }}><span className="evidence__label">근거</span><span className="evidence__sha">{r.ev}</span><span className="evidence__arrow">↗</span></span>}
              </div>
            </div>
          ))}
        </div>
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

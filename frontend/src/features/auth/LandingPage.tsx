import { useNavigate, useSearchParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowRight } from 'lucide-react';
import { Badge, Button, Note, StarKey } from '@/components/ui';
import { Modal } from '@/components/ui/Modal';
import { Wordmark } from '@/components/layout/AppShell';
import { endpoints } from '@/api/endpoints';
import { demoLogin, isDemo } from '@/mock/browser';
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
  const nav = useNavigate();
  const qc = useQueryClient();
  /** 데모(백엔드 없음)에서는 같은 자리에서 세션만 세우고 홈으로 — 그 뒤 흐름은 동일하다. */
  const go = async () => {
    if (!isDemo) { window.location.assign(endpoints.githubStartUrl()); return; }
    demoLogin();
    await qc.resetQueries();
    nav('/', { replace: true });
  };

  return (
    <main className="landing">
      <header className="landing__top">
        <Wordmark height={26} />
        <Button size="sm" variant="outline" onClick={() => setSp({ consent: '1' })}>GitHub으로 시작</Button>
      </header>

      <section className="landing__hero">
        <div className="stack" style={{ gap: 20 }}>
          <h1 className="landing__h1">커밋은 쌓였는데,<br />자소서 앞에서 막히는 이유</h1>
          <p className="landing__lead">
            GitHub에 남아 있는 커밋과 PR을 대신 읽어서, 자소서에 쓸 경험을 카드로 정리해 드려요.
            근거를 못 찾은 칸은 채우지 않고 그냥 비워 둡니다.
          </p>

          {error === 'access_denied' && <Note strong="GitHub에서 동의를 취소하셨네요" tone="inset">아무것도 저장되지 않았어요. 준비되면 다시 시작하시면 됩니다.</Note>}
          {error && error !== 'access_denied' && <Note strong="GitHub 연결이 안 됐어요" tone="danger">다시 시도해 보세요. 계속 안 되면 GitHub 쪽 앱 권한을 확인해야 합니다.</Note>}
          {reason === 'expired' && <Note strong="로그인이 만료됐어요" tone="inset">다시 로그인하면 보던 화면으로 돌아갑니다.</Note>}

          <div>
            <Button size="xl" onClick={() => setSp({ consent: '1' })}>GitHub으로 시작하기 <ArrowRight size={16} /></Button>
          </div>

          <div className="perm">
            <div className="perm__col">
              <h3>이건 읽어요</h3>
              {READS.map((r) => <div key={r} className="perm__row"><b>·</b><span>{r}</span></div>)}
            </div>
            <div className="perm__col perm__col--skip">
              <h3>이건 안 읽어요</h3>
              {SKIPS.map((r) => <div key={r} className="perm__row"><b>—</b><span>{r}</span></div>)}
            </div>
          </div>
        </div>

        {/* S-5 — 심사위원 머리에 남길 한 장면: T 칸이 비어 있는 카드 */}
        <div className="demo-card" aria-label="예시 카드">
          <div className="row" style={{ gap: 8 }}><span className="w-700" style={{ fontSize: 15 }}>로그인 세션 처리</span><Badge kind="DRAFT">작성 중</Badge><span className="right t-12 c-3">PR #42</span></div>
          {[
            { f: 'S' as const, t: '3인 팀 프로젝트에서 로그인 유지가 되지 않는 문제가 있었다', ev: 'a3f21c9' },
            { f: 'T' as const, t: null, ev: null },
            { f: 'A' as const, t: '인증 모듈의 세션 처리를 담당했다', ev: '9c02de1' },
          ].map((r) => (
            <div key={r.f} className={`demo-card__row ${r.t ? '' : 'demo-card__row--gap'}`}>
              <StarKey field={r.f} dropped={!r.t} small />
              <div className="stack grow" style={{ gap: 6 }}>
                {r.t ? <span style={{ fontSize: 13, lineHeight: '20px' }}>{r.t}</span> : <span className="c-3" style={{ fontSize: 12.5 }}>근거를 찾지 못해 비워 두었습니다</span>}
                {r.ev && <span className="evidence" style={{ padding: '5px 8px', fontSize: 11 }}><span className="evidence__label">근거</span><span className="evidence__sha">{r.ev}</span><span className="evidence__arrow">↗</span></span>}
                {!r.t && <span className="btn btn--outline btn--sm" style={{ alignSelf: 'flex-start' }}>이 부분 다시 물어봐 주세요</span>}
              </div>
            </div>
          ))}
          <p className="demo-card__note">이렇게 비어 있는 칸이 오히려 증거예요</p>
        </div>
      </section>

      {consent && (
        <Modal title="GitHub에서 동의만 하면 바로 시작해요" width={520}
          onClose={() => setSp({})}
          footer={{ strong: '읽기 권한 두 개만 요청해요', actions: <><Button variant="outline" onClick={() => setSp({})}>취소</Button><Button onClick={go}>GitHub으로 이동</Button></> }}>
          <div className="stack" style={{ gap: 8 }}>
            {[['공개 저장소 읽기', '커밋과 PR을 읽어요'], ['프로필 읽기', '아이디와 프로필 사진만 써요']].map(([t, d]) => (
              <div key={t} className="card card--paper row" style={{ gap: 12, padding: '14px 16px' }}>
                <div className="stack grow" style={{ gap: 3 }}><span className="w-600" style={{ fontSize: 14 }}>{t}</span><span className="t-12 c-2">{d}</span></div>
              </div>
            ))}
          </div>
          <Note strong="저장소에 쓰는 권한은 요청하지 않아요" />
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

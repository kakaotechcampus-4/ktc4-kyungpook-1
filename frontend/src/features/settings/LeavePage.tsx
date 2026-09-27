import { Link } from 'react-router-dom';
import { Badge, Breadcrumb, Button, PageTitle } from '@/components/ui';

const PRINCIPLES = [
  ['탈퇴는 즉시, 삭제는 유예', '탈퇴하면 즉시 로그인이 차단됩니다. 데이터 삭제는 유예를 둡니다.', '확정'],
  ['GitHub 토큰은 유예 없이 즉시 파기', '토큰은 되돌릴 여지 없이 바로 폐기합니다.', '확정'],
  ['타인의 데이터는 지우지 않습니다', '팀 레포의 커밋은 다른 사용자의 근거이기도 합니다.', '확정'],
  ['유예 기간', '확정 후 안내해 드릴 예정이에요', '미정'],
  ['마지막 사용자가 탈퇴한 레포의 원본', '언제 지울지 — 미정', '미정'],
] as const;

/** F3 탈퇴·삭제 — 정책 미정(요청사항 Q5). 3원칙은 합의됨, 기간·범위는 미정이라 실행 버튼은 비활성. */
export function LeavePage() {
  return (
    <main className="main main--tight">
      <Breadcrumb items={[{ label: '마이페이지', to: '/settings' }, { label: '탈퇴 · 데이터 삭제' }]} />
      <PageTitle right="준비 중">탈퇴 · 데이터 삭제</PageTitle>
      <div className="row" style={{ gap: 14, padding: '18px 20px', borderRadius: 12, background: 'var(--state-open-bg)' }}>
        <div className="stack" style={{ gap: 5 }}>
          <span className="w-600" style={{ fontSize: 15, color: 'var(--state-open-text)' }}>탈퇴 기능을 준비하고 있어요</span>
          <span className="t-12l c-2">현재는 탈퇴를 진행할 수 없어요. 삭제 범위와 보관 기간이 확정되면 안내해 드릴게요.</span>
        </div>
      </div>
      <div className="card stack" style={{ gap: 12, padding: '18px 20px' }}>
        <span className="w-700" style={{ fontSize: 14.5 }}>합의된 3원칙</span>
        {PRINCIPLES.map(([t, d, st]) => (
          <div key={t} className="card row" style={{ gap: 14, padding: '13px 14px', borderRadius: 8, background: st === '미정' ? 'var(--state-open-bg)' : 'var(--bg-paper)' }}>
            <div className="stack grow" style={{ gap: 3 }}><span className="w-600" style={{ fontSize: 13, color: st === '미정' ? 'var(--state-open-text)' : undefined }}>{t}</span><span className="t-12 c-2">{d}</span></div>
            <Badge kind={st === '미정' ? 'OPEN' : 'NEUTRAL'}>{st}</Badge>
          </div>
        ))}
      </div>
      <div className="card stack" style={{ gap: 10, padding: '18px 20px' }}>
        <span className="w-700" style={{ fontSize: 14.5 }}>탈퇴하면</span>
        {[['즉시', '로그인 차단 · GitHub 토큰 파기'], ['유예 기간 중', '카드·원본 보관 (복구 가능)'], ['유예 후', '내 카드·되묻기 답변 삭제 · 팀 레포 원본은 유지']].map(([k, v]) => (
          <div key={k} className="row" style={{ gap: 14 }}><Badge kind="NEUTRAL">{k}</Badge><span className="t-12l c-2">{v}</span></div>
        ))}
      </div>
      <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
        <span className="t-12l c-2">내보내기를 먼저 하시는 걸 권합니다</span>
        <div className="right row" style={{ gap: 8 }}>
          <Link to="/cards" className="btn btn--outline">내 카드 내보내기</Link>
          <Button disabled title="정책 확정 전에는 실행할 수 없습니다">탈퇴 진행</Button>
        </div>
      </div>
    </main>
  );
}

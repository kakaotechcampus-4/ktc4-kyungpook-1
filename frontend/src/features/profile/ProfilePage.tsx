import '@/styles/profile.css';
import { Link } from 'react-router-dom';
import { profileSupported } from '@/api/capabilities';
import { useProfile } from '@/api/profile';
import { Breadcrumb, PageTitle, Skeleton } from '@/components/ui';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { ProfileForm } from './ProfileForm';

/** 실서버에 아직 없는 기능 — 요청을 보내지 않고 준비 중임만 알린다(MatchUnavailable 과 같은 모양). */
export function ProfileUnavailable() {
  return (
    <main className="main">
      <PageTitle>인적사항 입력을 준비 중이에요</PageTitle>
      <p className="c-2">서버 준비가 끝나면 학력·경력·어학·자격증을 입력해 공고 매칭에 쓸 수 있어요.</p>
      <Link to="/settings" className="btn btn--outline">마이페이지로</Link>
    </main>
  );
}

/** F4 인적사항 — 학력·경력·어학·자격증을 입력하고 마이페이지에서 고친다. 홈의 첫 방문 안내가 여기로 온다. */
export function ProfilePage() {
  useDocumentTitle('인적사항');
  const profile = useProfile();
  if (!profileSupported()) return <ProfileUnavailable />;
  return (
    <main className="main main--footer main--tight profile-page">
      <Breadcrumb items={[{ label: '마이페이지', to: '/settings' }, { label: '인적사항' }]} />
      <PageTitle lead="학력·경력·어학·자격증을 적어 두면 공고 매칭에서 지원 자격을 비교할 때 써요. 모두 선택 사항이고, 나중에 언제든 고칠 수 있어요.">인적사항</PageTitle>
      {profile.isPending && <div className="stack" style={{ gap: 12 }} aria-busy="true">{[0, 1, 2].map((i) => <Skeleton key={i} h={120} />)}</div>}
      {profile.isError && <QueryFailure error={profile.error} retry={() => profile.refetch()} pending={profile.isFetching} />}
      {profile.isSuccess && <ProfileForm initial={profile.data} />}
    </main>
  );
}

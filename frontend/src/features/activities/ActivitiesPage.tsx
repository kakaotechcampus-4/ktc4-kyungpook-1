import { useState } from 'react';
import { Link } from 'react-router-dom';
import { FolderPlus, Layers } from 'lucide-react';
import { useActivities, type Activity } from '@/api/activities';
import { Breadcrumb, Button, EmptyState, PageTitle, Skeleton } from '@/components/ui';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { activityMeta, cardsInActivityLabel } from '@/lib/activityLabels';
import { folderPath } from '@/lib/cardFolders';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { ActivityDeleteDialog } from './ActivityDeleteDialog';
import './Activities.css';

/**
 * 활동 관리 — 레포 밖 경험(동아리, 인턴, 공모전 …)을 묶는 활동을 만들고, 고치고, 지운다(#522).
 * 활동 이름을 누르면 폴더 안(그 활동의 카드)으로 간다. 지울 때는 안의 카드도 함께 지워진다는 확인을 받는다.
 */
export function ActivitiesPage() {
  useDocumentTitle('활동');
  const activities = useActivities();
  const [deleting, setDeleting] = useState<Activity | null>(null);
  const list = activities.data ?? [];
  return (
    <main className="main activities-page">
      <Breadcrumb items={[{ label: '홈', to: '/' }, { label: '경험 카드', to: '/cards' }, { label: '활동' }]} />
      <div className="list-head">
        <PageTitle lead="동아리, 인턴, 공모전처럼 레포 밖에서 한 경험을 묶어 두는 곳이에요. 활동 안에서 경험 카드를 만들 수 있어요.">
          활동 <span className="c-3 t-14">{activities.isSuccess ? list.length : '—'}</span>
        </PageTitle>
        {list.length > 0 && <div className="right"><Link to="/activities/new" className="btn btn--primary"><FolderPlus size={14} aria-hidden />활동 만들기</Link></div>}
      </div>
      {activities.isError && <QueryFailure error={activities.error} retry={() => activities.refetch()} pending={activities.isFetching} />}
      {activities.isPending && <div className="stack" style={{ gap: 12 }} aria-busy="true">{[0, 1].map((i) => <Skeleton key={i} h={76} />)}</div>}
      {activities.isSuccess && list.length === 0 && (
        <EmptyState icon={Layers} title="아직 활동이 없어요" desc="동아리, 인턴, 공모전처럼 레포 밖에서 한 경험을 활동으로 묶어 두면 그 안에서 경험 카드를 만들 수 있어요.">
          <Link to="/activities/new" className="btn btn--primary"><FolderPlus size={14} aria-hidden />활동 만들기</Link>
        </EmptyState>
      )}
      {list.length > 0 && (
        <ul className="activity-list" aria-label="활동 목록">
          {list.map((activity) => (
            <li key={activity.id} className="card activity-row">
              <Link to={folderPath.activity(activity.id)} className="activity-row__main" aria-label={`${activity.name}, ${activityMeta(activity)}, ${cardsInActivityLabel(activity)}`}>
                <span className="activity-row__name">{activity.name}</span>
                <span className="activity-row__meta">{activityMeta(activity)}</span>
                <span className="activity-row__meta">{cardsInActivityLabel(activity)}</span>
              </Link>
              <div className="activity-row__actions">
                <Link to={`/activities/${encodeURIComponent(activity.id)}/edit`} className="btn btn--outline btn--sm" aria-label={`${activity.name} 고치기`}>고치기</Link>
                <Button variant="danger" size="sm" aria-label={`${activity.name} 삭제`} onClick={() => setDeleting(activity)}>삭제</Button>
              </div>
            </li>
          ))}
        </ul>
      )}
      {deleting && <ActivityDeleteDialog activity={deleting} onClose={() => setDeleting(null)} />}
    </main>
  );
}

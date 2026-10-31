import { useMemo, useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { PenLine } from 'lucide-react';
import { useActivities } from '@/api/activities';
import { useCards } from '@/api/queries';
import type { CardSummary } from '@/api/schemas';
import { Breadcrumb, Button, PageTitle, SectionHead, Skeleton } from '@/components/ui';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { ActivityDeleteDialog } from '@/features/activities/ActivityDeleteDialog';
import { CardGridItem } from '@/features/home/HomePage';
import { activityMeta } from '@/lib/activityLabels';
import { buildFolders, folderOwnerLine, folderTitle, type FolderKind } from '@/lib/cardFolders';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import './CardFolders.css';

/**
 * 폴더 안(#522, 10/29 회의). 경험 카드 목록에서 폴더를 누르면 여기로 온다. 위에 소유자, 이름, 카드 수와 이 폴더에서 할 일을 두고,
 * '기술 카드'와 '경험 카드' 두 구역에 카드를 지금 모양 그대로 둔다. 후보 보드 버튼은 폴더 앞판에서 옮겨 온 것이라 레포 폴더에만 있다.
 * 폴더 안에서 카드를 열면 이전/다음 카드는 이 폴더의 순서(기술 카드, 경험 카드)를 따른다.
 */
export const RepoFolderPage = () => <FolderScreen kind="repo" id={useParams().repoId} />;
export const ActivityFolderPage = () => <FolderScreen kind="activity" id={useParams().activityId} />;
export const DirectFolderPage = () => <FolderScreen kind="direct" />;

function FolderScreen({ kind, id }: { kind: FolderKind; id?: string }) {
  const nav = useNavigate();
  const cards = useCards();
  const activities = useActivities(kind === 'activity');
  const [deleting, setDeleting] = useState(false);
  const key = kind === 'direct' ? 'direct' : `${kind}:${id}`;
  const folder = useMemo(() => cards.isSuccess ? buildFolders(cards.data, kind === 'activity' ? activities.data : undefined).find((item) => item.key === key) : undefined,
    [cards.isSuccess, cards.data, activities.data, kind, key]);
  const activity = kind === 'activity' ? activities.data?.find((item) => item.id === id) : undefined;
  useDocumentTitle(folder ? folderTitle(folder) : kind === 'activity' ? '활동' : '폴더');

  const crumbs = (last: string) => [{ label: '홈', to: '/' }, { label: '경험 카드', to: '/cards' }, { label: last }];
  const failed = cards.isError && !cards.data ? cards : kind === 'activity' && activities.isError && !activities.data ? activities : null;
  if (failed) return <main className="main"><Breadcrumb items={crumbs('폴더')} /><QueryFailure error={failed.error} retry={() => failed.refetch()} pending={failed.isFetching} /><Link to="/cards" className="btn btn--outline">경험 카드 목록</Link></main>;
  if (cards.isPending || (kind === 'activity' && activities.isPending)) return <main className="main"><Skeleton h={16} w={240} /><Skeleton h={40} w={320} /><Skeleton h={160} /></main>;

  if (!folder || (kind === 'activity' && !activity)) {
    const message = kind === 'activity' ? '이미 삭제됐거나 볼 수 없는 활동이에요.'
      : kind === 'repo' ? '이 레포에는 아직 카드가 없어요.' : '레포나 활동 없이 쓴 카드가 아직 없어요.';
    return (
      <main className="main">
        <Breadcrumb items={crumbs('폴더')} />
        <div className="folder-missing">
          <p className="c-2">{message}</p>
          <div className="folder-head__actions">
            {kind === 'repo' && <Link to={`/repos/${encodeURIComponent(id ?? '')}/candidates`} className="btn btn--outline">후보 보드</Link>}
            {kind === 'direct' && <Link to="/cards/new" className="btn btn--primary"><PenLine size={14} aria-hidden />직접 작성</Link>}
            <Link to="/cards" className="btn btn--outline">경험 카드 목록</Link>
          </div>
        </div>
      </main>
    );
  }

  const techCards = folder.cards.filter((card) => card.kind === 'TECH');
  const experienceCards = folder.cards.filter((card) => card.kind === 'QUALITATIVE');
  const order = [...techCards, ...experienceCards].map((card) => card.id);
  const newCard = kind === 'repo' ? `/cards/new?repo=${encodeURIComponent(id ?? '')}` : kind === 'activity' ? `/cards/new?activity=${encodeURIComponent(id ?? '')}` : '/cards/new';
  const confirmed = folder.cards.filter((card) => card.status === 'CONFIRMED').length;
  const boardLink = kind === 'repo' ? `/repos/${encodeURIComponent(id ?? '')}/candidates` : null;
  // 위 머리의 버튼과 같은 이름의 버튼을 한 화면에 또 두지 않는다 — 구역이 비었을 때는 무엇을 하면 되는지만 말한다
  const emptyHint = (text: string) => <div className="folder-section__empty"><span>{text}</span></div>;

  return (
    <main className="main folder-page">
      <Breadcrumb items={crumbs(folderTitle(folder))} />
      <div className="folder-head">
        <div className="folder-head__identity">
          <span className="folder-head__owner">{folderOwnerLine(folder)}</span>
          <PageTitle sub={<span className="t-14 c-2">{kind === 'activity' ? `카드 ${folder.cards.length}장, 확정 ${confirmed}장` : `카드 ${folder.cards.length}장`}</span>}>{folderTitle(folder)}</PageTitle>
          {activity && <p className="folder-head__meta">{activityMeta(activity)}</p>}
        </div>
        <div className="folder-head__actions">
          {boardLink && <Link to={boardLink} className="btn btn--outline">후보 보드</Link>}
          {kind === 'activity' && <Link to={newCard} className="btn btn--primary"><PenLine size={14} aria-hidden />경험 카드 만들기</Link>}
          {kind === 'activity' && <Link to={`/activities/${encodeURIComponent(id ?? '')}/edit`} className="btn btn--outline">활동 고치기</Link>}
          {kind === 'activity' && <Button variant="danger" onClick={() => setDeleting(true)}>활동 삭제</Button>}
          {kind !== 'activity' && <Link to={newCard} className={`btn ${kind === 'direct' ? 'btn--primary' : 'btn--ghost'}`}><PenLine size={14} aria-hidden />직접 작성</Link>}
        </div>
      </div>

      {(kind === 'repo' || techCards.length > 0) && (
        <CardSection title="기술 카드" cards={techCards} order={order}
          empty={emptyHint("아직 기술 카드가 없어요. 위의 '후보 보드'에서 후보를 골라 만들 수 있어요.")} />
      )}
      <CardSection title="경험 카드" cards={experienceCards} order={order}
        empty={emptyHint(kind === 'activity'
          ? "아직 카드가 없어요. 위의 '경험 카드 만들기'에서 이 활동에서 한 경험을 쓸 수 있어요."
          : "아직 경험 카드가 없어요. 코드에 남지 않은 경험은 위의 '직접 작성'으로 이 폴더에 둘 수 있어요.")} />

      {deleting && activity && <ActivityDeleteDialog activity={activity} onClose={() => setDeleting(false)} onDeleted={() => nav('/cards', { replace: true })} />}
    </main>
  );
}

function CardSection({ title, cards, order, empty }: { title: string; cards: CardSummary[]; order: string[]; empty: ReactNode }) {
  return (
    <section className="folder-section" aria-label={title}>
      <SectionHead label={title} count={cards.length} />
      {cards.length > 0 ? <div className="experience-list">{cards.map((card) => <CardGridItem key={card.id} c={card} cardOrder={order} />)}</div> : empty}
    </section>
  );
}

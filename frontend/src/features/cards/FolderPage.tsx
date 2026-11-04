import { useId, useMemo, useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Pencil, PenLine } from 'lucide-react';
import { useActivities } from '@/api/activities';
import { useCards, useRepo } from '@/api/queries';
import type { CardSummary } from '@/api/schemas';
import { Breadcrumb, Button, PageTitle, SectionHead, Skeleton } from '@/components/ui';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { ActivityDeleteDialog } from '@/features/activities/ActivityDeleteDialog';
import { RepoMatch } from '@/features/match/RepoMatch';
import { CardGridItem } from '@/features/home/HomePage';
import { activityMeta } from '@/lib/activityLabels';
import { buildFolders, folderOwnerLine, folderTitle, type FolderKind } from '@/lib/cardFolders';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { NewCardDialog } from './NewCardDialog';
import { RepoDescriptionDialog } from './RepoDescriptionDialog';
import './CardFolders.css';

/**
 * 폴더 안(#522, 10/29 회의). 경험 카드 목록에서 폴더를 누르면 여기로 온다. 위에 소유자, 이름, 카드 수를 두고,
 * '기술 카드'와 '경험 카드' 두 구역에 카드를 둔다. 후보 보드 버튼은 폴더 앞판에서 옮겨 온 것이라 레포 폴더에만 있다.
 * 카드를 만드는 길은 두 구역 맨 끝의 점선 타일이다(#773).
 * 경험 카드는 누른 폴더가 곧 자리라 그 자리에서 제목과 기간만 받는다.
 * 폴더 안에서 카드를 열면 이전/다음 카드는 이 폴더의 순서(기술 카드, 경험 카드)를 따른다.
 * 레포 폴더는 이름 옆 연필로 폴더 설명을 고친다(#777). 설명은 레포 상세에서 읽고, 못 읽어도 폴더는 그대로 보인다.
 */
export const RepoFolderPage = () => <FolderScreen kind="repo" id={useParams().repoId} />;
export const ActivityFolderPage = () => <FolderScreen kind="activity" id={useParams().activityId} />;
export const DirectFolderPage = () => <FolderScreen kind="direct" />;

function FolderScreen({ kind, id }: { kind: FolderKind; id?: string }) {
  const nav = useNavigate();
  const cards = useCards();
  const activities = useActivities(kind === 'activity');
  const repo = useRepo(kind === 'repo' ? id : undefined);
  const [deleting, setDeleting] = useState(false);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState(false);
  const key =kind === 'direct' ? 'direct' : `${kind}:${id}`;
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
            {kind === 'repo' && <Link to={`/repos/${encodeURIComponent(id ?? '')}/candidates?add=1`} className="btn btn--outline">기술 카드 만들기</Link>}
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
  const confirmed = folder.cards.filter((card) => card.status === 'CONFIRMED').length;
  const boardLink = kind === 'repo' ? `/repos/${encodeURIComponent(id ?? '')}/candidates` : null;
  const facts = kind === 'repo' ? `기술 카드 ${techCards.length}장 · 경험 카드 ${experienceCards.length}장 · 확정 ${confirmed}장` : `카드 ${folder.cards.length}장 · 확정 ${confirmed}장`;
  const experienceHint = kind === 'repo' ? '코드에 남지 않은 이 레포의 경험을 써요' : kind === 'activity' ? '이 활동에서 한 경험을 써요' : '레포·활동 없이 경험을 써요';

  return (
    <main className="main folder-page">
      <Breadcrumb items={crumbs(folderTitle(folder))} />
      <div className="folder-head">
        <div className="folder-head__identity">
          <span className="folder-head__owner">{folderOwnerLine(folder)}</span>
          <PageTitle sub={<span className="t-14 c-2">{facts}</span>}
            after={kind === 'repo' && repo.data ? <button type="button" className="title-edit" aria-label="폴더 설명 수정" title="폴더 설명 수정" onClick={() => setEditing(true)}><Pencil size={18} aria-hidden /></button> : undefined}>{folderTitle(folder)}</PageTitle>
          {activity &&<p className="folder-head__meta">{activityMeta(activity)}</p>}
          {kind === 'repo' && repo.data?.description && <p className="folder-head__meta">{repo.data.description}</p>}
        </div>
        {kind !== 'direct' && (
          <div className="folder-head__actions">
            {boardLink && <Link to={boardLink} className="btn btn--primary">후보 보드</Link>}
            {kind === 'activity' && <Link to={`/activities/${encodeURIComponent(id ?? '')}/edit`} className="btn btn--outline">활동 고치기</Link>}
            {kind === 'activity' && <Button variant="text" onClick={() => setDeleting(true)}>활동 삭제</Button>}
          </div>
        )}
      </div>

      {kind === 'repo' && id && <RepoMatch repoId={id} confirmedCards={confirmed} />}
      {(kind === 'repo' || techCards.length > 0) && (
        <CardSection title="기술 카드" cards={techCards} order={order}
          create={boardLink && <NewTile title="기술 카드 직접 만들기" hint="찾아 둔 후보에 없는 일을 커밋을 골라 적어요" to={`${boardLink}?add=1`} />} />
      )}
      <CardSection title="경험 카드" cards={experienceCards} order={order}
        create={<NewTile title="경험 카드 만들기" hint={experienceHint} onClick={() => setCreating(true)} />} />

      {creating && <NewCardDialog folderName={folderTitle(folder)} repoId={kind === 'repo' ? id : undefined} activityId={kind === 'activity' ? id : undefined} onClose={() => setCreating(false)} />}
      {deleting && activity && <ActivityDeleteDialog activity={activity} onClose={() => setDeleting(false)} onDeleted={() => nav('/cards', { replace: true })} />}
      {editing && repo.data && <RepoDescriptionDialog repo={repo.data} onClose={() => setEditing(false)} />}
    </main>
  );
}

function CardSection({ title, cards, order, create }: { title: string; cards: CardSummary[]; order: string[]; create: ReactNode }) {
  return (
    <section className="folder-section" aria-label={title}>
      <SectionHead label={title} count={cards.length} />
      <div className="experience-list">{cards.map((card) => <CardGridItem key={card.id} c={card} cardOrder={order} />)}{create}</div>
    </section>
  );
}

/** 구역 맨 끝에 늘 하나 있는 점선 타일이고, 둘째 줄까지 이름이 되면 스크린리더가 길게 읽어서 이름과 설명을 나눈다 */
function NewTile({ title, hint, to, onClick }: { title: string; hint: string; to?: string; onClick?: () => void }) {
  const titleId = useId();
  const hintId = useId();
  const text = <><b id={titleId} className="experience-new__title">{title}</b><span id={hintId}>{hint}</span></>;
  return to
    ? <Link to={to} className="experience-new" aria-labelledby={titleId} aria-describedby={hintId}>{text}</Link>
    : <button type="button" className="experience-new" aria-haspopup="dialog" aria-labelledby={titleId} aria-describedby={hintId} onClick={onClick}>{text}</button>;
}

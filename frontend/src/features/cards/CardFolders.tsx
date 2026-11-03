import { Link } from 'react-router-dom';
import { Flag, FolderGit2, FolderPlus, PenLine, type LucideIcon } from 'lucide-react';
import { techLabels } from '@/lib/cardTags';
import { deckLayers, folderLabel, folderOwnerLine, folderNote, folderTitle, type CardFolder, type FolderKind } from '@/lib/cardFolders';
import './CardFolders.css';

const kindIcon: Record<FolderKind, LucideIcon> = { repo: FolderGit2, activity: Flag, direct: PenLine };

/**
 * 폴더 앞판(카드 묶음). 맨 위 카드 한 장이 보이고, 그 뒤로 카드가 겹쳐 쌓인 모양이다 — 겹친 수는 장 수를 따른다(deckLayers).
 * 폴더 전체가 링크 하나라 키보드로 열고, 이름에는 소유자·이름·장 수가 모두 들어간다(folderLabel).
 * 장식(뒤에 겹친 카드와 종류 아이콘)은 접근성 트리에서 뺀다. 숫자는 서버가 준 카드를 센 값이다.
 */
export function FolderTile({ folder }: { folder: CardFolder }) {
  const Icon = kindIcon[folder.kind];
  const note = folderNote(folder);
  return (
    <Link to={folder.to} className="folder" aria-label={folderLabel(folder)} data-deck={deckLayers(folder.cards.length)} data-empty={folder.cards.length === 0 || undefined}>
      <span className="folder__back" aria-hidden />
      <span className="folder__front">
        <span className="folder__head">
          <span className="folder__owner">{folderOwnerLine(folder)}</span>
          <span className="folder__meta">
            {folder.cards.length > 0 && <span className="folder__count"><span className="folder__count-num">{folder.cards.length}</span>장</span>}
            <Icon className="folder__kind" size={16} aria-hidden />
          </span>
        </span>
        <span className="folder__name">{folderTitle(folder)}</span>
        {note && <span className="folder__note">{note}</span>}
        <span className="folder__foot">
          <span className="folder__tags">{folder.techs.map((tech) => <span key={tech} className="folder__tag">{techLabels[tech] ?? tech}</span>)}</span>
        </span>
      </span>
    </Link>
  );
}

/**
 * 폴더 보기. `folders` 는 lib/cardFolders 가 만든 것이다. 새 카드와 활동을 만드는 길도 여기서 연다 — 활동 고치기·삭제는 활동 폴더 안에 있다.
 * 활동 목록을 못 읽었으면(`activitiesFailed`) 카드에 적힌 활동만 폴더가 되므로 카드가 없는 활동은 빠진다 — 그 사실을 알리고 다시 불러오게 한다.
 */
export function CardFolders({ folders, activitiesFailed, retryActivities }: { folders: CardFolder[]; activitiesFailed?: boolean; retryActivities?: () => void }) {
  return (
    <section className="folders" aria-label="레포와 활동 폴더">
      <div className="folders__bar">
        <span className="t-12 c-2">폴더 {folders.length}개</span>
        <div className="right">
          <Link to="/cards/new" className="btn btn--ghost btn--sm"><PenLine size={14} aria-hidden />경험 카드 만들기</Link>
          <Link to="/activities/new" className="btn btn--ghost btn--sm"><FolderPlus size={14} aria-hidden />활동 만들기</Link>
        </div>
      </div>
      {activitiesFailed && (
        <p className="folders__notice t-12 c-2">
          <span role="status">활동 목록을 불러오지 못했어요. 카드가 없는 활동은 보이지 않을 수 있어요.</span>
          {retryActivities && <button type="button" className="btn btn--text btn--sm" onClick={retryActivities}>다시 불러오기</button>}
        </p>
      )}
      <ul className="folder-grid">
        {folders.map((folder) => <li key={folder.key}><FolderTile folder={folder} /></li>)}
      </ul>
    </section>
  );
}

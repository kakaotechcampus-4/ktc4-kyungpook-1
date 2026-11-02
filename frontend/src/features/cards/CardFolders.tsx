import { Link } from 'react-router-dom';
import { FolderPlus } from 'lucide-react';
import { techLabels } from '@/lib/cardTags';
import { folderLabel, folderOwnerLine, folderTitle, lacksExperience, type CardFolder } from '@/lib/cardFolders';
import './CardFolders.css';

/**
 * 폴더 앞판(닫힌 폴더). 폴더 전체가 링크 하나라 키보드로 열고, 이름에는 소유자·이름·장 수가 모두 들어간다(folderLabel).
 * 장식(뒤판과 탭)은 접근성 트리에서 뺀다. 탭에는 글을 넣지 않는다.
 * 숫자는 서버가 준 카드를 센 값이다. 검색·상태로 거른 보기에서는 '맞는 카드'의 수라고 밝힌다.
 */
export function FolderTile({ folder }: { folder: CardFolder }) {
  const total = folder.matched ?? folder.cards.length;
  return (
    <Link to={folder.to} className="folder" aria-label={folderLabel(folder)}>
      <span className="folder__back" aria-hidden />
      <span className="folder__front">
        <span className="folder__owner">{folderOwnerLine(folder)}</span>
        <span className="folder__name">{folderTitle(folder)}</span>
        {lacksExperience(folder) && <span className="folder__note">경험 카드 없음</span>}
        <span className="folder__foot">
          <span className="folder__tags">{folder.techs.map((tech) => <span key={tech} className="folder__tag">{techLabels[tech] ?? tech}</span>)}</span>
          <span className="folder__count">
            {folder.matched !== undefined && <span>맞는 카드</span>}
            <span><span className="folder__count-num">{total}</span>장</span>
          </span>
        </span>
      </span>
    </Link>
  );
}

/**
 * 폴더 보기. `folders` 는 lib/cardFolders 가 만든 것이다. 활동을 만들고 관리하는 길도 여기서 연다.
 * 활동 목록을 못 읽었으면(`activitiesFailed`) 카드에 적힌 활동만 폴더가 되므로 카드가 없는 활동은 빠진다 — 그 사실을 알리고 다시 불러오게 한다.
 */
export function CardFolders({ folders, activitiesFailed, retryActivities }: { folders: CardFolder[]; activitiesFailed?: boolean; retryActivities?: () => void }) {
  return (
    <section className="folders" aria-label="레포와 활동 폴더">
      <div className="folders__bar">
        <span className="t-12 c-2">폴더 {folders.length}개</span>
        <div className="right">
          <Link to="/activities/new" className="btn btn--ghost btn--sm"><FolderPlus size={14} aria-hidden />활동 만들기</Link>
          <Link to="/activities" className="btn btn--text btn--sm">활동 관리</Link>
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

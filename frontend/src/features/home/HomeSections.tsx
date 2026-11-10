import { useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { BriefcaseBusiness, ChevronRight, FileText, FolderGit2, GitBranch, Search } from 'lucide-react';
import { assistantSupported } from '@/api/capabilities';
import { matchSupported } from '@/api/matching';
import type { RepoUpdate } from '@/api/schemas';
import { repoUpdatePath } from '@/lib/homeModel';

/** 홈 맨 위 — 할 일을 한 줄로 맡기는 자리. 한 줄 명령이 꺼진 배포에서는 입력 대신 자주 하는 일 버튼만 보인다 */
export function HomeHero() {
  const nav = useNavigate();
  const [text, setText] = useState('');
  const input = useRef<HTMLInputElement>(null);
  // 빈 채로 누르면 버튼을 죽여 두는 대신 적을 자리로 보낸다(회색 버튼은 눌러도 되는지 알기 어렵다)
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const goal = text.trim();
    if (goal) nav(`/ask?q=${encodeURIComponent(goal)}`);
    else input.current?.focus();
  };
  if (!assistantSupported()) {
    return (
      <div className="home3-quick">
        {matchSupported() && <Link to="/match" className="home3-pill"><BriefcaseBusiness size={18} aria-hidden />내 조건에 맞는 공고 찾기</Link>}
        <Link to="/repos" className="home3-pill"><FolderGit2 size={18} aria-hidden />레포 정리하기</Link>
        <Link to="/cover-letters/new" className="home3-pill"><FileText size={18} aria-hidden />자소서 쓰기</Link>
      </div>
    );
  }
  return (
    <form className="home3-ask" role="search" onSubmit={submit}>
      <Search size={20} aria-hidden className="home3-ask__lens" />
      <input ref={input} value={text} onChange={(e) => setText(e.target.value)} maxLength={500} aria-label="할 일" placeholder="예: 토스 백엔드 공고 찾아서 나랑 맞는지 봐 줘" />
      <button type="submit" className="home3-ask__go">맡기기</button>
    </form>
  );
}

/**
 * 서버가 스스로 다시 읽어 카드 초안을 써 둔 레포를 레포마다 한 줄로 보인다
 * 폴더로 갈 때는 누르는 순간 본 것으로 표시하고, 기업 고르기 화면은 들어오면서 표시한다
 */
export function RepoUpdateNotice({ updates, onSeen }: { updates: RepoUpdate[]; onSeen: (repoId: string) => void }) {
  if (!updates.length) return null;
  const matching = matchSupported();
  return (
    <section aria-label="새로 써 둔 카드">
      <ul className="home3-updates">
        {updates.map((update) => (
          <li key={update.repoId} className="home3-update">
            <GitBranch size={20} aria-hidden className="home3-update__icon" />
            <p><b>{update.repoName}</b>에서 새로 한 일로 카드 {update.cardCount}장을 써 뒀어요</p>
            <Link to={repoUpdatePath(update, matching)} onClick={matching ? undefined : () => onSeen(update.repoId)}>카드 확인하기<ChevronRight size={18} aria-hidden /></Link>
          </li>
        ))}
      </ul>
    </section>
  );
}

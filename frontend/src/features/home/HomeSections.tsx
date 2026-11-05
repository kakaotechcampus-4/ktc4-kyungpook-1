import { useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ArrowRight, BriefcaseBusiness, Check, ChevronRight, FileText, FolderGit2, GitCommitHorizontal, Github, Layers, Search } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useJob } from '@/api/queries';
import { assistantSupported } from '@/api/capabilities';
import { matchSupported } from '@/api/matching';
import { IconBox, KindIcon, Spinner, StarDots } from '@/components/ui';
import { agoLabel, daysLeftLabel, metLabel, type ContinueItem, type DeadlineItem, type RecentItem } from '@/lib/homeModel';
import { jobResultState } from '@/lib/jobLinks';
import { filledOf, needsReviewOf } from './HomePage';

/** 홈 맨 위 — 할 일을 한 줄로 적는 자리. 입력은 서버가 준비된 환경에서만 보이고, 없으면 자주 하는 일 버튼만 남는다 */
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
  return (
    <section className="home2-hero" aria-labelledby="home2-title">
      <div className="home2-hero__copy">
        <h1 id="home2-title">오늘은 무엇을 할까요?</h1>
        <p>공고를 찾고, 내 경험과 맞추고, 자소서 초안까지 이어서 해 드려요.</p>
        {assistantSupported() && (
          <form className="home2-ask" role="search" onSubmit={submit}>
            <label className="home2-ask__field">
              <Search size={18} aria-hidden />
              <input ref={input} value={text} onChange={(e) => setText(e.target.value)} maxLength={500} aria-label="할 일" placeholder="예: 토스 백엔드 공고에 맞춰 자소서 써 줘" />
            </label>
            <button type="submit" className="btn btn--primary home2-ask__go">시작 <ArrowRight size={16} aria-hidden /></button>
          </form>
        )}
        <div className="home2-quick">
          {matchSupported() && <Link to="/match" className="home2-pill"><BriefcaseBusiness size={17} aria-hidden />내 조건에 맞는 공고 찾기</Link>}
          <Link to="/repos" className="home2-pill"><FolderGit2 size={17} aria-hidden />레포 정리하기</Link>
          <Link to="/cover-letters/new" className="home2-pill"><FileText size={17} aria-hidden />자소서 쓰기</Link>
        </div>
      </div>
      <div className="home2-hero__art" aria-hidden="true">
        <div className="home2-float home2-float--1"><IconBox icon={GitCommitHorizontal} size={34} /><span>커밋과 PR<small>쌓아 온 작업 기록</small></span></div>
        <div className="home2-float home2-float--2"><IconBox icon={Layers} size={34} /><span>경험 카드<small>근거가 붙은 내 경험</small></span></div>
        <div className="home2-float home2-float--3"><IconBox icon={FileText} size={34} /><span>자소서<small>공고에 맞춘 초안</small></span><Check size={18} /></div>
        <Github className="home2-hero__github" size={50} strokeWidth={1.4} />
      </div>
    </section>
  );
}

export type StageTile = {
  key: 'repo' | 'card' | 'match' | 'letter';
  name: string; count: number | undefined; unit: string; to: string; action: string;
  /** 눈에 먼저 띄어야 하는 한 줄(파랑 = 지금 도는 일, 호박 = 사람이 봐야 하는 일) */
  flag?: { tone: 'now' | 'need'; text: string };
  note?: string;
  /** 공고 칸에만 쓴다 — 맞는 기업의 첫 글자 */
  initials?: string[];
};
const STAGE_ICON: Record<StageTile['key'], LucideIcon> = { repo: FolderGit2, card: Layers, match: BriefcaseBusiness, letter: FileText };

/** 네 단계의 지금 상태 — 왼쪽에서 오른쪽으로 쓰는 순서대로 놓고, 칸마다 다음에 누를 곳을 하나만 둔다 */
export function StageTiles({ tiles }: { tiles: StageTile[] }) {
  return (
    <section className="home2-sec" aria-labelledby="home2-state">
      <h2 id="home2-state" className="home2-h">지금 상태</h2>
      <ol className="home2-flow">
        {tiles.map((tile) => {
          const Icon = STAGE_ICON[tile.key];
          return (
            <li key={tile.key}>
              <Link to={tile.to} className={`home2-tile ${tile.flag?.tone === 'now' ? 'home2-tile--now' : ''}`}>
                <span className="home2-tile__top">
                  <span className="home2-tile__name">{tile.name}</span>
                  {tile.initials?.length
                    ? <span className="home2-avs">{tile.initials.map((initial, index) => <span key={index} className="home2-av">{initial}</span>)}</span>
                    : <IconBox icon={Icon} size={40} />}
                </span>
                <strong className="home2-tile__num">{tile.count ?? '—'}<small>{tile.unit}</small></strong>
                <span className="home2-tile__state">
                  {tile.flag && <span className={`home2-chip home2-chip--${tile.flag.tone}`}>{tile.flag.tone === 'now' && <i aria-hidden />}{tile.flag.text}</span>}
                  {tile.note && <span>{tile.note}</span>}
                </span>
                <span className="home2-tile__go">{tile.action}<ArrowRight size={18} aria-hidden /></span>
              </Link>
            </li>
          );
        })}
      </ol>
    </section>
  );
}

/** 레포 정리와 카드 초안은 걸음 수로 어디까지 왔는지 보인다(막대가 차오르는 진행률이 아니라 끝난 걸음의 개수) */
function JobSteps({ jobId }: { jobId: string }) {
  const job = useJob(jobId);
  const steps = job.data?.steps ?? [];
  if (!steps.length) return <Spinner />;
  const done = steps.filter((step) => step.state === 'DONE' || step.state === 'SKIPPED').length;
  return (
    <span className="home2-steps" role="img" aria-label={`${steps.length}걸음 중 ${done}걸음을 마쳤어요`}>
      {steps.map((step, index) => <i key={step.key} className={index < done ? 'is-done' : index === done ? 'is-now' : ''} />)}
    </span>
  );
}

function ContinueRow({ item }: { item: ContinueItem }) {
  // 카드 초안 작업은 카드를 만든 뒤에는 그 첫 카드의 초안 화면으로 보낸다 — 아직이면 그 레포의 카드 폴더다
  const draft = useJob(item.kind === 'job' && item.job.type === 'DRAFT' ? item.job.jobId : undefined);
  const firstCard = draft.data?.result?.cardIds?.[0];
  const icon: ReactNode = item.kind === 'card' ? <KindIcon kind={item.card.kind} size={40} />
    : <IconBox icon={item.kind === 'job' ? GitCommitHorizontal : FileText} size={40} />;
  const mark: ReactNode = item.kind === 'card' ? <StarDots filled={filledOf(item.card)} low={needsReviewOf(item.card)} />
    : item.kind === 'job' ? <JobSteps jobId={item.job.jobId} />
    : item.running ? <span className="home2-chip home2-chip--now"><i aria-hidden />쓰는 중</span>
    : <span className="home2-chip home2-chip--need">이어서 쓰기</span>;
  return (
    <li>
      <Link to={firstCard ? `/cards/${encodeURIComponent(firstCard)}` : item.to} state={draft.data && firstCard ? jobResultState(draft.data) : undefined} className="home2-todo">
        {icon}
        <span className="home2-todo__text"><strong>{item.title}</strong><span>{item.sub}</span></span>
        {mark}
        <ArrowRight size={18} className="home2-todo__go" aria-hidden />
      </Link>
    </li>
  );
}

/** 사람이 이어서 봐야 끝나는 일 — 비어 있으면 처음 온 사람에게는 시작할 곳을, 다 끝낸 사람에게는 끝났다는 말을 보인다 */
export function ContinueList({ items, firstTime }: { items: ContinueItem[]; firstTime: boolean }) {
  return (
    <section className="home2-panel" aria-labelledby="home2-todo">
      <h2 id="home2-todo" className="home2-h">이어서 하기 {items.length > 0 && <small>{items.length}</small>}</h2>
      {items.length ? <ul className="home2-todos">{items.map((item) => <ContinueRow key={`${item.kind}:${item.id}`} item={item} />)}</ul>
        : firstTime ? (
          <div className="home2-start">
            <IconBox icon={FolderGit2} size={40} />
            <span className="home2-todo__text"><strong>레포를 하나 골라 보세요</strong><span>커밋과 PR에서 경험을 찾아 카드로 만들어 드려요</span></span>
            <Link to="/repos" className="btn btn--primary">레포 고르기 <ArrowRight size={16} aria-hidden /></Link>
          </div>
        ) : <p className="home2-empty">이어서 할 일이 없어요. 새 레포를 정리하거나 맞는 공고를 살펴보세요.</p>}
    </section>
  );
}

function RecentThumb({ item }: { item: RecentItem }) {
  if (item.kind === 'card') {
    return (
      <span className="home2-thumb home2-thumb--card">
        <span className="home2-kind"><Layers size={14} aria-hidden />카드</span>
        <span className="home2-fan">{item.count > 2 && <i />}{item.count > 1 && <i />}<i><b>{item.sample}</b><em /><em /></i></span>
      </span>
    );
  }
  if (item.kind === 'match') {
    return (
      <span className="home2-thumb home2-thumb--match">
        <span className="home2-kind"><BriefcaseBusiness size={14} aria-hidden />공고</span>
        <span className="home2-fit"><span className="home2-av">{item.company.slice(0, 1)}</span><span><b>{metLabel(item)}</b><Meter met={item.met} total={item.total} /></span></span>
      </span>
    );
  }
  return (
    <span className="home2-thumb home2-thumb--letter">
      <span className="home2-kind"><FileText size={14} aria-hidden />자소서</span>
      <span className="home2-sheet">{item.sample ? <b>{item.sample}</b> : <em />}<em /><em /><em /><em /></span>
    </span>
  );
}

/** 필수 조건을 몇 개 채웠는지 칸으로 보인다. 조건이 많아도 여섯 칸까지만 그린다(비율은 그대로) */
export function Meter({ met, total }: { met: number; total: number }) {
  const cells = Math.min(total, 6);
  const on = total > 6 ? Math.round((met / total) * 6) : met;
  return <span className="home2-meter" aria-hidden>{Array.from({ length: cells }, (_, index) => <i key={index} className={index < on ? 'is-on' : ''} />)}</span>;
}

/** 최근에 한 일 — 종류마다 그림이 달라 글을 읽기 전에 무엇인지 보인다 */
export function RecentCards({ items, now }: { items: RecentItem[]; now?: Date }) {
  if (!items.length) return null;
  return (
    <section className="home2-sec" aria-labelledby="home2-recent">
      <h2 id="home2-recent" className="home2-h">최근에 한 일</h2>
      <ul className="home2-recent">
        {items.map((item) => (
          <li key={`${item.kind}:${item.to}`}>
            <Link to={item.to} className="home2-did">
              <RecentThumb item={item} />
              <span className="home2-did__text"><strong>{item.title}</strong><span>{[item.meta, agoLabel(item.at, now)].filter(Boolean).join(' · ')}</span></span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** 다가오는 마감 — 달력 아래에서 공고마다 맞은 조건과 자소서를 썼는지를 같이 말한다 */
export function DeadlineList({ items }: { items: DeadlineItem[] }) {
  if (!items.length) return null;
  const next = items.find((item) => !item.letter?.done);
  return (
    <section className="home2-due" aria-labelledby="home2-due">
      <h3 id="home2-due">다가오는 마감</h3>
      <ul>
        {items.map((item) => (
          <li key={item.postingId}>
            <Link to={`/match/${encodeURIComponent(item.postingId)}`} className="home2-due__row">
              <span className="home2-av home2-av--lg">{item.company.slice(0, 1)}</span>
              <span className="home2-due__text">
                <strong>{item.company} · {item.title}</strong>
                <span className="home2-due__chips">
                  <span className={`home2-chip ${item.daysLeft <= 3 ? 'home2-chip--need' : ''}`}>{daysLeftLabel(item.daysLeft)}</span>
                  <span className="home2-chip">{metLabel(item)}</span>
                  <span className={`home2-chip ${item.letter && !item.letter.done ? 'home2-chip--now' : ''}`}>{item.letter ? (item.letter.done ? '자소서 초안 있음' : '자소서 쓰는 중') : '자소서 없음'}</span>
                </span>
              </span>
              <ChevronRight size={18} className="home2-todo__go" aria-hidden />
            </Link>
          </li>
        ))}
      </ul>
      {next && (
        <Link className="btn btn--primary home2-due__go"
          to={next.letter ? `/cover-letters/${encodeURIComponent(next.letter.id)}` : `/cover-letters/new?postingId=${encodeURIComponent(next.postingId)}`}>
          {next.company} 자소서 {next.letter ? '이어 쓰기' : '쓰기'} <ArrowRight size={16} aria-hidden />
        </Link>
      )}
    </section>
  );
}

import { useCallback, useEffect, useId, useMemo, useRef, useState, type ReactNode } from 'react';
import { Link, Navigate, useParams, useSearchParams } from 'react-router-dom';
import { ArrowRight, ChevronRight } from 'lucide-react';
import { matchSupported, useRefreshRepoRecommendation, useRepoRecommendation, type RepoRecommendation } from '@/api/matching';
import { useMarkRepoUpdateSeen } from '@/api/repoUpdates';
import { useCards, useMe } from '@/api/queries';
import { Breadcrumb, ButtonLink, PageTitle, Skeleton, buttonClass } from '@/components/ui';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { UserAvatar } from '@/components/ui/UserAvatar';
import { buildFolders, folderPath, folderTitle } from '@/lib/cardFolders';
import {
  againOf, fitLabel, isReplayed, liveRows, logLines, markReplayed, orbitTiles, phaseOf, pickHead, pickReason, replayHead, resultRows, techName,
  type Again, type LogLine, type PickPhase, type PickRow,
} from '@/lib/repoPick';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import { CompanyMark } from './CompanyMark';
import { RepoPickOrbit } from './RepoPickOrbit';
import { deadlineOf } from './model';
import { useRepoPickReplay } from './useRepoPickReplay';
import './repoPick.css';

const cx = (...names: (string | false | null | undefined)[]) => names.filter(Boolean).join(' ');

/**
 * 레포의 카드로 맞는 기업을 고르는 모습. 서버가 기업을 하나씩 확인하는 과정(live)을 2초마다 읽어 원 그림 · 지금까지 고른 곳 · 로그로 보이고, 끝나면 고른 기업과 이유를 보인다.
 * 매칭이 꺼져 있거나 그 레포에 카드가 없으면 폴더로 돌려보낸다.
 * 알림에서 들어오면(again=1) 들어오면서 한 번 '본 것으로 표시' 와 '다시 고르기' 를 부르고, 둘 다 실패해도 지금 결과를 그대로 보인다.
 */
export function RepoPickPage() {
  const { repoId = '' } = useParams();
  const supported = matchSupported();
  const cards = useCards();
  const folder = useMemo(() => (cards.data ? buildFolders(cards.data).find((item) => item.key === `repo:${repoId}`) : undefined), [cards.data, repoId]);
  const [params] = useSearchParams();
  const again = useMemo(() => againOf(params), [params]);
  const { mutate: markSeen } = useMarkRepoUpdateSeen();
  const { mutate: refresh, isIdle: refreshIdle, isPending: refreshing } = useRefreshRepoRecommendation(repoId);
  // 다시 고르기 요청이 끝나기 전에 읽으면 지난 결과가 '다시 골랐어요' 로 잠깐 보인다
  const waiting = again !== null && (refreshIdle || refreshing);
  const rec = useRepoRecommendation(supported && folder && !waiting ? repoId : undefined);
  const entered = useRef(false);
  useEffect(() => {
    if (again === null || !supported || !folder || entered.current) return;
    entered.current = true;
    markSeen(repoId);
    refresh();
  }, [again, supported, folder, repoId, markSeen, refresh]);
  const me = useMe();
  const who: Who = { login: me.data?.login ?? '', avatarUrl: me.data?.avatarUrl };
  const name = folder ? folderTitle(folder) : '';
  useDocumentTitle(name ? `${name} · 맞는 기업` : '맞는 기업');

  const back = folderPath.repo(repoId);
  const crumbs = (last: string) => [{ label: '홈', to: '/' }, { label: '경험 카드', to: '/cards' }, { label: name || '폴더', to: back }, { label: last }];
  const loading = <main className="main rp-page"><Skeleton h={16} w={240} /><Skeleton h={40} w={320} /><Skeleton h={440} /></main>;

  if (!supported) return <Navigate to={back} replace />;
  if (cards.isError && !cards.data) {
    return <main className="main"><Breadcrumb items={crumbs('맞는 기업')} /><QueryFailure error={cards.error} retry={() => cards.refetch()} pending={cards.isFetching} /><ButtonLink to="/cards" variant="secondary">경험 카드 목록</ButtonLink></main>;
  }
  // 막 만든 카드가 아직 목록에 없을 수 있어, 다시 읽는 중에는 카드가 없다고 보지 않는다
  if (cards.isPending || (!folder && cards.isFetching)) return loading;
  if (!folder) return <Navigate to={back} replace />;
  if (waiting) return loading;
  if (rec.isError && !rec.data) {
    return <main className="main"><Breadcrumb items={crumbs('맞는 기업')} /><QueryFailure error={rec.error} retry={() => rec.refetch()} pending={rec.isFetching} /><ButtonLink to={back} variant="secondary">폴더로 가기</ButtonLink></main>;
  }
  if (!rec.data) return loading;
  return <PickScreen repoId={repoId} name={name} back={back} cardCount={folder.cards.length} crumbs={crumbs('맞는 기업')} rec={rec.data} who={who} again={again} />;
}

type Crumb = { label: string; to?: string };
/** 원 그림 가운데와 로그의 '내 쪽' 줄에 보일 사용자. 사진이 없으면 로그인 이름 첫 글자다 */
type Who = { login: string; avatarUrl?: string | null };

function PickScreen({ repoId, name, back, cardCount, crumbs, rec, who, again }: { repoId: string; name: string; back: string; cardCount: number; crumbs: Crumb[]; rec: RepoRecommendation; who: Who; again: Again | null }) {
  const realPhase = phaseOf(rec);
  // 예약이거나 막 시작한 순간에는 남아 있는 지난 진행을 쓰지 않는다
  const realLive = realPhase === 'queued' ? null : rec.live;
  const hasProcess = realPhase === 'done' && realLive !== null && realLive.finished && realLive.candidates.length > 0;
  // 이 화면에서 진행을 직접 본 결과는 다시 보여 주지 않는다
  const watched = useRef(false);
  useEffect(() => {
    if (realPhase === 'queued' || realPhase === 'running') watched.current = true;
    else if (realPhase === 'done' && watched.current) markReplayed(repoId, rec.computedAt);
  }, [realPhase, repoId, rec.computedAt]);
  const reduceMotion = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const auto = hasProcess && !watched.current && !reduceMotion && !isReplayed(repoId, rec.computedAt);
  const onReplayEnd = useCallback(() => markReplayed(repoId, rec.computedAt), [repoId, rec.computedAt]);
  const replay = useRepoPickReplay(hasProcess ? realLive : null, auto, reduceMotion, onReplayEnd);
  const replaying = replay.frame !== null;

  const phase: PickPhase = replaying ? 'running' : realPhase;
  const live = replay.frame ?? realLive;
  const view = replaying ? { ...rec, live } : rec;
  const count = live?.cardCount ?? cardCount;
  const rows = phase === 'running' ? (live ? liveRows(live) : []) : phase === 'done' || phase === 'failed' ? resultRows(rec.companies, live) : [];
  const head = replaying ? replayHead(name, count) : pickHead(phase, { repoName: name, cardCount: count, picked: rec.companies.length, again });
  const candidates = live?.candidates ?? [];
  const checked = candidates.filter((candidate) => candidate.state === 'DONE').length;
  const started = candidates.some((candidate) => candidate.state !== 'WAITING');
  const lines = live ? logLines(live) : [];

  const folderButton = (label: string, primary: boolean) => (
    <ButtonLink to={back} variant={primary ? 'primary' : 'secondary'}>{label}{primary && <ArrowRight size={16} aria-hidden />}</ButtonLink>
  );
  const allPostings = <ButtonLink to="/match" variant="secondary">전체 공고 보기</ButtonLink>;
  const actions: Record<PickPhase, ReactNode> = {
    queued: folderButton('카드 먼저 보기', false),
    running: folderButton('카드 먼저 보기', false),
    done: <>{allPostings}{folderButton('폴더로 가기', true)}</>,
    none: <>{allPostings}{folderButton('카드 보러 가기', true)}</>,
    failed: folderButton('폴더로 가기', true),
    idle: folderButton('폴더로 가기', true),
  };

  const replayActions = <><button type="button" className={buttonClass('secondary')} onClick={replay.skip}>결과 바로 보기</button>{folderButton('폴더로 가기', true)}</>;

  return (
    <main className="main rp-page">
      <Breadcrumb items={crumbs} />
      <div className="rp-head">
        <PageTitle lead={head.lead}>{head.title}</PageTitle>
        <div className="rp-head__act">{replaying ? replayActions : actions[phase]}</div>
      </div>

      <div className="rp-grid">
        <section className="card rp-orbit" aria-label="후보 회사">
          <RepoPickOrbit repoName={name} cardCount={count} tiles={orbitTiles(view, phase)} spinning={phase === 'running'} login={who.login} avatarUrl={who.avatarUrl} />
          <OrbitFoot phase={phase} live={live !== null} total={candidates.length} checked={checked} finished={live?.finished ?? false}
            dots={candidates.map((candidate) => (candidate.state === 'DONE' ? 'done' : candidate.state === 'CHECKING' ? 'now' : 'wait'))} />
          <p className="rp-legend"><span className="rp-legend__rings" aria-hidden="true"><i /><i /><i /></span>안쪽 고리로 들어올수록 내 카드와 맞는 조건이 많은 회사예요</p>
        </section>

        <div className="rp-side">
          <PicksPanel phase={phase} rows={rows} started={started} replay={replaying} onReplay={hasProcess && !replaying ? replay.start : undefined} />
          {phase === 'queued' && <LogPanel title="지금 하는 일"><p className="rp-wait">곧 시작해요</p></LogPanel>}
          {live && <LogPanel title={phase === 'running' && !replaying ? '지금 하는 일' : '한 일'}><LogFeed lines={lines} who={who} /></LogPanel>}
        </div>
      </div>
    </main>
  );
}

function OrbitFoot({ phase, live, total, checked, finished, dots }: { phase: PickPhase; live: boolean; total: number; checked: number; finished: boolean; dots: ('done' | 'now' | 'wait')[] }) {
  if (!live || phase === 'idle') return null;
  const text: ReactNode = total === 0 ? (phase === 'running' ? '후보 회사를 찾고 있어요' : '후보 회사를 찾지 못했어요')
    : finished ? <><b>{total}곳</b>을 모두 확인했어요</>
    : <>{total}곳 중 <b>{checked}곳</b> 확인했어요</>;
  return (
    <div className="rp-orbit__foot">
      <div className="rp-dots" aria-hidden="true">{dots.map((dot, i) => <i key={i} className={dot === 'done' ? 'is-done' : dot === 'now' ? 'is-now' : undefined} />)}</div>
      <p className="rp-orbit__count">{text}</p>
    </div>
  );
}

const dueLabel = (row: PickRow) => {
  if (row.closed) return '마감된 공고';
  const due = deadlineOf(row.deadline);
  if (due.kind === 'none') return '마감일 없음';
  return due.kind === 'closed' || due.kind === 'today' ? due.badge : `${due.badge.slice(2)}일 남음`;
};

function PicksPanel({ phase, rows, started, replay, onReplay }: { phase: PickPhase; rows: PickRow[]; started: boolean; replay: boolean; onReplay?: () => void }) {
  const titleId = useId();
  const running = phase === 'running';
  const slots = running || phase === 'queued';
  return (
    <section className="card rp-panel" aria-labelledby={titleId}>
      <h2 id={titleId}>
        {slots ? '지금까지 고른 곳' : '고른 기업'}
        {!slots && rows.length > 0 && <small>{rows.length}곳</small>}
        {running && !replay && <span className="rp-live"><i aria-hidden="true" />고르는 중</span>}
      </h2>
      {slots ? (
        <ol className="rp-picks">
          {[0, 1, 2].map((n) => rows[n]
            ? <PickItem key={rows[n].key} row={rows[n]} no={n + 1} final={false} />
            : <li key={`empty${n}`} className="rp-pick rp-pick--empty"><span className="rp-pick__no">{n + 1}</span>{running && started ? '아직 비어 있어요' : '곧 채워져요'}</li>)}
        </ol>
      ) : rows.length > 0 ? (
        <>
          <ol className="rp-picks">{rows.map((row, i) => <PickItem key={row.key} row={row} no={i + 1} final />)}</ol>
          {onReplay && <button type="button" className={buttonClass('secondary', undefined, 'rp-again')} onClick={onReplay}>고른 과정 다시 보기</button>}
          <p className="rp-note">{phase === 'done' ? '확정하지 않은 카드도 함께 봤어요. 카드를 확정하면 공고에서도 같은 조건이 맞는 걸로 보여요.' : '지난번에 고른 기업이에요.'}</p>
        </>
      ) : (
        <div className="rp-none">
          {phase === 'none' ? <>
            <strong>이 레포의 카드와 맞는 공고가 아직 없어요</strong>
            <span>카드의 빈칸을 채우거나 다른 레포를 정리하면 다시 골라 드려요. 새 공고가 올라와도 다시 맞춰 봐요.</span>
          </> : <strong>카드를 고치거나 확정하면 골라 드려요</strong>}
        </div>
      )}
    </section>
  );
}

const pips = (count: number, preferred: boolean) => Array.from({ length: Math.min(count, 5) }, (_, i) => <i key={`${preferred}${i}`} className={preferred ? 'is-pref' : undefined} />);

function PickItem({ row, no, final }: { row: PickRow; no: number; final: boolean }) {
  return (
    <li className={cx('rp-pick', final && 'is-final')}>
      <span className="rp-pick__no">{no}</span>
      <CompanyMark name={row.name} logoUrl={row.logoUrl} size={48} className="rp-pick__logo" />
      <span className="rp-pick__text">
        <span className="rp-pick__name"><strong>{row.name}</strong>{final && row.fresh && <span className="rp-new">새로 들어옴</span>}</span>
        <span>{row.role} · {dueLabel(row)}</span>
        {final && <span className="rp-pick__why">{pickReason(row)}</span>}
      </span>
      {final
        ? <Link className="rp-pick__go" to={`/match/${encodeURIComponent(row.postingId)}`} aria-label={`${row.name} 공고 보기`}>공고 보기<ChevronRight size={16} aria-hidden /></Link>
        : <span className="rp-pick__fit"><span className="rp-meter" aria-hidden="true">{pips(row.required, false)}{pips(row.preferred, true)}</span><span>{fitLabel(row.required, row.preferred)}</span></span>}
    </li>
  );
}

function LogPanel({ title, children }: { title: string; children: ReactNode }) {
  const titleId = useId();
  return (
    <section className="card rp-panel" aria-labelledby={titleId}>
      <h2 id={titleId}>{title}</h2>
      {children}
    </section>
  );
}

/** 새 줄이 오면 로그 칸만 맨 아래로 내린다(페이지는 움직이지 않는다). 읽으려고 위로 올려 둔 동안에는 끌어내리지 않는다 */
function LogFeed({ lines, who }: { lines: LogLine[]; who: Who }) {
  const box = useRef<HTMLDivElement>(null);
  const atBottom = useRef(true);
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const el = box.current;
    if (el && atBottom.current) el.scrollTop = el.scrollHeight;
  }, [lines.length]);
  return (
    <div ref={box} className={cx('rp-feed', scrolled && 'is-scrolled')} role="log" aria-live="polite"
      onScroll={(event) => {
        const el = event.currentTarget;
        atBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 24;
        setScrolled(el.scrollTop > 4);
      }}>
      <ol>{lines.map((line) => <LogItem key={line.key} line={line} who={who} />)}</ol>
    </div>
  );
}

function LogItem({ line, who }: { line: LogLine; who: Who }) {
  const mine = line.kind === 'techs' || line.kind === 'found' || line.kind === 'picked';
  return (
    <li className={cx('rp-line', line.kind === 'checking' && 'rp-line--now', line.kind === 'miss' && 'rp-line--miss')}>
      {mine
        ? <span className="rp-line__me" aria-hidden="true"><UserAvatar src={who.avatarUrl} login={who.login} size={28} /></span>
        : <CompanyMark name={line.candidate.companyName} logoUrl={line.candidate.logoUrl} size={28} className="rp-line__mark" />}
      <div>{lineText(line)}</div>
    </li>
  );
}

function lineText(line: LogLine): ReactNode {
  switch (line.kind) {
    case 'techs': return <>
      <b>카드 {line.cardCount}장</b>에서 기술 {line.techs.length}개를 읽었어요
      <span className="rp-chips">{line.techs.map((tech) => <span key={tech}>{techName(tech)}</span>)}</span>
    </>;
    case 'found': return <>지금 지원할 수 있는 공고에서 기술이 겹치는 회사 <b>{line.count}곳</b>을 찾았어요</>;
    case 'checking': return <><b>{line.candidate.companyName}</b> 공고의 겹치는 조건 {line.candidate.overlap}개를 내 카드와 맞춰 보고 있어요</>;
    case 'hit': return <>
      <span className={cx('rp-tag', line.hit.type === 'REQUIRED' ? 'rp-tag--req' : 'rp-tag--pref')}>{line.hit.type === 'REQUIRED' ? '필수' : '우대'}</span>
      <q>{line.hit.text}</q> 조건이 <span className="rp-card-name">{line.hit.cardTitle}</span> 카드와 맞아요
    </>;
    case 'miss': return line.why === 'FAILED' ? `${line.candidate.companyName}, 응답을 받지 못해 건너뛰었어요`
      : line.why === 'NO_OVERLAP' ? `${line.candidate.companyName}, 겹치는 조건이 없어요`
      : `${line.candidate.companyName}, 겹치는 조건 ${line.candidate.overlap}개 중 맞는 게 없어요`;
    case 'picked': return line.count > 0 ? <>필수 조건을 많이 채운 순으로 <b>{line.count}곳</b>을 골랐어요</> : '맞는 조건이 있는 회사를 찾지 못했어요';
  }
}

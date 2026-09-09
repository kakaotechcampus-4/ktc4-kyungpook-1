import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ArrowRight, ArrowUp, ChevronRight, FolderGit2, MessageSquareQuote, PenLine, Search } from 'lucide-react';
import { useCards, useMe, useRepos } from '@/api/queries';
import type { CardSummary, StarField } from '@/api/schemas';
import { Badge, Button, EmptyState, IconBox, KindIcon, SectionHead, Skeleton, StarDots, Toolbar } from '@/components/ui';
import { cardKindShort, cardStatusLabel, candidateRefLabel } from '@/lib/labels';
import { ym } from '@/lib/format';
import { track } from '@/lib/track';
import { useDocumentTitle } from '@/lib/useDocumentTitle';

/**
 * E1 홈 · A3 첫 진입. 레퍼런스(TIO) 구조: 중앙 프롬프트 → 보조 pill → 이어서 하기 → 카드.
 * 프롬프트는 "레포 이름을 치면 바로 그 레포의 사전 고지로" — 레포 고르기 페이지를 건너뛰는 빠른 길.
 */
export function HomePage() {
  useDocumentTitle('경험정리');
  const me = useMe();
  const cards = useCards();
  const repos = useRepos();
  const [q, setQ] = useState('');
  const [rq, setRq] = useState('');
  const [hi, setHi] = useState(0);
  const nav = useNavigate();

  const list = useMemo(() => (cards.data ?? []).filter((c) => c.title.toLowerCase().includes(q.toLowerCase())), [cards.data, q]);
  const drafts = (cards.data ?? []).filter((c) => c.status === 'DRAFT').slice(0, 3);
  const empty = cards.isSuccess && cards.data.length === 0;
  const sugg = useMemo(() => {
    const xs = repos.data ?? [];
    const t = rq.trim().toLowerCase();
    const pool = t ? xs.filter((r) => `${r.owner}/${r.name}`.toLowerCase().includes(t)) : xs.filter((r) => r.recommended && !r.lastAnalyzedAt);
    return pool.slice(0, 5);
  }, [repos.data, rq]);
  const go = (id?: string) => {
    const target = id ?? sugg[hi]?.id;
    if (!target) { nav('/repos'); return; }
    track('repo_selected', { repoId: target, via: 'prompt' });
    nav(`/repos?select=${target}&disclose=1`);
  };

  return (
    <main className="main">
      <section className="prompt">
        <h1 className="prompt__h">오늘은 어떤 <span className="hl">경험을</span> 정리해 볼까요?</h1>
        <p className="prompt__sub">레포 하나만 고르면, 커밋을 읽어서 카드로 정리해 드려요.</p>
        <div className="prompt__card" role="search">
          <div className="prompt__row">
            <input className="prompt__input" value={rq} onChange={(e) => { setRq(e.target.value); setHi(0); }} placeholder="레포 이름을 적어 보세요" aria-label="레포 검색"
              onKeyDown={(e) => { if (e.key === 'ArrowDown') { e.preventDefault(); setHi((h) => Math.min(h + 1, sugg.length - 1)); } if (e.key === 'ArrowUp') { e.preventDefault(); setHi((h) => Math.max(h - 1, 0)); } if (e.key === 'Enter') void go(); }} />
            <button type="button" className="prompt__go" onClick={() => void go()} aria-label="이 레포로 시작" disabled={!sugg.length}><ArrowUp size={18} /></button>
          </div>
          {(rq.trim() || sugg.length > 0) && (
            <div className="prompt__list">
              {sugg.length === 0
                ? <p className="prompt__none">그런 이름의 레포가 없어요</p>
                : (
                  <ul className="prompt__sugg" role="listbox" aria-label="레포 제안">
                    {sugg.map((r, i) => (
                      <li key={r.id}>
                        <button type="button" role="option" aria-selected={i === hi} onMouseEnter={() => setHi(i)} onClick={() => go(r.id)}>
                          <FolderGit2 size={15} className="prompt__sugg-icon" />
                          <span className="prompt__sugg-name">{r.owner} / {r.name}</span>
                          <span className="prompt__sugg-meta">내 커밋 {r.contribution.mine} · 팀 {r.contribution.team}</span>
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
            </div>
          )}
        </div>
        <div className="prompt__chips">
          <Link to="/repos" className="pill"><FolderGit2 size={14} /> 전체 레포에서 고르기</Link>
          <Link to="/cards/new" className="pill"><PenLine size={14} /> 코드에 없는 경험 직접 쓰기</Link>
        </div>
      </section>

      {drafts.length > 0 && (
        <section className="stack" style={{ gap: 10 }}>
          <SectionHead label="이어서 하기" count={drafts.length}  />
          <div className="resume">
            {drafts.map((c) => (
              <Link key={c.id} to={`/cards/${c.id}`} className="resume__item">
                <KindIcon kind={c.kind} size={30} />
                <div className="stack grow" style={{ gap: 3, minWidth: 0 }}>
                  <span className="resume__title">{c.title}</span>
                  <span className="t-12 c-2">{c.hasDropped ? '빈 칸 있음' : c.hasLowConfidence ? '⚑ 확인 필요' : '확정 대기'}</span>
                </div>
                <StarDots filled={filledOf(c)} />
                <ArrowRight size={16} className="c-3" />
              </Link>
            ))}
          </div>
        </section>
      )}

      {empty ? (
        <>
          <section className="intro">
            <h2 className="intro__h">경험부터 정리해 볼까요?</h2>
            <p className="intro__s">한 레포에서 카드 여러 장이 나와요. 셋 중 아무거나 골라 시작하시면 됩니다.</p>
          </section>
          <div className="sources">
            <SourceRow icon={FolderGit2} title="레포에서 정리하기" time="약 40초" desc="커밋과 PR을 읽어서 카드 초안까지 만들어 드려요" onClick={() => nav('/repos')} />
            <SourceRow icon={Search} title="파일 보면서 떠올리기" time="약 2분" desc="커밋 메시지가 부실해도, 만진 파일로 기억을 꺼내 드려요" onClick={() => nav('/repos?filter=nopr')} />
            <SourceRow icon={MessageSquareQuote} title="질문에 답하면서 직접 쓰기" time="약 3분" desc="팀을 설득한 일처럼 코드에 안 남는 경험은 여기서 씁니다" onClick={() => nav('/cards/new')} />
          </div>
        </>
      ) : (
        <>
          <Toolbar placeholder="카드 이름으로 검색" value={q} onChange={setQ}>
            <Link to="/cards/new" className="btn btn--text"><PenLine size={14} /> 직접 작성</Link>
            <Button onClick={() => nav('/repos')}><FolderGit2 size={14} /> 레포 정리하기</Button>
          </Toolbar>
          <SectionHead label={`${me.data?.login ?? '나'}의 경험 카드`} count={cards.data?.length} />
          {cards.isPending && <div className="grid-2">{[0, 1, 2, 3].map((i) => <Skeleton key={i} h={150} />)}</div>}
          <div className="grid-2">{list.map((c) => <CardGridItem key={c.id} c={c} />)}</div>
          {cards.isSuccess && list.length === 0 && <EmptyState icon={Search} title={`"${q}" 에 맞는 카드가 없습니다`} desc="다른 이름으로 찾아보세요." />}
        </>
      )}
    </main>
  );
}

export function SourceRow({ icon, title, time, desc, onClick }: { icon: typeof FolderGit2; title: string; time: string; desc: string; onClick: () => void }) {
  return (
    <button type="button" className="source" onClick={onClick}>
      <IconBox icon={icon} size={44} />
      <div className="stack" style={{ gap: 4, minWidth: 0 }}>
        <span className="source__t">{title}<span className="source__time">{time}</span></span>
        <span className="source__d">{desc}</span>
      </div>
      <ChevronRight size={18} className="source__chev" />
    </button>
  );
}

/** 목록 응답에는 칸 내용이 없으니 근거 수와 신호로 채움 상태를 추정한다. 상세에서 정확히 본다. */
function filledOf(c: CardSummary): StarField[] {
  const all: StarField[] = ['S', 'T', 'A', 'R'];
  if (c.status === 'CONFIRMED' && !c.hasDropped) return all;
  if (c.hasDropped) return all.slice(0, Math.max(1, Math.min(3, c.evidenceCount + c.userStatedCount > 3 ? 3 : 2)));
  return all;
}

export function CardGridItem({ c }: { c: CardSummary }) {
  const src = c.sourceType ? candidateRefLabel(c.sourceType, c.sourceLabel) : c.sourceLabel === 'INTERVIEW' ? '되묻기' : '직접 작성';
  const sub = c.hasDropped ? '빈 칸 있음' : c.userStatedCount && !c.evidenceCount ? `내가 말한 것 ${c.userStatedCount}건` : `근거 ${c.evidenceCount}건`;
  return (
    <Link to={`/cards/${c.id}`} className={`card gcard ${c.status === 'DRAFT' ? 'gcard--draft' : ''}`}>
      <div className="row" style={{ gap: 10 }}>
        <KindIcon kind={c.kind} size={30} />
        <Badge kind="NEUTRAL">{cardKindShort[c.kind]}</Badge>
        <span className="right t-12 c-3">{src}</span>
      </div>
      <h3 className="gcard__title">{c.title}</h3>
      <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
        <Badge kind={c.status}>{cardStatusLabel[c.status]}</Badge>
        {c.hasLowConfidence && c.status === 'DRAFT' && <Badge kind="CAUTION">⚑ 확인 필요</Badge>}
        <span className="right"><StarDots filled={filledOf(c)} /></span>
      </div>
      <div className="row t-12 c-2" style={{ gap: 6 }}>
        <span>{ym(c.period)}</span>
        <span className="right">{sub}</span>
      </div>
    </Link>
  );
}

import { ArrowRight, ChevronRight } from 'lucide-react';
import { useQueries } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { matchingApi, matchKeys, matchSupported, useOverallRecommendation } from '@/api/matching';
import { useCards } from '@/api/queries';
import { isDemo } from '@/mock/browser';
import { deadlineLabel } from '@/features/match/model';

type Pick = { companyName: string; posting: { id: string; title: string; deadline: string | null; closed: boolean } };

/**
 * 홈의 추천 공고 — 내 확정 카드로 매칭한 결과만 보인다. 전체 매칭 결과가 있으면 그 위 3곳, 없으면 카드가 있는 레포들의 추천을 맞은 필수 조건이 많은 순으로 합친다.
 * 둘 다 없으면 공고를 아무거나 보이지 않고 전체 매칭으로 잇는다.
 */
export function HomeRecommendations() {
  const supported = matchSupported();
  const cards = useCards();
  const overall = useOverallRecommendation();
  const confirmed = cards.data?.filter((card) => card.status === 'CONFIRMED') ?? [];
  const repoIds = [...new Set(confirmed.flatMap((card) => (card.repo ? [card.repo.id] : [])))];
  const repos = useQueries({
    queries: repoIds.map((repoId) => ({ queryKey: matchKeys.repo(repoId), queryFn: () => matchingApi.repoRecommendations(repoId), enabled: supported, retry: false })),
  });
  const fromOverall: Pick[] = overall.data?.companies ?? [];
  const fromRepos: Pick[] = repos.flatMap((repo) => repo.data?.companies ?? []).sort((a, b) => b.requiredMet - a.requiredMet);
  const seen = new Set<string>();
  const picks = (fromOverall.length ? fromOverall : fromRepos)
    .filter((pick) => !pick.posting.closed && !seen.has(pick.posting.id) && !!seen.add(pick.posting.id))
    .slice(0, 3);
  const loading = cards.isPending || (confirmed.length > 0 && (overall.isPending || repos.some((repo) => repo.isPending)));

  return <section className="home-recommendations" aria-labelledby="home-recommendations-title">
    <div className="home-panel-head"><h2 id="home-recommendations-title">추천 공고</h2>{supported && <Link to="/match">전체 보기 <ArrowRight size={14} /></Link>}</div>
    {isDemo && <p className="home-recommendations__notice">체험용 예시 공고예요.</p>}
    {!supported ? <p className="home-panel-empty">공고 매칭 기능을 연결하면 추천 공고가 표시돼요.</p>
      : loading ? <p className="home-panel-empty" role="status">추천 공고를 불러오고 있어요.</p>
      : confirmed.length === 0 ? <p className="home-panel-empty">경험 카드를 확정하면 맞는 공고를 골라 드려요.</p>
      : picks.length ? picks.map((pick, index) => <Link className="recommended-job" key={pick.posting.id} to={`/match/${encodeURIComponent(pick.posting.id)}`}>
        <span className={`recommended-job__logo recommended-job__logo--${index}`}>{pick.companyName.slice(0, 1)}</span><span className="recommended-job__copy"><strong>{pick.posting.title}</strong><span>{pick.companyName}</span><small>{deadlineLabel(pick.posting.deadline)}</small></span><ChevronRight size={17} aria-hidden />
      </Link>)
      : <div className="home-panel-empty"><p>전체 매칭을 하면 내 카드와 맞는 공고를 골라 드려요.</p><Link to="/match" className="btn btn--outline btn--sm">전체 매칭 하기</Link></div>}
  </section>;
}

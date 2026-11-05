import { useQueries } from '@tanstack/react-query';
import { matchingApi, matchKeys, matchSupported, useOverallRecommendation } from '@/api/matching';
import type { CardSummary } from '@/api/schemas';
import type { HomePick } from '@/lib/homeModel';

/**
 * 홈에 보일 '나와 맞는 공고' — 전체 매칭 결과가 있으면 그것을, 없으면 카드가 있는 레포들의 추천을 맞은 필수 조건이 많은 순으로 합친다.
 * 레포 추천은 필수 조건이 모두 몇 개인지 주지 않아 total 이 null 이다.
 */
export function useHomePicks(cards: readonly CardSummary[]) {
  const supported = matchSupported();
  const overall = useOverallRecommendation();
  const confirmed = cards.filter((card) => card.status === 'CONFIRMED');
  const repoIds = [...new Set(confirmed.flatMap((card) => (card.repo ? [card.repo.id] : [])))];
  const repos = useQueries({
    queries: repoIds.map((repoId) => ({ queryKey: matchKeys.repo(repoId), queryFn: () => matchingApi.repoRecommendations(repoId), enabled: supported, retry: false })),
  });
  const fromOverall: HomePick[] = (overall.data?.companies ?? []).map((company) => ({
    postingId: company.posting.id, company: company.companyName, title: company.posting.title,
    deadline: company.posting.deadline, closed: company.posting.closed, met: company.requiredMet, total: company.requiredTotal,
  }));
  const fromRepos: HomePick[] = repos.flatMap((repo) => repo.data?.companies ?? []).sort((a, b) => b.requiredMet - a.requiredMet).map((company) => ({
    postingId: company.posting.id, company: company.companyName, title: company.posting.title,
    deadline: company.posting.deadline, closed: company.posting.closed, met: company.requiredMet, total: null,
  }));
  const seen = new Set<string>();
  const picks = (fromOverall.length ? fromOverall : fromRepos).filter((pick) => !pick.closed && !seen.has(pick.postingId) && !!seen.add(pick.postingId));
  return { picks, overall, computing: overall.data?.status === 'COMPUTING' };
}

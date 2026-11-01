import { ArrowRight, ChevronRight } from 'lucide-react';
import { Link } from 'react-router-dom';
import { matchSupported, useMatches } from '@/api/matching';
import { isDemo } from '@/mock/browser';
import { categoryLabel } from '@/features/match/model';

export function HomeRecommendations() {
  const matches = useMatches();
  const supported = matchSupported();
  const now = Date.now();
  const recommendations = [...(matches.data ?? [])]
    .filter(item => !item.source.expiresAt || Date.parse(item.source.expiresAt) >= now)
    .sort((a, b) => (a.fit ?? 'Z').localeCompare(b.fit ?? 'Z')).slice(0, 3);
  return <section className="home-recommendations" aria-labelledby="home-recommendations-title">
    <div className="home-panel-head"><h2 id="home-recommendations-title">추천 공고</h2>{supported && <Link to="/match">전체 보기 <ArrowRight size={14} /></Link>}</div>
    {isDemo && <p className="home-recommendations__notice">체험용 예시 공고예요.</p>}
    {!supported ? <p className="home-panel-empty">공고 매칭 기능을 연결하면 추천 공고가 표시돼요.</p> : matches.isPending ? <p className="home-panel-empty" role="status">공고를 불러오고 있어요.</p> : matches.isError ? <div className="home-panel-empty"><p>공고를 불러오지 못했어요.</p><button className="btn btn--outline btn--sm" onClick={() => void matches.refetch()}>다시 시도</button></div> : recommendations.length ? recommendations.map((item, index) => <Link className="recommended-job" key={item.id} to={`/match/${item.id}`}>
      <span className={`recommended-job__logo recommended-job__logo--${index}`}>{item.company.slice(0, 1)}</span><span className="recommended-job__copy"><strong>{item.role}</strong><span>{item.company}</span><small>{categoryLabel[item.category]}</small></span><ChevronRight size={17} aria-hidden />
    </Link>) : <p className="home-panel-empty">아직 추천할 공고가 없어요. 경험 카드를 정리하며 다음 기회를 준비하세요.</p>}
  </section>;
}

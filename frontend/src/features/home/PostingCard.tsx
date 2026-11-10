import { Link } from 'react-router-dom';
import { ArrowRight } from 'lucide-react';
import { useMatch } from '@/api/matching';
import { applyAction, conditionRows, deadlineLabel, statusSentence, type ApplyItem } from './homeApply';
import { ButtonLink } from '@/components/ui';

const detailPath = (postingId: string) => `/match/${encodeURIComponent(postingId)}`;

function ConditionsSkeleton() {
  return (
    <div className="home3-reqs-skeleton" aria-hidden>
      <div className="home3-sk home3-pulse" style={{ width: '58%', height: 14, marginTop: 22 }} />
      {[0, 1, 2].map((row) => (
        <div key={row} className="home3-sk-row">
          <div className="home3-sk home3-pulse" style={{ width: 18, height: 18, borderRadius: '50%' }} />
          <div className="home3-sk home3-pulse" style={{ width: '44%', height: 14 }} />
        </div>
      ))}
    </div>
  );
}

/** 지금 보이는 공고 하나만 상세를 읽는다 — 조건 목록과 맞춰 보지 않은 조건의 안내를 맡는다 */
function Conditions({ postingId }: { postingId: string }) {
  const match = useMatch(postingId);
  if (match.isError) {
    return (
      <div className="home3-note">
        <p>조건을 불러오지 못했어요</p>
        <button type="button" className="home3-text-btn" onClick={() => void match.refetch()} disabled={match.isFetching}>다시 시도</button>
      </div>
    );
  }
  if (!match.data) return <ConditionsSkeleton />;
  const detail = match.data;
  if (detail.needsJudgement) {
    return (
      <div className="home3-note">
        <p>아직 맞춰 보지 않은 조건이 있어요</p>
        <Link to={detailPath(postingId)} className="home3-text-btn">이 공고와 맞춰 보기</Link>
      </div>
    );
  }
  const view = conditionRows(detail);
  const total = detail.summary?.requiredTotal ?? view.total;
  const met = detail.summary?.requiredMet ?? view.metCount;
  if (!view.rows.length) return null;
  return (
    <>
      <div className="home3-fithead">
        <p className="home3-fitline">필수 조건 <b className="home3-tnum">{total}개 중 {met}개</b>를 내 카드로 채워요</p>
        <span className="home3-colh">채운 내 카드</span>
      </div>
      <ul className="home3-reqs">
        {view.rows.map((row) => (row.cardTitle
          ? (
            <li key={row.id} className="home3-req">
              <span className="home3-mk home3-mk--met" role="img" aria-label="채웠어요" />
              <span className="home3-req__c">{row.text}</span>
              <span className="home3-req__ev" title={row.cardTitle}>{row.cardTitle}</span>
            </li>
          )
          : (
            <li key={row.id} className="home3-req home3-req--miss">
              <span className="home3-mk home3-mk--miss" role="img" aria-label="아직 없어요" />
              <span className="home3-req__c">{row.text}</span>
              <span className="home3-req__ev"><Link to={detailPath(postingId)}>채울 경험 찾기</Link></span>
            </li>
          )))}
      </ul>
      {view.hiddenMet > 0 && <div className="home3-more">채운 조건 {view.hiddenMet}개 더 있어요<Link to={detailPath(postingId)}>공고에서 보기</Link></div>}
    </>
  );
}

type Props = { item: ApplyItem; active: boolean; readDetail: boolean; panelProps: Record<string, unknown> };

/** 지원할 곳 카드 한 장 — 머리(회사 · 상태 · 제목 · 마감), 조건 목록, 버튼 */
export function PostingCard({ item, active, readDetail, panelProps }: Props) {
  const { pick } = item;
  const dead = deadlineLabel(item.daysLeft, pick.deadline);
  const action = applyAction(pick.postingId, item.letter);
  return (
    <article className="home3-lead" {...panelProps} aria-hidden={active ? undefined : true} inert={!active}>
      <div className="home3-lead__hd">
        <div className="home3-lead__who">
          <div className="home3-meta"><span>{pick.company}</span><span>{statusSentence(item)}</span></div>
          <h2>{pick.title}</h2>
        </div>
        <div className="home3-dd">
          <b className={`home3-tnum${dead.soon ? ' is-soon' : ''}`}>{dead.dday}</b>
          <small>{dead.date}</small>
        </div>
      </div>
      {readDetail ? <Conditions postingId={pick.postingId} /> : <ConditionsSkeleton />}
      <div className="home3-cta">
        <ButtonLink to={action.to} variant="primary" className="home3-btn">{action.label}<ArrowRight size={18} aria-hidden /></ButtonLink>
        <Link to={detailPath(pick.postingId)} className="home3-tbtn">공고 보기</Link>
      </div>
    </article>
  );
}

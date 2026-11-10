import { Link } from 'react-router-dom';
import { FileText } from 'lucide-react';
import { useCoverLetters, type CoverLetterStage } from '@/api/coverLetters';
import { Badge, Breadcrumb, EmptyState, PageTitle, Skeleton } from '@/components/ui';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { CompanyMark } from '@/features/match/CompanyMark';
import { postingLine } from '@/lib/coverLetterCopy';
import { CoverLetterDeleteAction } from './CoverLetterDeleteAction';
import { ymd } from '@/lib/format';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import './coverLetters.css';

const STAGE_LABEL: Record<CoverLetterStage, string> = {
  ASSIGNING: '카드 고르는 중', ASSIGNED: '이어서 쓰는 중', ASKING: '질문 만드는 중', ASKED: '이어서 쓰는 중',
  DRAFTING: '초안 쓰는 중', DRAFTED: '초안 완성', FAILED: '멈춤',
};

/**
 * 자소서 목록 — 최근 만든 순(서버 순서 그대로)으로 자소서마다 종이 한 장을 놓는다. 넓은 화면은 한 줄에 3장이다.
 * 종이 위쪽은 지원하는 기업과 상태, 가운데는 직무, 아래는 문항 수와 첫 문항이다. 공고 없이 쓴 자소서는 기업 자리에 그렇게 적는다(#637).
 */
export function CoverLettersPage() {
  useDocumentTitle('자소서');
  const list = useCoverLetters();
  const add = <Link to="/cover-letters/new" className="btn btn--primary">새 자소서</Link>;
  return (
    <main className="main main--tight">
      <Breadcrumb items={[{ label: '홈', to: '/' }, { label: '자소서' }]} />
      <PageTitle right={add} sub="확정한 경험 카드로 문항마다 초안을 써요.">자소서</PageTitle>
      {list.isError ? <QueryFailure error={list.error} retry={() => list.refetch()} pending={list.isFetching} />
        : list.isPending ? <div className="cl-shelf" aria-hidden="true"><Skeleton h={348} /><Skeleton h={348} /><Skeleton h={348} /></div>
        : list.data.length === 0 ? (
          <EmptyState icon={FileText} title="아직 쓴 자소서가 없어요" desc="지원 직무와 문항을 넣으면 문항마다 쓸 카드를 골라 드려요.">{add}</EmptyState>
        ) : (
          <ul className="cl-shelf">
            {list.data.map((l) => {
              const company = l.posting?.company ?? null;
              // 공고 제목이 직무와 다를 때만 따로 보인다
              const postingTitle = l.posting && postingLine(l.posting, l.jobRole) !== l.posting.company ? l.posting.title : null;
              return (
                <li key={l.id} className="cl-sheet">
                  <Link to={`/cover-letters/${encodeURIComponent(l.id)}`} className="cl-sheet__link">
                    <div className="cl-sheet__top">
                      {company
                        ? <span className="cl-sheet__company"><CompanyMark name={company} size={40} /><span>{company}</span></span>
                        : <span className="cl-sheet__company cl-sheet__company--none"><span className="cl-sheet__nomark" aria-hidden="true"><FileText size={20} /></span><span>공고 없이 쓴 자소서</span></span>}
                      <Badge kind={l.stage === 'DRAFTED' ? 'CONFIRMED' : 'NEUTRAL'}>{STAGE_LABEL[l.stage]}</Badge>
                    </div>
                    <h2 className="cl-sheet__role">{l.jobRole}</h2>
                    {postingTitle && <p className="cl-sheet__posting">{postingTitle}</p>}
                    <div className="cl-sheet__body">
                      <span className="cl-sheet__count">문항 {l.questionCount}개</span>
                      <p className="cl-sheet__first">{l.firstQuestion ?? ''}</p>
                    </div>
                  </Link>
                  {/* 링크 안에 버튼을 넣을 수 없어 종이 아래 줄에 따로 둔다 */}
                  <div className="cl-sheet__foot">
                    <span className="cl-sheet__date">{ymd(l.updatedAt)}</span>
                    <CoverLetterDeleteAction letter={l} ariaLabel={`${l.jobRole} 자소서 삭제`} />
                  </div>
                </li>
              );
            })}
          </ul>
        )}
    </main>
  );
}

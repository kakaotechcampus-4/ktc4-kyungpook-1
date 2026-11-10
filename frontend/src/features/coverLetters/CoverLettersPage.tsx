import { Link } from 'react-router-dom';
import { Briefcase, FileText } from 'lucide-react';
import { useCoverLetters, type CoverLetterStage } from '@/api/coverLetters';
import { Badge, Breadcrumb, EmptyState, PageTitle, Skeleton } from '@/components/ui';
import { QueryFailure } from '@/components/ui/QueryFailure';
import { postingLine } from '@/lib/coverLetterCopy';
import { CoverLetterDeleteAction } from './CoverLetterDeleteAction';
import { ymd } from '@/lib/format';
import { useDocumentTitle } from '@/lib/useDocumentTitle';
import './coverLetters.css';

const STAGE_LABEL: Record<CoverLetterStage, string> = {
  ASSIGNING: '카드 고르는 중', ASSIGNED: '이어서 쓰는 중', ASKING: '질문 만드는 중', ASKED: '이어서 쓰는 중',
  DRAFTING: '초안 쓰는 중', DRAFTED: '초안 완성', FAILED: '멈춤',
};

/** 자소서 목록 — 최근 만든 순(서버 순서 그대로). 공고에서 시작한 자소서는 직무 아래에 공고를 보인다(#637) */
export function CoverLettersPage() {
  useDocumentTitle('자소서');
  const list = useCoverLetters();
  const add = <Link to="/cover-letters/new" className="btn btn--primary">새 자소서</Link>;
  return (
    <main className="main main--tight">
      <Breadcrumb items={[{ label: '홈', to: '/' }, { label: '자소서' }]} />
      <PageTitle right={add} sub="확정한 경험 카드로 문항마다 초안을 써요.">자소서</PageTitle>
      {list.isError ? <QueryFailure error={list.error} retry={() => list.refetch()} pending={list.isFetching} />
        : list.isPending ? <Skeleton h={160} />
        : list.data.length === 0 ? (
          <EmptyState icon={FileText} title="아직 쓴 자소서가 없어요" desc="지원 직무와 문항을 넣으면 문항마다 쓸 카드를 골라 드려요.">{add}</EmptyState>
        ) : (
          <ul className="cl-list">
            {list.data.map((l) => {
              const where = postingLine(l.posting, l.jobRole);
              return (
                <li key={l.id} className="cl-list__item">
                  <Link to={`/cover-letters/${encodeURIComponent(l.id)}`} className="cl-list__row">
                    <div className="stack grow" style={{ gap: 4, minWidth: 0 }}>
                      <span className="w-600">{l.jobRole}</span>
                      {where && <span className="t-12 c-3 cl-list__posting"><Briefcase size={12} aria-hidden />{where}</span>}
                      <span className="t-13 c-2 cl-list__first">{l.firstQuestion ?? ''}{l.questionCount > 1 ? ` 외 ${l.questionCount - 1}문항` : ''}</span>
                    </div>
                    <div className="stack" style={{ gap: 4, alignItems: 'flex-end' }}>
                      <Badge kind={l.stage === 'DRAFTED' ? 'CONFIRMED' : 'NEUTRAL'}>{STAGE_LABEL[l.stage]}</Badge>
                      <span className="t-12 c-3">{ymd(l.updatedAt)}</span>
                    </div>
                  </Link>
                  {/* 링크 안에 버튼을 넣을 수 없어 링크 옆에 둔다 */}
                  <div className="cl-list__delete"><CoverLetterDeleteAction letter={l} ariaLabel={`${l.jobRole} 자소서 삭제`} /></div>
                </li>
              );
            })}
          </ul>
        )}
    </main>
  );
}

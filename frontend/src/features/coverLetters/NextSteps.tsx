import { ExternalLink } from 'lucide-react';
import { useMatch } from '@/api/matching';
import type { CoverLetterPosting } from '@/api/coverLetters';
import { buttonClass } from '@/components/ui';
import { CalendarToggle } from '@/features/match/CalendarToggle';
import { companyLabel } from '@/features/match/model';
import { track } from '@/lib/track';

/** 자소서 응답의 공고에는 원문 주소와 마감일이 없어 공고 상세를 따로 읽는다 — 읽는 동안과 못 읽었을 때는 있는 것만 보인다 */
export function NextSteps({ posting }: { posting: CoverLetterPosting }) {
  const detail = useMatch(posting.id).data;
  return (
    <div className="cl-next">
      {detail?.source.url && (
        <a href={detail.source.url} target="_blank" rel="noopener" className={buttonClass('secondary')} onClick={() => track('match_source_opened', { matchId: detail.id })}>
          공고 원문에서 지원하기
          <ExternalLink size={14} aria-hidden />
        </a>
      )}
      {detail && <CalendarToggle posting={{ id: detail.id, company: companyLabel(detail.company), role: detail.role, expiresAt: detail.source.expiresAt }} />}
    </div>
  );
}

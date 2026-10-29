package com.gitory.backend.job.domain;

import com.gitory.backend.card.domain.GeneratedCardQueryService;
import com.gitory.backend.job.infra.AnalysisJobRepository;
import com.gitory.backend.recommend.domain.CandidateEmptyReasons;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** 조회와 취소 응답이 같은 Job 화면 값을 만들도록, 저장소 공개 id·카드 초안 Job 이 만든 카드·후보가 없는 이유 줄을 한 곳에서 채운다 */
@Component
@RequiredArgsConstructor
class JobViewFactory {

    private final AnalysisJobRepository jobs;
    private final CandidateEmptyReasons emptyReasons;
    private final GeneratedCardQueryService generatedCards;
    private final JobActivityLog activity;

    JobView of(AnalysisJob job, String login) {

        UUID repoId = jobs.findUserRepositoryPublicId(job.getUserRepositoryId());
        List<UUID> cardIds = job.getType() == JobType.DRAFT
                ? generatedCards.cardIdsOf(job.getPublicId())
                : List.of();

        return JobView.from(job, repoId, cardIds, () -> emptyReasons.of(repoId, job.getUserId(), login),
                activity.recent(job.getId()));

    }
}

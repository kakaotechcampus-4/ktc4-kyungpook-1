package com.gitory.backend.job.domain;

import com.gitory.backend.job.infra.AnalysisJobRepository;
import com.gitory.backend.recommend.domain.CandidateEmptyReasons;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Job 을 응답용으로 바꾸는 곳을 한 군데로 모아, 후보가 없는 이유 줄을 조회와 취소 응답이 똑같이 채우게 한다 */
@Component
@RequiredArgsConstructor
class JobViewFactory {

    private final AnalysisJobRepository jobs;
    private final CandidateEmptyReasons emptyReasons;

    JobView of(AnalysisJob job, String login) {

        UUID repoId = jobs.findUserRepositoryPublicId(job.getUserRepositoryId());

        return JobView.from(job, repoId, () -> emptyReasons.of(repoId, job.getUserId(), login));

    }
}

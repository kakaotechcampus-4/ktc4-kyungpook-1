package com.gitory.backend.job.domain;

import com.gitory.backend.job.infra.AnalysisJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 본인 Job 을 취소하고, 취소된(또는 이미 끝나 있던) Job 을 돌려준다 */
@Service
@RequiredArgsConstructor
public class JobCancelService {

    private final AnalysisJobRepository jobs;

    @Transactional
    public JobView cancel(UUID publicId, Long userId) {

        AnalysisJob job = jobs.findWithLockByPublicIdAndUserId(publicId, userId)
                .orElseThrow(JobNotFoundException::new);
        job.cancel();
        jobs.flush();

        return JobView.from(job, jobs.findUserRepositoryPublicId(job.getUserRepositoryId()));

    }
}

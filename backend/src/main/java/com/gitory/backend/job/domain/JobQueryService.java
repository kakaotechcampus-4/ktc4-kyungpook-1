package com.gitory.backend.job.domain;

import com.gitory.backend.job.infra.AnalysisJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JobQueryService {

    private final AnalysisJobRepository jobs;

    @Transactional(readOnly = true)
    public JobView load(UUID publicId, Long userId) {

        AnalysisJob job = jobs.findByPublicIdAndUserId(publicId, userId)
                .orElseThrow(JobNotFoundException::new);

        return JobView.from(job, jobs.findUserRepositoryPublicId(job.getUserRepositoryId()));

    }

    @Transactional(readOnly = true)
    public List<ActiveJobRow> loadActive(Long userId) {
        return jobs.findActiveRowsByUserId(userId, JobState.ACTIVE_NAMES);
    }
}

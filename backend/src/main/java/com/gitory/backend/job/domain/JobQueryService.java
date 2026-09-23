package com.gitory.backend.job.domain;

import com.gitory.backend.job.infra.AnalysisJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JobQueryService {

    private final AnalysisJobRepository jobs;

    @Transactional(readOnly = true)
    public JobResponse load(UUID publicId, Long userId) {
        return jobs.findByPublicIdAndUserId(publicId, userId)
                .map(JobResponse::from)
                .orElseThrow(JobNotFoundException::new);
    }
}

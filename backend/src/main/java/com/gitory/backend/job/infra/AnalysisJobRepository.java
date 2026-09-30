package com.gitory.backend.job.infra;


import com.gitory.backend.job.domain.AnalysisJob;
import com.gitory.backend.job.domain.JobState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, Long> {

    Optional<AnalysisJob> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    // 한 레포에서 진행 중인 Job 은 오직 하나뿐이라(uq_job_active) 결과는 최대 1개 반환
    Optional<AnalysisJob> findByUserRepositoryIdAndStateIn(Long userRepositoryId, List<JobState> states);

    Optional<AnalysisJob> findByPublicIdAndUserId(UUID publicId, Long userId);
}

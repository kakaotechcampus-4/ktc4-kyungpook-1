package com.gitory.backend.job.infra;


import com.gitory.backend.job.domain.ActiveJobRow;
import com.gitory.backend.job.domain.AnalysisJob;
import com.gitory.backend.job.domain.JobState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, Long> {

    Optional<AnalysisJob> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    // 한 레포에서 진행 중인 Job 은 오직 하나뿐이라(uq_job_active) 결과는 최대 1개 반환
    Optional<AnalysisJob> findByUserRepositoryIdAndStateIn(Long userRepositoryId, List<JobState> states);

    Optional<AnalysisJob> findByPublicIdAndUserId(UUID publicId, Long userId);

    @Query(value = """
            SELECT j.public_id                      AS jobId,
                   j.state                          AS state,
                   j.type                           AS type,
                   ur.public_id                     AS userRepositoryId,
                   r.owner_login || '/' || r.name   AS repoName,
                   j.started_at                     AS startedAt
            FROM analysis_job j
                     JOIN user_repository ur ON ur.id = j.user_repository_id
                     JOIN repository r ON r.id = ur.repository_id
            WHERE j.user_id = :userId
              AND j.state IN (:states)
            ORDER BY j.started_at DESC
            """, nativeQuery = true)
    List<ActiveJobRow> findActiveRowsByUserId(@Param("userId") Long userId, @Param("states") List<String> states);
}

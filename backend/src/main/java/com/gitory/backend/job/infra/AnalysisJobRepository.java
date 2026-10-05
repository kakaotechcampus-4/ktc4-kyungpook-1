package com.gitory.backend.job.infra;


import com.gitory.backend.job.domain.ActiveJobRow;
import com.gitory.backend.job.domain.AnalysisJob;
import com.gitory.backend.job.domain.JobState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, Long> {

    Optional<AnalysisJob> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    // 한 레포에서 진행 중인 Job 은 오직 하나뿐이라(uq_job_active) 결과는 최대 1개 반환
    Optional<AnalysisJob> findByUserRepositoryIdAndStateIn(Long userRepositoryId, List<JobState> states);

    Optional<AnalysisJob> findByPublicIdAndUserId(UUID publicId, Long userId);

    // 다른 워커가 잡고 있는 행은 건너뛰어 같은 Job 을 두 번 꺼내지 않는다
    @Query(value = """
            SELECT * FROM analysis_job
            WHERE state = 'QUEUED'
            ORDER BY started_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<AnalysisJob> findNextQueuedForUpdate();

    List<AnalysisJob> findByStateAndUpdatedAtBefore(JobState state, Instant before);

    // 취소와 워커 마무리가 겹쳐도 한쪽이 바꾼 상태를 다른 쪽이 보고 판단하도록 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AnalysisJob> findWithLockByPublicIdAndUserId(UUID publicId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AnalysisJob> findWithLockById(Long id);

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

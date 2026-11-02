package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.MatchGapFill;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface MatchGapFillRepository extends JpaRepository<MatchGapFill, Long> {

    Optional<MatchGapFill> findByUserIdAndJobPostingId(Long userId, Long jobPostingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from MatchGapFill f where f.userId = :userId and f.jobPostingId = :jobPostingId")
    Optional<MatchGapFill> findWithLockByUserIdAndJobPostingId(@Param("userId") Long userId,
                                                               @Param("jobPostingId") Long jobPostingId);

    // 찾는 중인 줄은 건드리지 않아 같은 공고를 두 곳에서 찾지 않고, 시작한 지 오래된 줄만 다시 시작한다
    // 시작했으면 1, 이미 찾는 중이면 0 을 돌려주며, conditions 는 아직 아무것도 정하지 않은 조건들의 JSON 이다
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO match_gap_fill (user_id, job_posting_id, status, conditions, requested_at)
            VALUES (:userId, :jobPostingId, 'RUNNING', CAST(:conditions AS jsonb), :now)
            ON CONFLICT (user_id, job_posting_id) DO UPDATE SET status = 'RUNNING', conditions = EXCLUDED.conditions,
                                                                fingerprint = NULL, requested_at = :now,
                                                                finished_at = NULL
            WHERE match_gap_fill.status <> 'RUNNING' OR match_gap_fill.requested_at <= :stuckBefore
            """, nativeQuery = true)
    int start(@Param("userId") Long userId, @Param("jobPostingId") Long jobPostingId,
              @Param("conditions") String conditions, @Param("now") Instant now,
              @Param("stuckBefore") Instant stuckBefore);

    // 다시 찾기는 읽은 그 끝난 결과에서만 시작해, 그사이 다른 요청이 시작했거나 결과를 바꿨으면 0 을 돌려준다
    // 지문은 그대로 두어, 카드·후보·레포가 바뀌지 않은 동안은 처음부터 찾기가 이 결과를 그대로 쓴다
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE match_gap_fill SET status = 'RUNNING', conditions = CAST(:conditions AS jsonb), requested_at = :now,
                                      finished_at = NULL
            WHERE user_id = :userId AND job_posting_id = :jobPostingId AND status = 'DONE'
              AND requested_at = :finishedRunAt
            """, nativeQuery = true)
    int restart(@Param("userId") Long userId, @Param("jobPostingId") Long jobPostingId,
                @Param("conditions") String conditions, @Param("now") Instant now,
                @Param("finishedRunAt") Instant finishedRunAt);

    // 못 채운 조건이 없는 공고는 AI 없이 바로 끝난 줄로 두되, 찾는 중인 줄은 그 결과를 기다린다
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO match_gap_fill (user_id, job_posting_id, status, conditions, requested_at, finished_at)
            VALUES (:userId, :jobPostingId, 'DONE', '[]', :now, :now)
            ON CONFLICT (user_id, job_posting_id) DO UPDATE SET status = 'DONE', conditions = '[]', fingerprint = NULL,
                                                                requested_at = :now, finished_at = :now
            WHERE match_gap_fill.status <> 'RUNNING' OR match_gap_fill.requested_at <= :stuckBefore
            """, nativeQuery = true)
    void finishEmpty(@Param("userId") Long userId, @Param("jobPostingId") Long jobPostingId,
                     @Param("now") Instant now, @Param("stuckBefore") Instant stuckBefore);
}

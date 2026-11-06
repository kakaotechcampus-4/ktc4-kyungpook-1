package com.gitory.backend.job.infra;

import com.gitory.backend.job.domain.RepoRefresh;
import com.gitory.backend.job.domain.RepoUpdateRow;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RepoRefreshRepository extends JpaRepository<RepoRefresh, Long> {

    Optional<RepoRefresh> findByAnalysisJobId(Long analysisJobId);

    // 끝남 처리와 카드 초안 시작이 같은 기록을 동시에 바꾸지 않도록 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RepoRefresh> findWithLockByAnalysisJobId(Long analysisJobId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RepoRefresh> findWithLockByDraftJobId(Long draftJobId);

    boolean existsByDraftJobId(Long draftJobId);

    boolean existsByUserRepositoryIdAndCreatedAtAfter(Long userRepositoryId, Instant after);

    @Query(value = """
            SELECT ur.public_id                     AS repoId,
                   r.owner_login || '/' || r.name   AS repoName,
                   CAST(SUM(rr.card_count) AS INTEGER) AS cardCount,
                   MAX(rr.finished_at)              AS finishedAt
            FROM repo_refresh rr
                     JOIN user_repository ur ON ur.id = rr.user_repository_id
                     JOIN repository r ON r.id = ur.repository_id
            WHERE rr.user_id = :userId
              AND rr.state = 'READY'
              AND rr.seen_at IS NULL
            GROUP BY ur.id, ur.public_id, r.owner_login, r.name
            ORDER BY MAX(rr.finished_at) DESC
            """, nativeQuery = true)
    List<RepoUpdateRow> findUnseenReadyRows(@Param("userId") Long userId);

    @Transactional
    @Modifying
    @Query(value = """
            UPDATE repo_refresh SET seen_at = :now
            WHERE user_repository_id = :userRepositoryId AND state = 'READY' AND seen_at IS NULL
            """, nativeQuery = true)
    int markSeen(@Param("userRepositoryId") Long userRepositoryId, @Param("now") Instant now);
}

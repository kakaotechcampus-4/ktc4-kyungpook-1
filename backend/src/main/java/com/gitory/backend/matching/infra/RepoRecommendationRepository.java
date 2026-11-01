package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.RepoRecommendation;
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

public interface RepoRecommendationRepository extends JpaRepository<RepoRecommendation, Long> {

    Optional<RepoRecommendation> findByUserRepositoryId(Long userRepositoryId);

    // 계산 결과를 저장하는 사이에 '다시 계산 필요' 표시가 덮이지 않게 같은 줄을 잠그고 읽는다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RepoRecommendation r where r.userRepositoryId = :userRepositoryId")
    Optional<RepoRecommendation> findWithLockByUserRepositoryId(@Param("userRepositoryId") Long userRepositoryId);

    List<RepoRecommendation> findTop20ByStaleSinceLessThanEqualOrderByStaleSinceAsc(Instant cutoff);

    // 확정 카드가 바뀔 때마다 마지막 변경 시각으로 덮어, 1분 동안 더 바뀌지 않아야 계산하게 한다
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO repo_recommendation (user_id, user_repository_id, status, companies, stale_since)
            VALUES (:userId, :userRepositoryId, 'COMPUTING', '[]', :staleSince)
            ON CONFLICT (user_repository_id) DO UPDATE SET status = 'COMPUTING', stale_since = :staleSince
            """, nativeQuery = true)
    void markStale(@Param("userId") Long userId, @Param("userRepositoryId") Long userRepositoryId,
                   @Param("staleSince") Instant staleSince);

    // 표시를 지운 쪽만 계산해, 같은 레포를 두 번 계산하지 않는다
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE repo_recommendation SET stale_since = NULL
            WHERE id = :id AND stale_since IS NOT NULL AND stale_since <= :cutoff
            """, nativeQuery = true)
    int claim(@Param("id") Long id, @Param("cutoff") Instant cutoff);
}

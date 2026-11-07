package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.OverallRecommendation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface OverallRecommendationRepository extends JpaRepository<OverallRecommendation, Long> {

    Optional<OverallRecommendation> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from OverallRecommendation r where r.userId = :userId")
    Optional<OverallRecommendation> findWithLockByUserId(@Param("userId") Long userId);

    // 계산 중인 줄은 건드리지 않아 같은 사용자를 두 곳에서 계산하지 않고, 시작한 지 오래된 줄만 다시 시작한다
    // 시작했으면 1, 이미 계산 중이면 0 을 돌려준다
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO overall_recommendation (user_id, status, companies, requested_at)
            VALUES (:userId, 'COMPUTING', '[]', :now)
            ON CONFLICT (user_id) DO UPDATE SET status = 'COMPUTING', requested_at = :now, progress_done = 0,
                                                progress_total = 0
            WHERE overall_recommendation.status <> 'COMPUTING' OR overall_recommendation.requested_at <= :stuckBefore
            """, nativeQuery = true)
    int start(@Param("userId") Long userId, @Param("now") Instant now, @Param("stuckBefore") Instant stuckBefore);

    // 공고를 함께 물어 늦게 끝난 쪽이 앞 숫자로 덮을 수 있어, 확인한 수는 늘어날 때만 쓴다
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE overall_recommendation SET progress_done = :done, progress_total = :total
            WHERE user_id = :userId AND status = 'COMPUTING' AND (progress_done < :done OR progress_total <> :total)
            """, nativeQuery = true)
    void progress(@Param("userId") Long userId, @Param("done") int done, @Param("total") int total);
}

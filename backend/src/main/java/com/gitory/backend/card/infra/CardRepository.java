package com.gitory.backend.card.infra;

import com.gitory.backend.card.domain.Card;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CardRepository extends JpaRepository<Card, Long> {

    Optional<Card> findByPublicIdAndUserId(UUID publicId, Long userId);

    // 같은 카드에 동시에 온 변경이 앞 요청이 바꾼 상태와 버전 번호를 보고 판단하도록 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Card> findWithLockByPublicIdAndUserId(UUID publicId, Long userId);

    // 소유자를 보지 않으므로 사용자 요청이 아닌 초안 Job 만 쓴다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Card> findWithLockById(Long id);

    // 소유자를 보지 않으므로 남의 카드인지 가려 거절 기록을 남길 때만 쓴다
    Optional<Card> findByPublicId(UUID publicId);

    Optional<Card> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    List<Card> findByUserIdOrderByUpdatedAtDescIdDesc(Long userId);

    @Query("SELECT c.publicId FROM Card c WHERE c.generationJobId = :jobId ORDER BY c.id")
    List<UUID> findPublicIdsByGenerationJobId(@Param("jobId") UUID jobId);

    List<Card> findByGenerationJobIdOrderById(UUID generationJobId);

    @Query("SELECT c.activityId AS activityId, count(c) AS cards, "
            + "sum(CASE WHEN c.status = com.gitory.backend.card.domain.CardStatus.CONFIRMED THEN 1 ELSE 0 END) AS confirmed "
            + "FROM Card c WHERE c.activityId IN :activityIds GROUP BY c.activityId")
    List<ActivityCardCount> countByActivityIds(@Param("activityIds") Collection<Long> activityIds);

    interface ActivityCardCount {

        Long getActivityId();

        long getCards();

        long getConfirmed();
    }
}

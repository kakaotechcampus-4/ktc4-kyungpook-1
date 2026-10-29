package com.gitory.backend.card.infra;

import com.gitory.backend.card.domain.Activity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    Optional<Activity> findByPublicId(UUID publicId);

    Optional<Activity> findByPublicIdAndUserId(UUID publicId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Activity> findWithLockByPublicIdAndUserId(UUID publicId, Long userId);

    // 카드를 넣는 동안 활동이 지워지지 않게 하되, 같은 활동에 카드를 넣는 요청끼리는 서로 기다리지 않게 한다
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<Activity> findWithShareLockById(Long id);

    Optional<Activity> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    List<Activity> findByUserIdOrderByStartMonthDescIdDesc(Long userId);
}

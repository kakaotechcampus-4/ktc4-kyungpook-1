package com.gitory.backend.assistant.infra;

import com.gitory.backend.assistant.domain.AssistantRun;
import com.gitory.backend.assistant.domain.AssistantRunState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssistantRunRepository extends JpaRepository<AssistantRun, Long> {

    Optional<AssistantRun> findByPublicIdAndUserId(UUID publicId, Long userId);

    boolean existsByUserIdAndState(Long userId, AssistantRunState state);

    // 소유자를 보지 않으므로 남의 실행인지 가려 거절 기록을 남길 때만 쓴다
    Optional<AssistantRun> findByPublicId(UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AssistantRun> findWithLockByPublicIdAndUserId(UUID publicId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AssistantRun> findWithLockById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<AssistantRun> findWithLockByRunningSinceIsNotNull();

    // 다른 워커가 잡고 있는 행은 건너뛰어 같은 실행을 두 번 집지 않는다
    @Query(value = """
            SELECT * FROM assistant_run
            WHERE state = 'RUNNING' AND running_since IS NULL
            ORDER BY updated_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<AssistantRun> findNextWaitingForUpdate();

    // 워커가 걸음을 저장하는 중인 행은 건너뛰어, 살아 있는 실행을 실패로 덮어쓰지 않는다
    @Query(value = """
            SELECT * FROM assistant_run
            WHERE state = 'RUNNING' AND running_since IS NOT NULL AND updated_at < :before
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<AssistantRun> findStuckForUpdate(@Param("before") Instant before);
}

package com.gitory.backend.coverletter.infra;

import com.gitory.backend.coverletter.domain.CoverLetter;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CoverLetterRepository extends JpaRepository<CoverLetter, Long> {

    Optional<CoverLetter> findByPublicIdAndUserId(UUID publicId, Long userId);

    Optional<CoverLetter> findByPublicId(UUID publicId);

    Optional<CoverLetter> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CoverLetter> findWithLockByPublicIdAndUserId(UUID publicId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CoverLetter> findWithLockById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<CoverLetter> findWithLockByRunningSinceIsNotNull();

    // 다른 워커가 잡고 있는 행은 건너뛰어 같은 단계를 두 번 집지 않는다
    @Query(value = """
            SELECT * FROM cover_letter
            WHERE stage = 'ASSIGNING' AND running_since IS NULL
            ORDER BY updated_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<CoverLetter> findNextWaitingForUpdate();

    // 워커가 결과를 저장하는 중인 행은 건너뛰어, 끝낸 단계를 실패로 덮어쓰지 않는다
    @Query(value = """
            SELECT * FROM cover_letter
            WHERE running_since < :before
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<CoverLetter> findStuckForUpdate(@Param("before") Instant before);
}

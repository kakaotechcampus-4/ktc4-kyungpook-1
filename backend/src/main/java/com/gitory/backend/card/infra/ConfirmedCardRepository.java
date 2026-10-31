package com.gitory.backend.card.infra;

import com.gitory.backend.card.domain.Card;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Card 엔티티가 지운 시각(deleted_at)을 매핑하지 않아 카드 표를 직접 읽는다 */
public interface ConfirmedCardRepository extends Repository<Card, Long> {

    @Query(value = """
            SELECT id FROM card
            WHERE user_id = :userId AND status = 'CONFIRMED' AND deleted_at IS NULL
            ORDER BY confirmed_at DESC, id DESC
            """, nativeQuery = true)
    List<Long> findLiveIdsNewestFirst(@Param("userId") Long userId);

    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM card WHERE user_id = :userId AND status = 'CONFIRMED' AND deleted_at IS NULL)
            """, nativeQuery = true)
    boolean existsLiveConfirmed(@Param("userId") Long userId);

    @Query(value = "SELECT id FROM card WHERE id IN (:ids) AND deleted_at IS NULL", nativeQuery = true)
    List<Long> findLiveIds(@Param("ids") Collection<Long> ids);

    @Query(value = """
            SELECT id FROM card
            WHERE public_id = :publicId AND user_id = :userId AND status = 'CONFIRMED' AND deleted_at IS NULL
            """, nativeQuery = true)
    Optional<Long> findLiveConfirmedId(@Param("userId") Long userId, @Param("publicId") UUID publicId);

    @Query(value = """
            SELECT id FROM card
            WHERE user_id = :userId AND user_repository_id = :userRepositoryId AND status = 'CONFIRMED'
              AND deleted_at IS NULL
            ORDER BY id
            """, nativeQuery = true)
    List<Long> findLiveConfirmedIdsOfRepository(@Param("userId") Long userId,
                                                @Param("userRepositoryId") Long userRepositoryId);

    @Query(value = """
            SELECT id FROM card
            WHERE user_id = :userId AND status = 'CONFIRMED' AND deleted_at IS NULL
            ORDER BY id
            """, nativeQuery = true)
    List<Long> findLiveConfirmedIdsOfUser(@Param("userId") Long userId);
}

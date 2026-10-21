package com.gitory.backend.card.infra;

import com.gitory.backend.card.domain.Card;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Card 엔티티가 지운 시각(deleted_at)을 매핑하지 않아 카드 표를 직접 읽는다 */
public interface ConfirmedCardRepository extends Repository<Card, Long> {

    @Query(value = """
            SELECT id FROM card
            WHERE user_id = :userId AND status = 'CONFIRMED' AND deleted_at IS NULL
            ORDER BY confirmed_at DESC, id DESC
            """, nativeQuery = true)
    List<Long> findLiveIdsNewestFirst(@Param("userId") Long userId);
}

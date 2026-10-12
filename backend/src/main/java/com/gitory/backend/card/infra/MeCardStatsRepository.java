package com.gitory.backend.card.infra;

import com.gitory.backend.card.domain.Card;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface MeCardStatsRepository extends Repository<Card, Long> {

    // 삭제 표시(deleted_at)는 엔티티에 매핑하지 않아 SQL 로 거른다
    @Query(value = "SELECT COUNT(*) FROM card WHERE user_id = :userId AND status = 'CONFIRMED' AND deleted_at IS NULL",
            nativeQuery = true)
    long countConfirmed(@Param("userId") Long userId);

    @Query(value = """
            SELECT COUNT(*) FROM interview_turn t JOIN card c ON c.id = t.card_id
            WHERE c.user_id = :userId AND c.deleted_at IS NULL AND t.outcome = 'ANSWERED'
            """, nativeQuery = true)
    long countAnsweredTurns(@Param("userId") Long userId);
}

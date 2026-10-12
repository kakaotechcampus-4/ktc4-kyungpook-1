package com.gitory.backend.card.infra;

import com.gitory.backend.card.domain.Card;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface RepositoryCardCountRepository extends Repository<Card, Long> {

    // Card 엔티티에 지운 시각(deleted_at)이 매핑돼 있지 않아 이 모듈의 표를 SQL 로 직접 센다
    @Query(value = """
            SELECT user_repository_id AS "userRepositoryId", COUNT(*) AS "cardCount"
            FROM card
            WHERE user_repository_id IN (:userRepositoryIds) AND status = 'CONFIRMED' AND deleted_at IS NULL
            GROUP BY user_repository_id
            """, nativeQuery = true)
    List<Row> countConfirmed(@Param("userRepositoryIds") Collection<Long> userRepositoryIds);

    interface Row {

        Long getUserRepositoryId();

        Long getCardCount();
    }
}

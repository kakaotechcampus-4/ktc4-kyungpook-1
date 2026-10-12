package com.gitory.backend.card.infra;

import com.gitory.backend.card.domain.Card;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** Card 엔티티가 후보 연결과 삭제 시각을 매핑하지 않아 카드 표를 직접 읽는다 */
public interface CandidateCardRepository extends Repository<Card, Long> {

    @Query(value = """
            SELECT candidate_id AS candidateId, public_id AS publicId
            FROM card
            WHERE candidate_id IN (:candidateIds) AND deleted_at IS NULL
            ORDER BY id
            """, nativeQuery = true)
    List<CandidateCardRow> findLive(@Param("candidateIds") Collection<Long> candidateIds);
}

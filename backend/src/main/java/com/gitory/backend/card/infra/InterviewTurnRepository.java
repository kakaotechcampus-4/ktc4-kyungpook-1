package com.gitory.backend.card.infra;

import com.gitory.backend.card.domain.interview.InterviewTurn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InterviewTurnRepository extends JpaRepository<InterviewTurn, Long> {

    List<InterviewTurn> findByCardIdOrderBySeqAsc(Long cardId);

    Optional<InterviewTurn> findByCardIdAndSeq(Long cardId, short seq);

    // 목록이 카드 몇 장이든 쿼리가 한 번이도록 카드 id 를 묶어서 묻는다
    @Query("""
            SELECT t.cardId FROM InterviewTurn t
            WHERE t.cardId IN :cardIds AND t.outcome = com.gitory.backend.card.domain.interview.TurnOutcome.ANSWERED
            GROUP BY t.cardId
            """)
    List<Long> findCardIdsWithAnswer(@Param("cardIds") Collection<Long> cardIds);
}

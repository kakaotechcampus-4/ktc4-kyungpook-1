package com.gitory.backend.matching.infra;

import com.gitory.backend.matching.domain.MatchJudgement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface MatchJudgementRepository extends JpaRepository<MatchJudgement, Long> {

    List<MatchJudgement> findByCardIdInAndCriterionIdIn(Collection<Long> cardIds, Collection<Long> criterionIds);

    // 두 계산이 같은 짝을 함께 판단하면 먼저 저장한 판단을 남긴다
    @Modifying
    @Query(value = """
            INSERT INTO match_judgement (card_id, card_hash, criterion_id, met, reason, judged_at)
            VALUES (:cardId, :cardHash, :criterionId, :met, :reason, :judgedAt)
            ON CONFLICT (card_id, card_hash, criterion_id) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(@Param("cardId") Long cardId, @Param("cardHash") String cardHash,
                        @Param("criterionId") Long criterionId, @Param("met") boolean met,
                        @Param("reason") String reason, @Param("judgedAt") Instant judgedAt);

    // 카드 글이 바뀌면 옛 글의 판단은 다시 쓰이지 않으므로 지운다
    @Modifying
    @Query("delete from MatchJudgement j where j.cardId = :cardId and j.cardHash <> :cardHash")
    void deleteOtherHashes(@Param("cardId") Long cardId, @Param("cardHash") String cardHash);
}

package com.gitory.backend.card.infra;

import com.gitory.backend.card.domain.CardStatement;
import com.gitory.backend.card.domain.StarField;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CardStatementRepository extends JpaRepository<CardStatement, Long> {

    List<CardStatement> findByCardIdAndVersionNo(Long cardId, short versionNo);

    List<CardStatement> findByCardIdAndVersionNoAndStarSlot(Long cardId, short versionNo, StarField starSlot);

    List<CardStatement> findByCardId(Long cardId);

    @Query("""
            SELECT s FROM CardStatement s JOIN Card c ON c.id = s.cardId
            WHERE c.id IN :cardIds AND s.versionNo = c.currentVersion
            """)
    List<CardStatement> findCurrentOf(@Param("cardIds") Collection<Long> cardIds);

    // 엔티티로 지우면 DELETE 가 flush 때로 미뤄져, 바로 넣는 같은 칸의 새 문장이 먼저 들어가 유니크에 걸린다
    // 근거 연결은 DB 의 ON DELETE CASCADE 로 함께 지워진다
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM CardStatement s WHERE s.cardId = :cardId AND s.versionNo = :versionNo")
    void deleteAllOfVersion(@Param("cardId") Long cardId, @Param("versionNo") short versionNo);
}

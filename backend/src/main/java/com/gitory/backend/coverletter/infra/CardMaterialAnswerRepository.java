package com.gitory.backend.coverletter.infra;

import com.gitory.backend.coverletter.domain.CardMaterialAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface CardMaterialAnswerRepository extends JpaRepository<CardMaterialAnswer, Long> {

    List<CardMaterialAnswer> findByCardIdIn(Collection<Long> cardIds);

    // 두 자소서가 같은 카드·항목의 답을 동시에 기억해도 유니크 키에 걸리지 않고 나중 것이 남는다
    @Modifying
    @Query(value = """
            INSERT INTO card_material_answer (card_id, item, text, updated_at)
            VALUES (:cardId, :item, :text, :now)
            ON CONFLICT (card_id, item) DO UPDATE SET text = EXCLUDED.text, updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    void remember(@Param("cardId") Long cardId, @Param("item") String item, @Param("text") String text,
                  @Param("now") Instant now);
}

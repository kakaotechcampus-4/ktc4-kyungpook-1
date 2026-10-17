package com.gitory.backend.card.infra;

import com.gitory.backend.card.domain.CardSlotNote;
import com.gitory.backend.card.domain.StarField;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CardSlotNoteRepository extends JpaRepository<CardSlotNote, Long> {

    List<CardSlotNote> findByCardIdAndVersionNo(Long cardId, short versionNo);

    List<CardSlotNote> findByCardIdAndVersionNoAndStarSlot(Long cardId, short versionNo, StarField starSlot);

    // 문장과 같은 까닭으로, 바로 다시 넣는 같은 칸의 메모가 유니크에 걸리지 않게 지우기를 먼저 내보낸다
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM CardSlotNote n WHERE n.cardId = :cardId AND n.versionNo = :versionNo")
    void deleteAllOfVersion(@Param("cardId") Long cardId, @Param("versionNo") short versionNo);
}

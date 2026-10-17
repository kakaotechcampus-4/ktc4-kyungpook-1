package com.gitory.backend.card.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import static lombok.AccessLevel.PROTECTED;

/**
 * 카드 버전의 한 칸에 AI 가 남긴 메모로, 비운 칸에는 비운 이유 코드를, 확인이 필요한 칸에는 그 이유 문장을 둔다
 * 사용자가 그 칸을 고치면 새 버전으로 따라가지 않고, 안 고친 칸의 메모만 근거처럼 복사된다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "card_slot_note")
public class CardSlotNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long cardId;

    private short versionNo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 1)
    private StarField starSlot;

    @Enumerated(EnumType.STRING)
    private DropReason dropReason;

    private String reviewReason;

    private CardSlotNote(Long cardId, short versionNo, StarField starSlot, DropReason dropReason,
                         String reviewReason) {
        this.cardId = cardId;
        this.versionNo = versionNo;
        this.starSlot = starSlot;
        this.dropReason = dropReason;
        this.reviewReason = reviewReason;
    }

    static CardSlotNote dropped(Long cardId, short versionNo, StarField starSlot, DropReason reason) {

        return new CardSlotNote(cardId, versionNo, starSlot, reason, null);

    }

    static CardSlotNote review(Long cardId, short versionNo, StarField starSlot, String reason) {

        return new CardSlotNote(cardId, versionNo, starSlot, null, reason);

    }

    CardSlotNote copyTo(short versionNo) {

        return new CardSlotNote(cardId, versionNo, starSlot, dropReason, reviewReason);

    }
}

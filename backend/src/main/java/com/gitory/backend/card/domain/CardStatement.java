package com.gitory.backend.card.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import static lombok.AccessLevel.PROTECTED;

/**
 * 카드 버전 하나의 한 칸 글로, 빈 칸은 행을 만들지 않는다
 * 사용자가 쓴 글은 앞뒤 공백까지 그대로 저장한다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "card_statement")
public class CardStatement {

    // 되묻기 답도 칸의 글을 대신하므로 칸마다 문장은 하나뿐이다
    private static final short ONLY_SEQ = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long cardId;

    private short versionNo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 1)
    private StarField starSlot;

    private short seq;

    private String body;

    @Enumerated(EnumType.STRING)
    private EvidenceType evidenceType;

    @Enumerated(EnumType.STRING)
    private Confidence confidence;

    private Long sourceTurnId;

    private CardStatement(Long cardId, short versionNo, StarField starSlot, String body, EvidenceType evidenceType,
                          Confidence confidence, Long sourceTurnId) {
        if (body == null || CardText.isBlank(body)) {
            throw new IllegalArgumentException("빈 칸은 문장 행을 만들지 않는다: " + starSlot);
        }
        CardText.requireNoNul(body, starSlot + " 칸");
        this.cardId = cardId;
        this.versionNo = versionNo;
        this.starSlot = starSlot;
        this.seq = ONLY_SEQ;
        this.body = body;
        this.evidenceType = evidenceType;
        this.confidence = confidence;
        this.sourceTurnId = sourceTurnId;
    }

    /** 사용자가 직접 쓴 글로, 근거 커밋·확신도·되묻기 턴이 없다 */
    public static CardStatement userStated(Long cardId, short versionNo, StarField starSlot, String body) {

        return new CardStatement(cardId, versionNo, starSlot, body, EvidenceType.USER_STATED, null, null);

    }

    CardStatement copyTo(short versionNo) {

        return new CardStatement(cardId, versionNo, starSlot, body, evidenceType, confidence, sourceTurnId);

    }
}

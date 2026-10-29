package com.gitory.backend.card.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import static lombok.AccessLevel.PROTECTED;

/**
 * AI 초안을 담은 카드 버전에 남기는 초안 단계 하나로, 화면이 값으로 'AI 가 한 일' 문장을 만든다
 * 글 칸은 AI 가 보낸 값을 DB 칸 길이에 맞춰 자르며, reason 은 AI 가 고른 행동에만 있다
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "card_draft_step")
public class CardDraftStep {

    private static final int MAX_CODE = 40;
    private static final int MAX_DETAIL = 200;
    private static final int MAX_REASON = 60;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long cardId;

    private short versionNo;

    private short seq;

    private short roundNo;

    @Enumerated(EnumType.STRING)
    private DraftStepActor actor;

    @Enumerated(EnumType.STRING)
    private DraftStepKind kind;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 1)
    private StarField starSlot;

    private String code;

    private String detail;

    @Enumerated(EnumType.STRING)
    private DraftMaterial material;

    private String reason;

    private CardDraftStep(Long cardId, short versionNo, short seq, DraftStep step) {
        this.cardId = cardId;
        this.versionNo = versionNo;
        this.seq = seq;
        this.roundNo = (short) step.round();
        this.actor = step.actor();
        this.kind = step.kind();
        this.starSlot = step.field();
        this.code = clip(step.code(), MAX_CODE);
        this.detail = clip(step.value(), MAX_DETAIL);
        this.material = step.material();
        this.reason = clip(step.reason(), MAX_REASON);
    }

    static CardDraftStep recorded(Long cardId, short versionNo, short seq, DraftStep step) {

        return new CardDraftStep(cardId, versionNo, seq, step);

    }

    DraftStep view() {

        return new DraftStep(roundNo, actor, kind, starSlot, code, detail, material, reason);

    }

    private static String clip(String text, int max) {

        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);

    }
}

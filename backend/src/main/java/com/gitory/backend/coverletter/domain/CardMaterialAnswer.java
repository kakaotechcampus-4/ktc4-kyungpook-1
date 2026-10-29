package com.gitory.backend.coverletter.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import static lombok.AccessLevel.PROTECTED;

/**
 * 자소서 질문에서 받은 답 중 경험 자체에 대한 것을 카드마다 기억해, 같은 카드를 쓰는 다음 자소서에 미리 채운다
 * 직무 연결·지원 이유는 회사마다 달라 기억하지 않는다(DB CHECK 도 같은 항목만 받는다)
 */
@Entity
@Getter
@NoArgsConstructor(access = PROTECTED)
@Table(name = "card_material_answer")
public class CardMaterialAnswer {

    private static final Set<MaterialItem> REMEMBERED = EnumSet.of(MaterialItem.SITUATION, MaterialItem.ACTION,
            MaterialItem.RESULT, MaterialItem.LEARNING, MaterialItem.TRIGGER);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long cardId;

    @Enumerated(EnumType.STRING)
    private MaterialItem item;

    private String text;
    private Instant updatedAt;

    public static CardMaterialAnswer of(Long cardId, MaterialItem item, String text) {

        if (!REMEMBERED.contains(item)) {
            throw new IllegalArgumentException(item + " 는 카드마다 기억하지 않는 항목이다");
        }

        CardMaterialAnswer answer = new CardMaterialAnswer();
        answer.cardId = cardId;
        answer.item = item;
        answer.text = text;
        answer.updatedAt = CoverLetterTime.now();

        return answer;

    }
}

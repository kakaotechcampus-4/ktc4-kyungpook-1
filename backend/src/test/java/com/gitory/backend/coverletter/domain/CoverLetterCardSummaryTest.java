package com.gitory.backend.coverletter.domain;

import com.gitory.backend.card.domain.CardKind;
import com.gitory.backend.card.domain.CardMaterial;
import com.gitory.backend.card.domain.CardMaterialStatement;
import com.gitory.backend.card.domain.StarField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CoverLetterCardSummaryTest {

    @Test
    @DisplayName("행동·결과 문장만 순서대로 이어 요약하고, 상황·과제 문장은 넣지 않는다")
    void joinsActionAndResultOnly() {

        CardMaterial card = card(new CardMaterialStatement(StarField.S, "배포가 느렸다"),
                new CardMaterialStatement(StarField.T, "줄여야 했다"),
                new CardMaterialStatement(StarField.A, "빌드를 병렬로 돌렸다"),
                new CardMaterialStatement(StarField.A, "캐시를 나눴다"),
                new CardMaterialStatement(StarField.R, "12분에서 4분으로 줄었다"));

        assertThat(CoverLetterCardSummary.of(card)).isEqualTo("빌드를 병렬로 돌렸다 캐시를 나눴다 12분에서 4분으로 줄었다");

    }

    @Test
    @DisplayName("요약은 300자에서 자른다")
    void cutsAtThreeHundredChars() {

        CardMaterial card = card(new CardMaterialStatement(StarField.A, "가".repeat(200)),
                new CardMaterialStatement(StarField.R, "나".repeat(200)));

        assertThat(CoverLetterCardSummary.of(card)).hasSize(300).startsWith("가".repeat(200) + " 나");

    }

    @Test
    @DisplayName("행동·결과 문장이 없으면 있는 문장 전부를, 문장이 하나도 없으면 제목을 보낸다")
    void fallsBackToAllStatementsThenTitle() {

        CardMaterial situationOnly = card(new CardMaterialStatement(StarField.S, "배포가 느렸다"),
                new CardMaterialStatement(StarField.T, "줄여야 했다"));

        assertThat(CoverLetterCardSummary.of(situationOnly)).isEqualTo("배포가 느렸다 줄여야 했다");
        assertThat(CoverLetterCardSummary.of(card())).isEqualTo("배포 시간 단축");

    }

    private static CardMaterial card(CardMaterialStatement... statements) {
        return new CardMaterial(1L, UUID.randomUUID(), "배포 시간 단축", CardKind.TECH, null, null, List.of(statements));
    }
}

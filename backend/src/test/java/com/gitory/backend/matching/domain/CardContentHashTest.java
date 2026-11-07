package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.card.domain.MatchingCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CardContentHashTest {

    private static final MatchingCard CARD = new MatchingCard(1L, 7L, "결제 API", "중복 승인", "한 번만 승인",
            "멱등 키를 두었다", "중복 0건", List.of(CardTech.JAVA, CardTech.SPRING));

    @Test
    @DisplayName("AI 에 보내는 글이 같으면 같은 값이고 기술 이름표 순서는 상관없다")
    void sameTextGivesSameHashRegardlessOfTechOrder() {

        MatchingCard reordered = new MatchingCard(1L, 7L, "결제 API", "중복 승인", "한 번만 승인", "멱등 키를 두었다",
                "중복 0건", List.of(CardTech.SPRING, CardTech.JAVA));

        assertThat(CardContentHash.of(reordered)).isEqualTo(CardContentHash.of(CARD));

    }

    @Test
    @DisplayName("제목·네 칸·기술 이름표 중 하나라도 바뀌거나 글이 칸 사이로 옮겨 가면 값이 달라진다")
    void anyChangeGivesDifferentHash() {

        String original = CardContentHash.of(CARD);

        assertThat(List.of(
                new MatchingCard(1L, 7L, "결제 API 개선", "중복 승인", "한 번만 승인", "멱등 키를 두었다", "중복 0건",
                        CARD.techs()),
                new MatchingCard(1L, 7L, "결제 API", "중복 승인", "한 번만 승인", "멱등 키를 두었다", "중복 0건.",
                        CARD.techs()),
                new MatchingCard(1L, 7L, "결제 API", "중복 승인한 번만 승인", null, "멱등 키를 두었다", "중복 0건",
                        CARD.techs()),
                new MatchingCard(1L, 7L, "결제 API", "중복 승인", "한 번만 승인", "멱등 키를 두었다", "중복 0건",
                        List.of(CardTech.JAVA))))
                .allSatisfy(changed -> assertThat(CardContentHash.of(changed)).isNotEqualTo(original));

    }
}

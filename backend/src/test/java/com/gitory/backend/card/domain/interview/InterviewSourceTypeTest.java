package com.gitory.backend.card.domain.interview;

import com.gitory.backend.card.domain.Card;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewSourceTypeTest {

    @Test
    @DisplayName("직접 작성한 카드는 후보가 없으므로 DIRECT_CARD 다")
    void manualCardIsDirectCard() {

        Card card = Card.startManual(7L, 11L, "카드", null, "key-1");

        assertThat(InterviewSourceType.of(card)).isEqualTo(InterviewSourceType.DIRECT_CARD);

    }
}

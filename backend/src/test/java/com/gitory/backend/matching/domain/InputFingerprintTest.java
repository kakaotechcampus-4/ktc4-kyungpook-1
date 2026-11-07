package com.gitory.backend.matching.domain;

import com.gitory.backend.card.domain.CardTech;
import com.gitory.backend.card.domain.MatchingCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InputFingerprintTest {

    private static final Instant SAVED = Instant.parse("2026-10-30T00:00:00Z");
    private static final MatchingCard PAYMENT = new MatchingCard(11L, 7L, "결제 API", null, null, "멱등 키를 두었다", null,
            List.of(CardTech.SPRING));
    private static final MatchingCard SESSION = new MatchingCard(12L, 8L, "세션 저장", null, null, "세션을 DB 에 두었다", null,
            List.of(CardTech.REDIS));

    @Test
    @DisplayName("같은 카드는 읽은 순서가 달라도 같은 지문이고, 카드 글·카드 수·인적사항 저장 시각이 바뀌면 지문이 바뀐다")
    void changesOnlyWithCardContentOrProfileSavedTime() {

        String fingerprint = InputFingerprint.of(List.of(PAYMENT, SESSION), SAVED);

        assertThat(InputFingerprint.of(List.of(SESSION, PAYMENT), SAVED)).isEqualTo(fingerprint);
        assertThat(InputFingerprint.of(List.of(PAYMENT, new MatchingCard(12L, 8L, "세션 저장", null, null,
                "세션을 Redis 에 두었다", null, List.of(CardTech.REDIS))), SAVED)).isNotEqualTo(fingerprint);
        assertThat(InputFingerprint.of(List.of(PAYMENT), SAVED)).isNotEqualTo(fingerprint);
        assertThat(InputFingerprint.of(List.of(PAYMENT, SESSION), SAVED.plusSeconds(1))).isNotEqualTo(fingerprint);
        assertThat(InputFingerprint.of(List.of(PAYMENT, SESSION), null)).isNotEqualTo(fingerprint);

    }
}
